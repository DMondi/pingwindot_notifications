package dev.pingwindot.notifications

import android.content.Context
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter plugin entry point.
 *
 * Exposes a single MethodChannel `dev.pingwindot.notifications/control` so the
 * host app can flip the foreground flag used by [PingWinFcmService] to decide
 * whether to play sound/vibration natively (background) or defer to the in-app
 * Dart audio handler (foreground).
 *
 * The flag is mirrored into `FlutterSharedPreferences` (key
 * `flutter.pingwin.app_in_foreground`) so the FCM service — which runs in a
 * separate process context and may receive a push before any plugin lifecycle
 * callback — can read it without binding to the Flutter engine.
 *
 * Method: `setForegroundState(bool inForeground)`
 *   Writes `pingwin.app_in_foreground` to SharedPreferences. Returns `null`.
 */
class PingwindotNotificationsPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private var channel: MethodChannel? = null
    private var appContext: Context? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME).also {
            it.setMethodCallHandler(this)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        appContext = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val ctx = appContext
        if (ctx == null) {
            result.error("not_attached", "Plugin not attached to engine", null)
            return
        }
        when (call.method) {
            "setForegroundState" -> {
                val value = call.arguments as? Boolean
                if (value == null) {
                    result.error("bad_args", "expected bool", null)
                    return
                }
                NotificationPrefs.setForeground(ctx, value)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    companion object {
        const val CHANNEL_NAME = "dev.pingwindot.notifications/control"
    }
}
