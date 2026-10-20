package com.shivansh.mibox.data.tmdb

import android.content.Context
import androidx.annotation.WorkerThread
import com.shivansh.mibox.BuildConfig
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
class Tmdb(context: Context) {
    private val dir = File(context.cacheDir, "tmdb").apply { mkdirs() }

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
        val url = url(path, params)
        // The cache name leaves out the API key, so changing keys doesn't throw the cache away.
        val file = File(dir, (key ?: sha1(path + "?" + params.toSortedMap())) + ".json")
        if (file.exists() && (offline || System.currentTimeMillis() - file.lastModified() < maxAgeMs)) {
            read(file)?.let { return it }
        }
        if (offline) return null
        return try {
            val text = call("GET", url, null)
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
    fun send(method: String, path: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject =
        JSONObject(call(method, url(path, params), body?.toString()))

    private fun url(path: String, params: Map<String, String>): String {
        val query = (params + ("api_key" to BuildConfig.TMDB_API_KEY)).entries
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return "$BASE$path?$query"
    }

    private fun call(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("Accept", "application/json")
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
                throw Failure(status, json?.optInt("status_code") ?: 0, json?.optString("status_message") ?: "HTTP $status")
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
    }
}
