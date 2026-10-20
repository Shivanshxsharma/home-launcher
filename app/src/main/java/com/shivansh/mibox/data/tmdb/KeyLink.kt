package com.shivansh.mibox.data.tmdb

import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * A small web page the TV serves on the home network, so a TMDB key is pasted on a phone instead
 * of typed with the remote. Scanning the Connect page's QR code opens it; the key is checked with
 * TMDB on the spot and handed to [onKey] on the main thread. It answers only at a random path,
 * one request at a time, and only while the Connect page is on screen.
 */
class KeyLink(
    /** Whether TMDB accepts a key; throws [IOException] when TMDB can't be reached. */
    private val verify: (String) -> Boolean,
    /** A key arrived from the phone and is being checked. */
    private val onChecking: () -> Unit,
    /** It didn't connect: TMDB refused the key, or ([offline]) couldn't be reached. */
    private val onRejected: (offline: Boolean) -> Unit,
    private val onKey: (String) -> Unit,
) {
    @Volatile
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    /** Starts serving and returns the page's address, or null when the TV has no network address. */
    fun start(): String? {
        stop()
        val ip = lanAddress() ?: return null
        val socket = try {
            ServerSocket(0)
        } catch (_: IOException) {
            return null
        }
        server = socket
        val path = "/" + code()
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    serve(client, path)
                } catch (_: Exception) {
                    // A dropped or malformed request: the next one starts clean.
                } finally {
                    runCatching { client.close() }
                }
            }
        }, "key-link").apply { isDaemon = true }.start()
        return "http://${ip.hostAddress}:${socket.localPort}$path"
    }

    fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
    }

    private fun serve(client: Socket, path: String) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val request = readLine(input)?.split(' ') ?: return
        if (request.size < 2) return
        var length = 0
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            if (header.startsWith("content-length:", ignoreCase = true)) length = header.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (request[1].substringBefore('?') != path) {
            respond(out, 404, Pages.expired())
            return
        }
        when (request[0]) {
            "GET" -> respond(out, 200, Pages.form(null))
            "POST" -> {
                if (length !in 1..MAX_BODY) {
                    respond(out, 200, Pages.form(Pages.EMPTY))
                    return
                }
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val key = form(String(body, 0, read, Charsets.UTF_8))["key"]?.trim()?.trim('"', '\'').orEmpty()
                if (key.isEmpty() || key.length > MAX_KEY || key.any { it.isWhitespace() }) {
                    respond(out, 200, Pages.form(Pages.EMPTY))
                    return
                }
                main.post(onChecking)
                val accepted = try {
                    verify(key)
                } catch (_: IOException) {
                    respond(out, 200, Pages.form(Pages.OFFLINE))
                    main.post { onRejected(true) }
                    return
                }
                if (accepted) {
                    respond(out, 200, Pages.done())
                    main.post { onKey(key) }
                } else {
                    respond(out, 200, Pages.form(Pages.REFUSED))
                    main.post { onRejected(false) }
                }
            }
            else -> respond(out, 405, Pages.expired())
        }
    }

    private fun respond(out: OutputStream, status: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Method Not Allowed"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    /** One header line, without its line break; null at the end of the stream or past [MAX_LINE]. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length >= MAX_LINE) return null
            line.append(c.toChar())
        }
    }

    private fun form(body: String): Map<String, String> = body.split('&').mapNotNull { pair ->
        val at = pair.indexOf('=')
        if (at <= 0) null else URLDecoder.decode(pair.substring(0, at), "UTF-8") to URLDecoder.decode(pair.substring(at + 1), "UTF-8")
    }.toMap()

    /** The TV's address on the home network (Wi-Fi or Ethernet). */
    private fun lanAddress(): Inet4Address? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .sortedBy { if (it.isSiteLocalAddress) 0 else 1 }
            .firstOrNull()
    }.getOrNull()

    /** A short random path, so only someone looking at the TV can open the page. */
    private fun code(): String {
        val random = SecureRandom()
        return (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }

    private companion object {
        const val MAX_LINE = 8 * 1024
        const val MAX_BODY = 4 * 1024
        const val MAX_KEY = 1024
        const val CODE_LENGTH = 10

        /** No look-alike characters, in case someone types the address. */
        const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}

/** The phone pages: dark like the launcher, readable on a small screen, no outside resources. */
private object Pages {
    const val EMPTY = "Paste your TMDB API key into the box first."
    const val REFUSED = "TMDB didn't accept that key. Check you copied all of it (the API Key, or the API Read Access Token), then try again."
    const val OFFLINE = "Your TV couldn't reach TMDB just now. Check its internet connection, then try again."

    fun form(error: String?) = page(
        """
        <p class="tag">Home Launcher</p>
        <h1>Connect your TV to TMDB</h1>
        <p>Shows, films, artwork and trailers come from TMDB. It's free.</p>
        <ol>
          <li>Open <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noopener">themoviedb.org → Settings → API</a>. Sign up first if you're new; a personal key is approved straight away.</li>
          <li>Copy your <b>API Key</b> (or the <b>API Read Access Token</b>).</li>
          <li>Paste it here and connect.</li>
        </ol>
        ${if (error != null) "<p class=\"err\">$error</p>" else ""}
        <form method="post" onsubmit="var b=this.querySelector('button');b.disabled=true;b.textContent='Checking with TMDB…'">
          <input name="key" type="text" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="Paste your TMDB API key" required>
          <button type="submit">Connect TV</button>
        </form>
        <small>The key goes straight to your TV over your Wi-Fi and is kept only on the TV.</small>
        """,
    )

    fun done() = page(
        """
        <p class="tag">Home Launcher</p>
        <h1>Connected ✓</h1>
        <p>Your TV is loading shows and films from TMDB now. You can close this page.</p>
        <p>To bring your watchlist and favorites too, sign in on the TV's Library tab.</p>
        """,
    )

    fun expired() = page(
        """
        <p class="tag">Home Launcher</p>
        <h1>This link has expired</h1>
        <p>Open the Library tab on your TV and scan the new code.</p>
        """,
    )

    private fun page(content: String) = """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Connect your TV</title>
        <style>
        :root{color-scheme:dark}
        body{margin:0;background:#050507;color:#F5F3EE;font:16px/1.55 -apple-system,system-ui,"Segoe UI",Roboto,sans-serif}
        main{max-width:460px;margin:0 auto;padding:44px 22px 56px}
        .tag{font-size:12px;letter-spacing:.16em;text-transform:uppercase;color:#B4B0BB;font-weight:600;margin:0}
        h1{font-size:30px;line-height:1.12;letter-spacing:-.02em;margin:10px 0 14px}
        p,li{color:#D9D5DF}
        ol{padding-left:20px;margin:18px 0 22px}
        li{margin:8px 0}
        a{color:#F5F3EE}
        input{box-sizing:border-box;width:100%;padding:15px 16px;border-radius:14px;border:1px solid rgba(255,255,255,.22);background:#121216;color:#F5F3EE;font:15px ui-monospace,Menlo,Consolas,monospace}
        input:focus{outline:2px solid #F5F3EE;outline-offset:2px}
        button{width:100%;margin-top:12px;padding:15px;border:0;border-radius:999px;background:#F5F3EE;color:#0A0A0C;font:600 16px -apple-system,system-ui,sans-serif}
        button:disabled{opacity:.6}
        .err{background:rgba(255,138,75,.12);border:1px solid rgba(255,138,75,.5);color:#FFC6A8;padding:12px 14px;border-radius:12px}
        small{display:block;margin-top:18px;color:#8F8B97;font-size:13px}
        </style></head>
        <body><main>$content</main></body></html>
    """.trimIndent()
}
