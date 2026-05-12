/// PingWinDot native notification plugin.
///
/// Android-only plugin that:
///   1. Intercepts FCM data-only messages and renders a heads-up notification
///      with a single confirm action button.
///   2. Handles taps on that action button via a [BroadcastReceiver] and calls
///      the Supabase RPC `confirm_notification_status(p_descriptor := ...)`
///      directly over REST — without spawning a Flutter background isolate.
///   3. Plays a per-level sound (bundled in `res/raw`) and vibrates with a
///      per-level pattern when a push arrives in background/killed state,
///      gated by SharedPreferences flags.
///
/// Host app integration:
///   * Mirror the current Supabase session into SharedPreferences key
///     `pingwin.session` (the plugin reads `flutter.pingwin.session` natively).
///   * Provide `dev.pingwindot.notifications.SUPABASE_URL` and
///     `dev.pingwindot.notifications.SUPABASE_ANON_KEY` as `<meta-data>`
///     entries inside its `<application>` block.
///   * Send Android FCM payloads as **data-only** with the fields
///     `notification_id`, `descriptor`, `title`, `body`, `sound_type`.
///   * Call [PingwindotNotifications.setForegroundState] from a
///     `WidgetsBindingObserver` so the native service knows when to defer
///     audio to the in-app player and avoid double playback.
///   * (Optional) Write the [PingwindotNotifications.prefs] keys via the
///     Flutter `shared_preferences` plugin to enable custom sounds/vibration.
///     If no notif_-key is ever written, the plugin falls back to legacy
///     channel behaviour with the system default sound (v0.3.0 parity).
library pingwindot_notifications;

import 'package:flutter/services.dart';

/// Plugin version string.
const String pingwindotNotificationsVersion = '0.4.0';

/// Public API of the native plugin.
class PingwindotNotifications {
  PingwindotNotifications._();

  static const MethodChannel _channel =
      MethodChannel('dev.pingwindot.notifications/control');

  /// Tells the native FCM service whether the Flutter app is currently in the
  /// foreground. When `true`, the service will not play sound or vibrate on
  /// push receipt — the in-app Dart audio handler is responsible for that.
  ///
  /// Wire this up via [WidgetsBindingObserver.didChangeAppLifecycleState]:
  /// `AppLifecycleState.resumed → true`, anything else → `false`.
  static Future<void> setForegroundState(bool inForeground) async {
    try {
      await _channel.invokeMethod<void>('setForegroundState', inForeground);
    } on MissingPluginException {
      // Not running on Android or plugin not yet attached — safe to ignore.
    }
  }
}

/// SharedPreferences keys read by the native side. Use the Flutter
/// `shared_preferences` plugin and pass these (without the `flutter.` prefix
/// the platform side adds — the Dart plugin handles that automatically).
abstract final class PingwindotPrefs {
  /// Bool. Master sound switch. Default true when any notif-pref is set.
  static const String soundEnabled = 'pingwin.notif_sound_enabled';

  /// Bool. Master vibration switch. Default true when any notif-pref is set.
  static const String vibrationEnabled = 'pingwin.notif_vibration_enabled';

  /// Bool. Mute both sound and vibration for `sound_type == 0`.
  static const String muteLevel0 = 'pingwin.notif_mute_level_0';

  /// Bool. Mute both sound and vibration for `sound_type == 1`.
  static const String muteLevel1 = 'pingwin.notif_mute_level_1';

  /// Bool. Mute both sound and vibration for `sound_type == 2`.
  static const String muteLevel2 = 'pingwin.notif_mute_level_2';
}
