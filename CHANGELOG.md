# Changelog

## 0.2.0

* **Breaking:** FCM data payload now expects field `descriptor` instead of
  `recipient_id`. Edge function on the host side must include
  `descriptor` (the unique `notification_recipients.descriptor` value)
  rather than the row id.
* **Breaking:** RPC call now passes `p_descriptor` instead of `p_id`. The
  host's Supabase RPC `confirm_notification_status` must accept a
  `p_descriptor text` parameter and look up the row by descriptor (which
  is `UNIQUE` in the schema and equals `<notification_id>-<munit_id>`).
* No edge-function lookup is required to find the descriptor — it can be
  constructed deterministically from `notification_id` and `munit_id`,
  although hosts may keep the lookup as a sanity check.

## 0.1.0

* Initial release.
* Android-only Flutter plugin contributing:
  * `PingWinFcmService` — `FirebaseMessagingService` subclass that intercepts
    data-only FCM payloads and renders a heads-up notification with a single
    "+" action button.
  * `NotificationActionReceiver` — handles taps on the action button, calls
    Supabase RPC `confirm_notification_status(p_id := recipient_id)`, with
    native access-token refresh on 401.
  * `SupabaseAuthHelper` — reads mirrored session from SharedPreferences,
    serialises refresh through a `FileLock` to prevent rotation races with
    the supabase_flutter SDK.
* No Dart API surface — host app integrates via:
  * AndroidManifest `<meta-data>` for Supabase URL and anon key.
  * SharedPreferences key `pingwin.session` (Dart writes mirrored session,
    plugin reads `flutter.pingwin.session` natively).
* Edge function payload contract: data-only fields `notification_id`,
  `recipient_id`, `title`, `body`.
