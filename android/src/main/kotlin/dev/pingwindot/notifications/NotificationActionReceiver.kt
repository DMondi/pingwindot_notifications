package dev.pingwindot.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Handles taps on the "+" action button shown in the notification tray.
 *
 * Strategy:
 *   1. Read access_token from SharedPreferences and call the Supabase RPC.
 *   2. On 401, run a native refresh via [SupabaseAuthHelper], retry once.
 *   3. On any hard failure (network, refresh 4xx, missing session), open the
 *      host app via PendingIntent so the user can finish the action in UI.
 *
 * No offline retry queue: PingWinDot signals require an immediate ack.
 * Surfacing the failure to the user is preferable to silent retries that
 * arrive late.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIRM) return

        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
        val descriptor = intent.getStringExtra(EXTRA_DESCRIPTOR) ?: return
        val androidNotifId = intent.getIntExtra(EXTRA_ANDROID_NOTIF_ID, notificationId.hashCode())
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "PingWinDot"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""

        showProgress(context, androidNotifId, title, body)

        val pendingResult = goAsync()
        thread(start = true, name = "PingWinConfirm-$notificationId") {
            try {
                val ok = performConfirm(context, descriptor)
                if (ok) {
                    showSuccess(context, androidNotifId, title)
                } else {
                    openAppForFallback(context, notificationId, descriptor)
                    cancel(context, androidNotifId)
                }
            } catch (e: Exception) {
                openAppForFallback(context, notificationId, descriptor)
                cancel(context, androidNotifId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun performConfirm(context: Context, descriptor: String): Boolean {
        val config = SupabaseAuthHelper.readConfig(context) ?: return false
        var session = SupabaseAuthHelper.readSession(context) ?: return false

        var status = callRpc(config, session.accessToken, descriptor)
        if (status == 401) {
            val refreshed = SupabaseAuthHelper.refresh(context, config, session.refreshToken)
                ?: return false
            session = refreshed
            status = callRpc(config, session.accessToken, descriptor)
        }
        return status in 200..299
    }

    private fun callRpc(
        config: SupabaseAuthHelper.Config,
        accessToken: String,
        descriptor: String,
    ): Int {
        return try {
            val url = URL("${config.supabaseUrl}/rest/v1/rpc/confirm_notification_status")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", config.anonKey)
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Prefer", "return=minimal")
            }
            val body = JSONObject().apply { put("p_descriptor", descriptor) }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            try {
                if (code in 200..299) conn.inputStream.close() else conn.errorStream?.close()
            } catch (_: Exception) {}
            code
        } catch (e: Exception) {
            -1
        }
    }

    private fun showProgress(context: Context, androidNotifId: Int, title: String, body: String) {
        PingWinFcmService.ensureChannel(context)
        val builder = NotificationCompat.Builder(context, PingWinFcmService.activeChannelId(context))
            .setSmallIcon(context.applicationInfo.icon)
            .setColor(PingWinFcmService.ACCENT_COLOR)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText("Надсилаємо доповідь…")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setProgress(0, 0, true)
            .setAutoCancel(false)
            .setOngoing(true)
        try {
            NotificationManagerCompat.from(context).notify(androidNotifId, builder.build())
        } catch (_: SecurityException) {}
    }

    private fun showSuccess(context: Context, androidNotifId: Int, title: String) {
        PingWinFcmService.ensureChannel(context)
        val builder = NotificationCompat.Builder(context, PingWinFcmService.activeChannelId(context))
            .setSmallIcon(context.applicationInfo.icon)
            .setColor(PingWinFcmService.ACCENT_COLOR)
            .setContentTitle(title)
            .setContentText("Доповідь прийнято ✓")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(10_000L)
        try {
            NotificationManagerCompat.from(context).notify(androidNotifId, builder.build())
        } catch (_: SecurityException) {}
    }

    private fun cancel(context: Context, androidNotifId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(androidNotifId)
        } catch (_: SecurityException) {}
    }

    private fun openAppForFallback(
        context: Context,
        notificationId: String,
        descriptor: String,
    ) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.apply {
            putExtra("pending_action", "confirm_notification")
            putExtra("notification_id", notificationId)
            putExtra("descriptor", descriptor)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            // Allowed: receiver responding to an explicit user action (the
            // tap on the action button). Not a background activity launch
            // in the API 29+ restriction sense.
            context.startActivity(launch)
        } catch (e: Exception) {
            val pi = PendingIntent.getActivity(
                context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try { pi.send() } catch (_: Exception) {}
        }
    }

    companion object {
        const val ACTION_CONFIRM = "dev.pingwindot.notifications.ACTION_CONFIRM"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_DESCRIPTOR = "descriptor"
        const val EXTRA_ANDROID_NOTIF_ID = "android_notif_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
    }
}
