/// PingWinDot native notification plugin.
///
/// This plugin contributes Android-only native code that:
///   1. Intercepts FCM data-only messages and renders a heads-up notification
///      with a single confirm action button.
///   2. Handles taps on that action button via a [BroadcastReceiver] and calls
///      the Supabase RPC `confirm_notification_status(p_descriptor := ...)`
///      directly over REST — without spawning a Flutter background isolate.
///
/// The Dart side does not expose any callable API. The host Flutter app must:
///   * Mirror the current Supabase session into SharedPreferences key
///     `pingwin.session` (the plugin reads `flutter.pingwin.session` natively).
///   * Provide `dev.pingwindot.notifications.SUPABASE_URL` and
///     `dev.pingwindot.notifications.SUPABASE_ANON_KEY` as `<meta-data>`
///     entries inside its `<application>` block.
///   * Send Android FCM payloads as **data-only** with the fields
///     `notification_id`, `descriptor`, `title`, `body`.
///
/// See README for full integration details.
library pingwindot_notifications;

/// Plugin version string. Useful for diagnostics if the host app wants to
/// log which plugin build it is shipping with.
const String pingwindotNotificationsVersion = '0.2.0';
