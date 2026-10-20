package com.shivansh.mibox.data.tmdb

import android.content.Context
import androidx.annotation.WorkerThread
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * A small TMDB v3 client. Every GET is kept on disk: fresh copies are used as they are, and
 * [offline] reads take whatever is cached, so the home screen can draw instantly at start and
 * keep working without a connection.
 */
class Tmdb(context: Context, private val key: ApiKey) {
    private val dir = File(context.cacheDir, "tmdb").apply { mkdirs() }

    /** TMDB turned the key down (revoked, or mistyped when connected). Called on a worker thread. */
    @Volatile
    var onKeyRefused: (() -> Unit)? = null

    /** A failed call: the HTTP status and TMDB's own status code (e.g. 17 = request token not approved yet). */
    class Failure(val http: Int, val code: Int, message: String) : IOException(message)

    /** [key] names the cached copy instead of the URL, for queries whose parameters drift (dates). */
    @WorkerThread
    fun get(
        path: String,
        params: Map<String, String> = emptyMap(),
        maxAgeMs: Long = HOUR,
        offline: Boolean = false,
        key: String? = null,
    ): JSONObject? {
        // The cache name leaves out the API key, so changing keys doesn't throw the cache away.
        val file = File(dir, (key ?: sha1(path + "?" + params.toSortedMap())) + ".json")
        if (file.exists() && (offline || System.currentTimeMillis() - file.lastModified() < maxAgeMs)) {
            read(file)?.let { return it }
        }
        // No key yet (a fresh install): whatever is cached is all there is.
        val apiKey = this.key.value ?: return if (file.exists()) read(file) else null
        if (offline) return null
        return try {
            val text = call("GET", url(path, params, apiKey), null, apiKey)
            file.writeText(text)
            JSONObject(text)
        } catch (e: IOException) {
            // No network or a TMDB hiccup: a stale copy beats nothing.
            if (file.exists()) read(file) else null
        }
    }

    /** Drops cached copies whose name starts with [prefix], e.g. the lists of an account that signed out. */
    fun forget(prefix: String) {
        dir.listFiles()?.forEach { if (it.name.startsWith(prefix)) it.delete() }
    }

    /** An uncached call that changes something (sessions, lists). Throws [Failure] on an error status. */
    @WorkerThread
    fun send(method: String, path: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject {
        val apiKey = key.value ?: throw Failure(401, INVALID_KEY, "No TMDB key connected")
        return JSONObject(call(method, url(path, params, apiKey), body?.toString(), apiKey))
    }

    /**
     * Whether TMDB accepts [candidate] (a key pasted on the Connect page). Throws [IOException]
     * when TMDB can't be reached, so a network problem isn't mistaken for a bad key.
     */
    @WorkerThread
    fun verify(candidate: String): Boolean = try {
        JSONObject(call("GET", url("/authentication", emptyMap(), candidate), null, candidate)).optBoolean("success", true)
    } catch (e: Failure) {
        if (e.http == 401) false else throw e
    }

    /** A v3 key goes in the query; a v4 token goes in the Authorization header instead. */
    private fun url(path: String, params: Map<String, String>, apiKey: String): String {
        val all = if (ApiKey.isToken(apiKey)) params else params + ("api_key" to apiKey)
        val query = all.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return "$BASE$path?$query"
    }

    private fun call(method: String, url: String, body: String?, apiKey: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("Accept", "application/json")
            if (ApiKey.isToken(apiKey)) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json;charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                val code = json?.optInt("status_code") ?: 0
                // The key in use was turned down: let the app ask for a new one.
                if (status == 401 && code == INVALID_KEY && apiKey == key.value) {
                    key.refuse(apiKey)
                    onKeyRefused?.invoke()
                }
                throw Failure(status, code, json?.optString("status_message") ?: "HTTP $status")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun read(file: File): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val BASE = "https://api.themoviedb.org/3"
        const val IMAGES = "https://image.tmdb.org/t/p/"
        const val HOUR = 60 * 60 * 1000L

        /** TMDB's status code for an invalid API key. */
        const val INVALID_KEY = 7
    }
}
