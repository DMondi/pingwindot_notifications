# Changelog

## 0.4.1

* **Breaking pref-key rename** so the plugin can be driven directly by
  FlutterFlow App State (Persisted Boolean) fields. Old keys
  `pingwin.notif_*` are no longer read; new keys mirror the FF persistence
  convention `flutter.ff_<fieldName>`:
  * `notifSoundEnabled` (was `pingwin.notif_sound_enabled`)
  * `notifVibrationEnabled` (was `pingwin.notif_vibration_enabled`)
  * `notifMuteLevel0` / `notifMuteLevel1` / `notifMuteLevel2`
    (was `pingwin.notif_mute_level_*`)
* No-op for hosts that have not yet written any of these — the legacy
  `pingwin_signals` fallback still kicks in, so 0.3.0-style behaviour is
  preserved out of the box.
* `app_in_foreground` flag remains under `flutter.pingwin.app_in_foreground`
  (plugin-managed, not exposed via FF App State).

## 0.4.0

* **New: per-level sound on push receipt.** Bundles three notification sounds
  in `android/src/main/res/raw/` (`pingwin_sound_default.mp3`,
  `pingwin_sound_warning.wav`, `pingwin_sound_alarm.mp3`) and selects one
  based on the new `sound_type` data field (`"0"`/`"1"`/`"2"`). Plays via
  `MediaPlayer` with `USAGE_NOTIFICATION` from the FCM service, so it works
  even when the app is killed.
* **New: per-level vibration patterns.** `0` → short tick, `1` → double-tap,
  `2` → long alarm × 3. Uses `VibrationEffect.createWaveform` on API 26+ with
  legacy fallback.
* **New: SharedPreferences-driven control.** Five keys (see
  `PingwindotPrefs`): global sound/vibration switches plus per-level mute.
  Defaults to legacy v0.3.0 behaviour (system sound on `pingwin_signals`
  channel) until the host writes any notif-pref, then switches to the new
  silent channel `pingwin_signals_v2` and drives sound/vibe natively.
* **New: foreground dedup.** `PingwindotNotifications.setForegroundState(bool)`
  flips a flag the FCM service reads to skip native sound/vibe when the
  in-app Dart handler is active.
* **New: Dart plugin API.** `PingwindotNotifications` class with
  `setForegroundState`. The plugin is no longer Dart-API-less.
* New manifest channel id: `pingwin_signals_v2` is now the
  `default_notification_channel_id`. The legacy `pingwin_signals` channel
  remains for the fallback path.
* Manifest now declares `<uses-permission android:name="android.permission.VIBRATE"/>`.
* No FCM payload contract change beyond the optional `sound_type` field.
* Drop-in compatible with 0.3.0 hosts that don't write any notif-pref.

## 0.3.0

* Localised all user-facing strings to Ukrainian (the plugin is purpose-built
  for the PingWinDot signal app, which is Ukrainian-only):
  * Action button label: "+" → "Прийняв +"
  * Progress subtext: "Sending acknowledgement…" → "Надсилаємо …"
  * Success body: "Acknowledgement received ✓" → "прийнято ✓"
  * Channel name: "PingWin Signals" → "Сигнали PingWin"
  * Channel description updated accordingly.
* Added brand accent colour `#3498DB` via `setColor()` on all three
  notification states (initial, progress, success). On Material You
  (Android 12+) this colourises the action button text; on older versions
  it tints the small icon.
* No payload / RPC contract changes — drop-in compatible with 0.2.0 hosts.

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
