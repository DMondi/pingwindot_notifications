package dev.pingwindot.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
 *   recipient_id      string  notification_recipients row id, passed as
 *                             `p_id` to the Supabase RPC
 *   title             string  notification title
 *   body              string  notification body
 *
 * The host Flutter app's edge function MUST send these as `data` only —
 * any `notification` block in the FCM payload causes Android to render
 * the notification itself and skip this service in background state.
 */
class PingWinFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val notificationId = data["notification_id"] ?: return
        val recipientId = data["recipient_id"] ?: return
        val title = data["title"] ?: "PingWinDot"
        val body = data["body"] ?: ""

        ensureChannel(this)

        val androidNotifId = notificationId.hashCode()

        val confirmIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CONFIRM
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(NotificationActionReceiver.EXTRA_RECIPIENT_ID, recipientId)
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
            putExtra("recipient_id", recipientId)
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(false)
            .addAction(
                android.R.drawable.ic_input_add,
                "+",
                confirmPendingIntent,
            )
            .also { if (tapPendingIntent != null) it.setContentIntent(tapPendingIntent) }

        try {
            NotificationManagerCompat.from(this).notify(androidNotifId, builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (API 33+). Drop silently — the
            // user will still see the data in-app on next launch.
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Host app already has a Dart-side onTokenRefresh listener
        // (initializeAndroidFCM custom action) that updates fcm_tokens in
        // Supabase. No need to duplicate here.
    }

    companion object {
        const val CHANNEL_ID = "pingwin_signals"
        const val CHANNEL_NAME = "PingWin Signals"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "PingWin signals with quick-confirm action"
                        enableVibration(true)
                    }
                    nm.createNotificationChannel(channel)
                }
            }
        }
    }
}
