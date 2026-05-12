package dev.pingwindot.notifications

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for sound/vibration preferences shared between the
 * Flutter side (via `shared_preferences` plugin) and the native FCM service.
 *
 * Storage file: `FlutterSharedPreferences` (the same one the Dart
 * `shared_preferences` plugin uses). Keys carry the `flutter.` prefix Android
 * sees but Dart drops. Mirrors the existing pattern used by
 * [SupabaseAuthHelper] for the session.
 *
 * Default behaviour when *no* notif_-key has ever been written: caller should
 * fall back to the legacy channel (system sound + vibration on the channel),
 * matching v0.3.0 behaviour. See [hasAnyConfig].
 */
internal object NotificationPrefs {

    private const val PREFS = "FlutterSharedPreferences"

    const val KEY_SOUND_ENABLED = "flutter.pingwin.notif_sound_enabled"
    const val KEY_VIBRATION_ENABLED = "flutter.pingwin.notif_vibration_enabled"
    const val KEY_MUTE_LEVEL_PREFIX = "flutter.pingwin.notif_mute_level_" // + 0/1/2
    const val KEY_FOREGROUND = "flutter.pingwin.app_in_foreground"

    private val ALL_NOTIF_KEYS = listOf(
        KEY_SOUND_ENABLED,
        KEY_VIBRATION_ENABLED,
        KEY_MUTE_LEVEL_PREFIX + "0",
        KEY_MUTE_LEVEL_PREFIX + "1",
        KEY_MUTE_LEVEL_PREFIX + "2",
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True iff the user (or app) has ever written a notif-related preference.
     *  When false, callers should use the legacy fallback channel. */
    fun hasAnyConfig(ctx: Context): Boolean {
        val p = prefs(ctx)
        return ALL_NOTIF_KEYS.any { p.contains(it) }
    }

    fun isSoundEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SOUND_ENABLED, true)

    fun isVibrationEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VIBRATION_ENABLED, true)

    /** Per-level mute (sound + vibration). [level] expected 0/1/2; out-of-range
     *  values fall through to false (not muted). */
    fun isLevelMuted(ctx: Context, level: Int): Boolean {
        if (level !in 0..2) return false
        return prefs(ctx).getBoolean(KEY_MUTE_LEVEL_PREFIX + level, false)
    }

    fun isAppInForeground(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FOREGROUND, false)

    fun setForeground(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_FOREGROUND, value).apply()
    }
}
