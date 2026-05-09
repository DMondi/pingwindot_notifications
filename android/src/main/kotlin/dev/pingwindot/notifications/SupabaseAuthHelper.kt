package dev.pingwindot.notifications

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * Reads the current Supabase session from SharedPreferences (mirrored from
 * the Dart side via the host app's `mirrorSupabaseSession` custom action),
 * and refreshes the access token natively when needed.
 *
 * Configuration is read from `applicationInfo.metaData`. The host app must
 * declare these `<meta-data>` entries inside its `<application>` block —
 * typically via FlutterFlow's AndroidManifest snippets, populated from
 * Environment Values:
 *
 *   <meta-data android:name="dev.pingwindot.notifications.SUPABASE_URL"
 *              android:value="{{supabaseUrl}}" />
 *   <meta-data android:name="dev.pingwindot.notifications.SUPABASE_ANON_KEY"
 *              android:value="{{supabaseAnonKey}}" />
 */
object SupabaseAuthHelper {

    /** Key that the Dart side writes the mirrored session JSON under.
     *  shared_preferences plugin prefixes Android keys with `flutter.`. */
    private const val SESSION_PREFS_KEY = "flutter.pingwin.session"
    private const val FLUTTER_PREFS_FILE = "FlutterSharedPreferences"

    private const val META_SUPABASE_URL = "dev.pingwindot.notifications.SUPABASE_URL"
    private const val META_SUPABASE_ANON_KEY = "dev.pingwindot.notifications.SUPABASE_ANON_KEY"

    private const val REFRESH_LOCK_FILE = "supabase_refresh.lock"

    data class Config(val supabaseUrl: String, val anonKey: String)

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
    )

    fun readConfig(context: Context): Config? {
        return try {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_META_DATA,
            )
            val md = ai.metaData ?: return null
            val url = md.getString(META_SUPABASE_URL) ?: return null
            val key = md.getString(META_SUPABASE_ANON_KEY) ?: return null
            Config(url.trimEnd('/'), key)
        } catch (e: Exception) {
            null
        }
    }

    fun readSession(context: Context): Session? {
        val raw = prefs(context).getString(SESSION_PREFS_KEY, null) ?: return null
        return parseSession(raw)
    }

    private fun parseSession(raw: String): Session? {
        return try {
            val obj = JSONObject(raw)
            val access = obj.optString("access_token", "").ifEmpty { return null }
            val refresh = obj.optString("refresh_token", "").ifEmpty { return null }
            val expiresAt = obj.optLong("expires_at", 0L)
            Session(access, refresh, expiresAt)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Refresh the session natively via Supabase REST. On success, writes the
     * new pair back to the same SharedPreferences key the Dart side reads —
     * the supabase_flutter SDK picks up the rotated tokens on its next
     * persistence read.
     *
     * Serialised through a process-wide [FileLock]: native receiver and
     * Dart SDK live in the same process, so the lock prevents both sides
     * from burning the same refresh_token under rotation.
     */
    fun refresh(context: Context, config: Config, refreshToken: String): Session? {
        val lock = acquireLock(context) ?: return null
        try {
            val current = readSession(context)
            if (current != null && current.refreshToken != refreshToken) {
                // Another actor refreshed while we were blocked — use that.
                return current
            }

            val url = URL("${config.supabaseUrl}/auth/v1/token?grant_type=refresh_token")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", config.anonKey)
                setRequestProperty("Authorization", "Bearer ${config.anonKey}")
            }

            val body = JSONObject().apply { put("refresh_token", refreshToken) }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.errorStream?.close()
                return null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(response)
            val newAccess = obj.optString("access_token", "").ifEmpty { return null }
            val newRefresh = obj.optString("refresh_token", refreshToken)
            val expiresAt = obj.optLong("expires_at", 0L)

            val merged = JSONObject().apply {
                put("access_token", newAccess)
                put("refresh_token", newRefresh)
                put("expires_at", expiresAt)
            }.toString()
            prefs(context).edit().putString(SESSION_PREFS_KEY, merged).apply()

            return Session(newAccess, newRefresh, expiresAt)
        } catch (e: Exception) {
            return null
        } finally {
            releaseLock(lock)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FLUTTER_PREFS_FILE, Context.MODE_PRIVATE)

    private data class Lock(val file: RandomAccessFile, val channel: FileChannel, val lock: FileLock)

    private fun acquireLock(context: Context): Lock? {
        return try {
            val lockFile = java.io.File(context.filesDir, REFRESH_LOCK_FILE)
            val raf = RandomAccessFile(lockFile, "rw")
            val channel = raf.channel
            val l = channel.lock()
            Lock(raf, channel, l)
        } catch (e: Exception) {
            null
        }
    }

    private fun releaseLock(lock: Lock) {
        try { lock.lock.release() } catch (_: Exception) {}
        try { lock.channel.close() } catch (_: Exception) {}
        try { lock.file.close() } catch (_: Exception) {}
    }
}
