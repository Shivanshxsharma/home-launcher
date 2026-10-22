package com.shivansh.mibox.maximal.ui

import android.content.Context
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloaded artwork, kept on disk so each poster and backdrop is fetched once. The folder is
 * trimmed back to [TARGET] (least recently used first) whenever it grows past [LIMIT].
 */
object ImageFiles {
    private lateinit var dir: File

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.cacheDir, "img").apply { mkdirs() }
        Workers.misc.execute { trim() }
    }

    /** The image at [url] as a local file, downloading it first if needed. */
    @WorkerThread
    fun file(url: String): File {
        val file = File(dir, sha1(url))
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        // Each thread writes its own part file, so two loads of the same picture can't collide.
        val part = File(dir, "${file.name}.${Thread.currentThread().id}.part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} for $url")
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(file)) {
            file.delete()
            part.renameTo(file)
        }
        return file
    }

    private fun trim() {
        val files = dir.listFiles()?.toMutableList() ?: return
        files.filter { it.name.endsWith(".part") }.forEach { it.delete() }
        var total = files.sumOf { it.length() }
        if (total <= LIMIT) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= TARGET) break
            total -= f.length()
            f.delete()
        }
    }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val LIMIT = 150L * 1024 * 1024
    private const val TARGET = 100L * 1024 * 1024
}
