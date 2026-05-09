# pingwindot_notifications

Android-only Flutter plugin that gives [PingWinDot](https://pingwin.dev) FCM
notifications a single confirm action button in the system tray, and sends
the acknowledgement to a Supabase RPC natively — without launching a Flutter
background isolate.

> ⚠️ This plugin is purpose-built for the PingWinDot signal app. It hard-codes
> the RPC name `confirm_notification_status(p_id text)` and a specific
> SharedPreferences session schema. Useful as a reference for building similar
> integrations, but not a general-purpose notification library.

## License

MIT
