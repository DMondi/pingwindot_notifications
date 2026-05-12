package dev.pingwindot.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Intercepts FCM data-only payloads and renders a heads-up notification
 * with a confirm action button. Runs natively without spawning a Flutter
 * isolate — works reliably on aggressive OEM background killers (Samsung
 * One UI in particular).
 *
 * Expected FCM `data` fields:
 *   notification_id   string  unique id, used as Android notification id
 *                             (via hashCode) and for dedup
 *   descriptor        string  notification_recipients.descriptor (unique),
 *                             passed as `p_descriptor` to the Supabase RPC
 *   title             string  notification title
 *   body              string  notification body
 *   sound_type        string  "0" | "1" | "2" — selects bundled sound +
 *                             vibration pattern. Default "0".
 *
 * Sound/vibration behaviour depends on whether the host app has ever written
 * any notif_-prefixed preference (see [NotificationPrefs.hasAnyConfig]):
 *   - Not configured → legacy channel `pingwin_signals` with system sound +
 *     channel-default vibration (matches v0.3.0).
 *   - Configured → silent channel `pingwin_signals_v2`; sound played via
 *     [MediaPlayer] from `R.raw.pingwin_sound_*`, vibration via [Vibrator]
 *     with per-level pattern. Both gated by global on/off + per-level mute.
 *     If [NotificationPrefs.isAppInForeground] is true the in-app Dart
 *     handler plays the sound, so this service stays quiet.
 */
class PingWinFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val notificationId = data["notification_id"] ?: return
        val descriptor = data["descriptor"] ?: return
        val title = data["title"] ?: "PingWinDot"
        val body = data["body"] ?: ""
        val soundLevel = (data["sound_type"] ?: "0").toIntOrNull()?.coerceIn(0, 2) ?: 0

        val configured = NotificationPrefs.hasAnyConfig(this)
        val channelId = if (configured) CHANNEL_ID_V2 else CHANNEL_ID_LEGACY
        ensureChannel(this, channelId)

        val androidNotifId = notificationId.hashCode()

        val confirmIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CONFIRM
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationActionReceiver.EXTRA_DESCRIPTOR, descriptor)
            putExtra(NotificationActionReceiver.EXTRA_ANDROID_NOTIF_ID, androidNotifId)
            putExtra(NotificationActionReceiver.EXTRA_TITLE, title)
            putExtra(NotificationActionReceiver.EXTRA_BODY, body)
        }

        val confirmPendingIntent = PendingIntent.getBroadcast(
            this,
            androidNotifId,
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra("notification_id", notificationId)
            putExtra("descriptor", descriptor)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = if (tapIntent != null) {
            PendingIntent.getActivity(
                this,
                androidNotifId,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(applicationInfo.icon)
            .setColor(ACCENT_COLOR)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(false)
            .addAction(
                android.R.drawable.ic_input_add,
                "Прийняв +",
                confirmPendingIntent,
            )
            .also { if (tapPendingIntent != null) it.setContentIntent(tapPendingIntent) }

        if (configured) {
            // Silent channel — explicitly suppress builder-level audio/vibe so
            // pre-O devices don't re-derive defaults from priority.
            builder.setSound(null)
            builder.setVibrate(longArrayOf(0))
        }

        try {
            NotificationManagerCompat.from(this).notify(androidNotifId, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (API 33+). Drop silently — the
            // user will still see the data in-app on next launch.
        }

        if (configured) {
            playCustomFeedback(soundLevel)
        }
    }

    /** Plays MediaPlayer + Vibrator according to prefs. Skipped entirely when
     *  the app is foreground (Dart in-app handler does it, avoiding double). */
    private fun playCustomFeedback(level: Int) {
        if (NotificationPrefs.isAppInForeground(this)) return
        if (NotificationPrefs.isLevelMuted(this, level)) return

        if (NotificationPrefs.isSoundEnabled(this)) {
            playSound(level)
        }
        if (NotificationPrefs.isVibrationEnabled(this)) {
            vibrate(level)
        }
    }

    private fun playSound(level: Int) {
        val resId = when (level) {
            1 -> R.raw.pingwin_sound_warning
            2 -> R.raw.pingwin_sound_alarm
            else -> R.raw.pingwin_sound_default
        }
        try {
            val uri = Uri.parse("android.resource://$packageName/$resId")
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@PingWinFcmService, uri)
                setOnCompletionListener { it.release() }
                setOnErrorListener { player, _, _ -> player.release(); true }
                prepare()
                start()
            }
            // Defensive — prepare() can throw on bad data; covered by catch.
            mp.toString() // no-op
        } catch (_: Throwable) {
            // Audio playback is best-effort; never crash the FCM service.
        }
    }

    private fun vibrate(level: Int) {
        val pattern = when (level) {
            1 -> longArrayOf(0, 80, 100, 80)
            2 -> longArrayOf(0, 600, 200, 600, 200, 600)
            else -> longArrayOf(0, 80)
        }
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Host app already has a Dart-side onTokenRefresh listener
        // (initializeAndroidFCM custom action) that updates fcm_tokens in
        // Supabase. No need to duplicate here.
    }

    companion object {
        /** Legacy channel — kept so host apps that haven't configured prefs
         *  yet still get a working notification with system defaults. */
        const val CHANNEL_ID_LEGACY = "pingwin_signals"

        /** Silent channel — used once the host writes any notif_-pref. We
         *  drive sound + vibration manually so per-level mute works. */
        const val CHANNEL_ID_V2 = "pingwin_signals_v2"

        const val CHANNEL_NAME = "Сигнали PingWin"

        /** Brand accent #3498DB — used by setColor() for small icon tint and
         *  Material You action-button text colorization. */
        val ACCENT_COLOR: Int = Color.parseColor("#3498DB")

        /** Returns the channel id appropriate for the current pref state. */
        fun activeChannelId(context: Context): String =
            if (NotificationPrefs.hasAnyConfig(context)) CHANNEL_ID_V2 else CHANNEL_ID_LEGACY

        /** Convenience overload — ensures whichever channel is currently active. */
        fun ensureChannel(context: Context) {
            ensureChannel(context, activeChannelId(context))
        }

        fun ensureChannel(context: Context, channelId: String) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) != null) return

            val channel = when (channelId) {
                CHANNEL_ID_V2 -> NotificationChannel(
                    channelId,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Сигнали PingWin із кнопкою швидкої доповіді"
                    enableVibration(false)
                    setSound(null, null)
                }
                else -> NotificationChannel(
                    channelId,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Сигнали PingWin із кнопкою швидкої доповіді"
                    enableVibration(true)
                }
            }
            nm.createNotificationChannel(channel)
        }
    }
}
