# pingwindot_notifications

Android-only Flutter plugin that gives [PingWinDot](https://pingwin.dev) FCM
notifications a single confirm action button in the system tray, and sends
the acknowledgement to a Supabase RPC natively — without launching a Flutter
background isolate.

> ⚠️ This plugin is purpose-built for the PingWinDot signal app. It hard-codes
> the RPC name `confirm_notification_status(p_id text)` and a specific
> SharedPreferences session schema. Useful as a reference for building similar
> integrations, but not a general-purpose notification library.

## Why a native plugin

FCM v1 `notification` payloads cannot carry action buttons. The standard
Flutter alternative — `flutter_local_notifications` with a data-only payload
and a Dart background handler — is unreliable on aggressive OEM background
killers (Samsung One UI in particular kills the background Dart isolate before
the handler runs).

Going through a native `FirebaseMessagingService` sidesteps both: action
buttons work, and the Android service is much harder for the OS to kill
than a Dart background isolate.

## What the plugin does

1. Registers a `FirebaseMessagingService` (`PingWinFcmService`) that takes
   over FCM delivery for data-only payloads.
2. Renders a heads-up notification with title, body, and a single "+" action.
3. On "+" tap, a `BroadcastReceiver` (`NotificationActionReceiver`):
   * Reads the Supabase session from SharedPreferences.
   * Calls `POST /rest/v1/rpc/confirm_notification_status` with `p_id` set
     to `recipient_id` from the FCM payload.
   * On HTTP 401, refreshes the access token natively via
     `POST /auth/v1/token?grant_type=refresh_token` and retries once.
   * On hard failure (no session, network down, refresh 4xx) — opens the
     host app via `PendingIntent` with extras so the user can finish the
     action in UI.
4. Serialises native refresh through a `FileLock` to prevent rotation races
   with the `supabase_flutter` SDK living in the same process.

## Host app integration

The plugin contributes only Android Manifest entries + native classes. There
is no Dart API to call. The host app must do four things:

### 1. Add `<meta-data>` for Supabase config

Inside `<application>` of the host's `AndroidManifest.xml`:

```xml
<meta-data
    android:name="dev.pingwindot.notifications.SUPABASE_URL"
    android:value="https://your-project.supabase.co" />
<meta-data
    android:name="dev.pingwindot.notifications.SUPABASE_ANON_KEY"
    android:value="<your anon public key>" />
```

In FlutterFlow, this goes into Project Settings → Android → AndroidManifest.xml
→ App Component Tags. Values can be sourced from FlutterFlow Environment
Values via `{{supabaseUrl}}` / `{{supabaseAnonKey}}` placeholders.

### 2. Mirror the Supabase session into SharedPreferences

The plugin reads `flutter.pingwin.session` (the `flutter.` prefix is added
automatically by the `shared_preferences` plugin on Android). Mirror the
current session whenever it changes:

```dart
import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

Future<void> mirrorSupabaseSession() async {
  final supabase = Supabase.instance.client;
  await _writeMirror(supabase.auth.currentSession);
  supabase.auth.onAuthStateChange.listen((data) async {
    await _writeMirror(data.session);
  });
}

Future<void> _writeMirror(Session? session) async {
  final prefs = await SharedPreferences.getInstance();
  if (session == null) {
    await prefs.remove('pingwin.session');
    return;
  }
  await prefs.setString('pingwin.session', jsonEncode({
    'access_token': session.accessToken,
    'refresh_token': session.refreshToken ?? '',
    'expires_at': session.expiresAt ?? 0,
  }));
}
```

Call `mirrorSupabaseSession()` once on app startup.

### 3. Send data-only FCM payloads for Android

Edge function (or whatever delivers your FCM messages) must send Android
payloads as `data` only — any `notification` block makes Android render the
notification itself, bypassing the plugin in background state.

Required `data` fields:

| Field             | Type   | Purpose                                                |
| ----------------- | ------ | ------------------------------------------------------ |
| `notification_id` | string | unique id, used for dedup and Android notif id        |
| `recipient_id`    | string | passed as `p_id` to `confirm_notification_status` RPC |
| `title`           | string | notification title                                     |
| `body`            | string | notification body                                      |

Example FCM v1 message:

```json
{
  "message": {
    "token": "<device fcm token>",
    "android": {"priority": "HIGH"},
    "data": {
      "notification_id": "abc123",
      "recipient_id": "550e8400-e29b-41d4-a716-446655440000",
      "title": "New signal",
      "body": "Radio call 13:42 — Kyiv south"
    }
  }
}
```

### 4. POST_NOTIFICATIONS permission (Android 13+)

Ensure the host app has `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`
declared and that the permission is requested at runtime (the
`firebase_messaging` plugin's `requestPermission()` covers this).

## Database expectations

The plugin calls `confirm_notification_status(p_id text)` exactly the same
way the in-app "+" button does. If you change the RPC signature in your
Supabase project, fork this plugin or pin to a version compatible with the
old signature — the RPC name and parameter shape are baked into the native
code.

## License

MIT
