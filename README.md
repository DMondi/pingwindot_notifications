# pingwindot_notifications

Android-only Flutter plugin that renders FCM signal notifications with a
single confirm action button in the system tray and sends the acknowledgement
to a Supabase RPC natively — without launching a Flutter background isolate.

> ⚠️ **Purpose-built**, not general-purpose. This plugin hard-codes the RPC
> name `confirm_notification_status(p_descriptor text)` and a specific
> SharedPreferences session schema used by the [PingWinDot](https://pingwin.dev)
> signal app. It is published openly so the host app can pull it as a
> dependency, and as a reference for anyone building a similar
> action-button-from-shade integration over Supabase. To reuse, fork and
> adapt the RPC call site and the prefs key.

## Why a native plugin

FCM v1 `notification` payloads cannot carry action buttons. The standard
Flutter alternative — `flutter_local_notifications` with a data-only payload
plus a Dart background handler — is unreliable on aggressive OEM background
killers (Samsung One UI in particular kills the Dart background isolate
before the handler runs).

A native `FirebaseMessagingService` sidesteps both problems: action buttons
work, and the Android service is much harder for the OS to kill than a
Dart isolate.

## What it does

* Registers a `FirebaseMessagingService` that takes over FCM delivery for
  data-only payloads and renders a heads-up notification with one "+" action.
* On tap, a `BroadcastReceiver` reads the Supabase session from
  `SharedPreferences`, calls
  `POST /rest/v1/rpc/confirm_notification_status` with `p_descriptor` from
  the FCM payload, and updates the notification to a success state.
* On HTTP 401, refreshes the access token natively via
  `POST /auth/v1/token?grant_type=refresh_token` and retries once.
* On any hard failure (no session, network down, refresh 4xx) opens the host
  app via `PendingIntent` so the user can finish the action in UI. No
  offline retry queue — signals are time-sensitive and stale acks are worse
  than visible failures.
* Serialises native refresh through a `FileLock` to prevent rotation races
  with the `supabase_flutter` SDK living in the same process.

## Host app contract

A consuming app must:

1. Send Android FCM payloads as **data-only** with the fields
   `notification_id`, `descriptor`, `title`, `body` (any `notification`
   block makes Android render the notification itself, bypassing this
   plugin in background state). `descriptor` is the unique key the
   plugin will pass back to the RPC.
2. Mirror the current Supabase session into a known SharedPreferences key
   (`pingwin.session`) on every auth state change, encoded as JSON with
   `access_token`, `refresh_token`, `expires_at`.
3. Declare `<meta-data>` entries inside `<application>` for
   `dev.pingwindot.notifications.SUPABASE_URL` and
   `dev.pingwindot.notifications.SUPABASE_ANON_KEY`.
4. Have a Supabase RPC `confirm_notification_status(p_descriptor text)` that
   takes the descriptor string and performs whatever ack semantics the host
   needs (typically: look up the row by descriptor and mark confirmed).

The plugin contributes the `<service>` and `<receiver>` entries
automatically through Android Manifest merger — host does not need to
register them explicitly.

## Platform support

* **Android**: API 21+ (Lollipop and newer).
* **iOS / Web / desktop**: not supported. The plugin is a no-op on these
  platforms — adding it as a dependency causes no issues but also does
  nothing.

## Source

Source, issues, and discussion:
[github.com/DMondi/pingwindot_notifications](https://github.com/DMondi/pingwindot_notifications)

## License

MIT
