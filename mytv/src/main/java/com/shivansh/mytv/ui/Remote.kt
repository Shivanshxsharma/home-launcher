package com.shivansh.mytv.ui

import android.content.res.AssetManager
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

/**
 * A small web page for the phone: every wallpaper as a thumbnail and every clock face, one tap to
 * set. It runs only while MY TV is on screen, sits blocked on one socket doing nothing until asked,
 * and answers only under a private code, so nobody else on the network can change the TV.
 */
class Remote(
    private val assets: AssetManager,
    private val code: String,
    private val faces: List<String>,
    /** The wallpaper and clock face now in use. */
    private val current: () -> Pair<Int, Int>,
    private val onWall: (Int) -> Unit,
    private val onFace: (Int) -> Unit,
) {
    @Volatile
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())

    /** Starts serving and returns the page's address, or null when the TV has no network address. */
    fun start(): String? {
        stop()
        val ip = lanAddress() ?: return null
        val socket = try {
            ServerSocket(PORT)
        } catch (_: IOException) {
            try {
                ServerSocket(0)
            } catch (_: IOException) {
                return null
            }
        }
        server = socket
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    serve(client)
                } catch (_: Exception) {
                    // A dropped or malformed request: the next one starts clean.
                } finally {
                    runCatching { client.close() }
                }
            }
        }, "phone").apply { isDaemon = true }.start()
        return "http://${ip.hostAddress}:${socket.localPort}/$code"
    }

    fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
    }

    private fun serve(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val request = readLine(input)?.split(' ') ?: return
        if (request.size < 2) return
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
        }
        val path = request[1].substringBefore('?')
        if (path != "/$code" && !path.startsWith("/$code/")) {
            respond(out, 404, "text/plain", ByteArray(0), cache = false)
            return
        }
        val rest = path.removePrefix("/$code").trimEnd('/')
        val parts = rest.split('/').filter { it.isNotEmpty() }
        when {
            request[0] == "GET" && parts.isEmpty() -> respond(out, 200, "text/html; charset=utf-8", page().toByteArray(Charsets.UTF_8), cache = false)
            request[0] == "GET" && parts.size == 2 && parts[0] == "t" -> {
                val index = parts[1].substringBefore('.').toIntOrNull()
                val name = index?.let { Scenery.NAMES.getOrNull(it) }
                if (name == null) {
                    respond(out, 404, "text/plain", ByteArray(0), cache = false)
                } else {
                    respond(out, 200, "image/jpeg", assets.open(Scenery.thumb(index)).use { it.readBytes() }, cache = true)
                }
            }
            request[0] == "POST" && parts.size == 2 && parts[0] == "wall" -> {
                parts[1].toIntOrNull()?.let { main.post { onWall(it) } }
                respond(out, 204, "text/plain", ByteArray(0), cache = false)
            }
            request[0] == "POST" && parts.size == 2 && parts[0] == "face" -> {
                parts[1].toIntOrNull()?.let { main.post { onFace(it) } }
                respond(out, 204, "text/plain", ByteArray(0), cache = false)
            }
            else -> respond(out, 404, "text/plain", ByteArray(0), cache = false)
        }
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray, cache: Boolean) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            else -> "Not Found"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: ${if (cache) "max-age=86400" else "no-store"}\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_LINE) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
        return null
    }

    private fun lanAddress() = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }

    private fun page(): String {
        val (wall, face) = current()
        val names = faces.joinToString(",") { "\"$it\"" }
        return PAGE.replace("/*DATA*/", "var N=${Scenery.NAMES.size},F=[$names],W=$wall,C=$face;")
    }

    private companion object {
        const val PORT = 8520
        const val MAX_LINE = 2048

        val PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0b0d12"><title>MY TV</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#0b0d12;color:#fff;font:500 16px/1.35 -apple-system,system-ui,Roboto,sans-serif;padding:24px 16px 48px;max-width:720px;margin:auto}
h1{font-size:30px;font-weight:650;letter-spacing:-.02em;margin:0 4px}
p{margin:2px 4px 0;color:#9aa0ac;font-size:14px}
h2{font-size:15px;font-weight:600;color:#c9cdd6;margin:26px 4px 12px}
.f{display:flex;flex-wrap:wrap;gap:8px}
.f button{appearance:none;border:0;font:inherit;color:#fff;padding:10px 18px;border-radius:999px;background:#ffffff1c;transition:background .2s,color .2s}
.f button.on{background:#fff;color:#101217}
.w{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}
.w button{appearance:none;border:0;padding:0;display:block;width:100%;aspect-ratio:16/9;border-radius:16px;background:#1b1e26 center/cover;box-shadow:0 8px 20px #0007;transition:transform .35s cubic-bezier(.2,.9,.2,1.25)}
.w button:active{transform:scale(.95)}
.w button.on{outline:3px solid #fff;outline-offset:2px}
</style></head><body>
<h1>MY TV</h1><p>Tap to change it on the TV.</p>
<h2>Clock</h2><div class="f" id="f"></div>
<h2>Wallpaper</h2><div class="w" id="w"></div>
<script>
/*DATA*/
var base=location.pathname.replace(/\/${'$'}/,'');
function send(p){fetch(base+p,{method:'POST'}).catch(function(){})}
function only(box,b){[].forEach.call(box.children,function(x){x.className=''});b.className='on'}
var w=document.getElementById('w'),f=document.getElementById('f');
for(var i=0;i<N;i++)(function(i){var b=document.createElement('button');
b.style.backgroundImage='url('+base+'/t/'+i+'.jpg)';b.setAttribute('aria-label','Wallpaper '+(i+1));
if(i==W)b.className='on';b.onclick=function(){only(w,b);send('/wall/'+i)};w.appendChild(b)})(i);
F.forEach(function(n,i){var b=document.createElement('button');b.textContent=n;
if(i==C)b.className='on';b.onclick=function(){only(f,b);send('/face/'+i)};f.appendChild(b)});
</script></body></html>"""
    }
}
