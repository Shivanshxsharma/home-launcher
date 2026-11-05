package com.shivansh.mytv.ui

import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * A small web page for the phone: every wallpaper as a thumbnail and every clock face, one tap to
 * set, plus adding and deleting wallpapers. It runs only while MY TV is on screen, sits blocked on
 * one socket doing nothing until asked, and answers only under a private code, so nobody else on
 * the network can change the TV. The phone shrinks a picture before sending it, so a large photo
 * arrives at TV size.
 */
class Remote(
    private val code: String,
    private val faces: List<String>,
    /** Somewhere to hold a picture while it arrives. */
    private val upload: File,
    /** The wallpaper and clock face now in use. */
    private val current: () -> Pair<String?, Int>,
    private val onWall: (String) -> Unit,
    private val onFace: (Int) -> Unit,
    private val onAdded: (Wall) -> Unit,
    /** Wallpapers were deleted or restored. */
    private val onChanged: () -> Unit,
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
        client.soTimeout = 20_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val request = readLine(input)?.split(' ') ?: return
        if (request.size < 2) return
        var length = 0L
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            if (header.startsWith("content-length:", ignoreCase = true)) {
                length = header.substringAfter(':').trim().toLongOrNull() ?: 0L
            }
        }
        val path = request[1].substringBefore('?')
        if (path != "/$code" && !path.startsWith("/$code/")) {
            empty(out, 404)
            return
        }
        val parts = path.removePrefix("/$code").split('/').filter { it.isNotEmpty() }
        val post = request[0] == "POST"
        when {
            request[0] == "GET" && parts.isEmpty() -> respond(out, 200, "text/html; charset=utf-8", page().toByteArray(Charsets.UTF_8), cache = false)
            request[0] == "GET" && parts.size == 2 && parts[0] == "t" -> {
                val wall = Walls.find(parts[1].removeSuffix(".jpg"))
                if (wall == null) empty(out, 404) else respond(out, 200, "image/jpeg", Walls.thumbBytes(wall), cache = true)
            }
            post && parts.size == 2 && parts[0] == "wall" -> {
                main.post { onWall(parts[1]) }
                empty(out, 204)
            }
            post && parts.size == 2 && parts[0] == "face" -> {
                parts[1].toIntOrNull()?.let { main.post { onFace(it) } }
                empty(out, 204)
            }
            post && parts.size == 1 && parts[0] == "add" -> add(input, out, length)
            post && parts.size == 2 && parts[0] == "delete" -> {
                Walls.delete(parts[1])
                main.post { onChanged() }
                empty(out, 204)
            }
            post && parts.size == 1 && parts[0] == "restore" -> {
                Walls.restore()
                main.post { onChanged() }
                empty(out, 204)
            }
            else -> empty(out, 404)
        }
    }

    private fun add(input: InputStream, out: OutputStream, length: Long) {
        if (length !in 1..MAX_UPLOAD) {
            empty(out, 413)
            return
        }
        try {
            upload.outputStream().use { file ->
                val buffer = ByteArray(16 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) break
                    file.write(buffer, 0, n)
                    left -= n
                }
                if (left > 0) {
                    empty(out, 400)
                    return
                }
            }
            val wall = Walls.add(upload)
            if (wall == null) {
                empty(out, 415)
            } else {
                main.post { onAdded(wall) }
                empty(out, 204)
            }
        } finally {
            upload.delete()
        }
    }

    private fun empty(out: OutputStream, status: Int) = respond(out, status, "text/plain", ByteArray(0), cache = false)

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray, cache: Boolean) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            413 -> "Too Large"
            415 -> "Not A Picture"
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
        val ids = Walls.all.joinToString(",") { "\"${it.id}\"" }
        val names = faces.joinToString(",") { "\"$it\"" }
        return PAGE.replace("/*DATA*/", "var W=[$ids],F=[$names],S=\"${wall.orEmpty()}\",C=$face,H=${Walls.hidden};")
    }

    private companion object {
        const val PORT = 8520
        const val MAX_LINE = 2048
        const val MAX_UPLOAD = 16L * 1024 * 1024

        val PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0b0d12"><title>MY TV</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{background:#0b0d12;color:#fff;font:500 16px/1.35 -apple-system,system-ui,Roboto,sans-serif;padding:24px 16px 48px;max-width:720px;margin:auto}
h1{font-size:30px;font-weight:650;letter-spacing:-.02em;margin:0 4px}
p{margin:2px 4px 0;color:#9aa0ac;font-size:14px}
h2{font-size:15px;font-weight:600;color:#c9cdd6;margin:0}
.h{display:flex;align-items:center;gap:8px;margin:26px 4px 12px}
.h h2{flex:1}
.f{display:flex;flex-wrap:wrap;gap:8px}
button,label.b{appearance:none;border:0;font:inherit;color:#fff;background:#ffffff1c;border-radius:999px;cursor:pointer}
.f button{padding:10px 18px;transition:background .2s,color .2s}
.f button.on{background:#fff;color:#101217}
.s,label.b{padding:7px 14px;font-size:14px}
label.b input{display:none}
#del.on{background:#ff4d4f;color:#fff}
#msg{margin:0 4px 10px;min-height:1.3em}
.w{display:grid;grid-template-columns:repeat(2,1fr);gap:10px}
.w button{position:relative;padding:0;display:block;width:100%;aspect-ratio:16/9;border-radius:16px;background:#1b1e26 center/cover;box-shadow:0 8px 20px #0007;transition:transform .35s cubic-bezier(.2,.9,.2,1.25)}
.w button:active{transform:scale(.95)}
.w button.on{outline:3px solid #fff;outline-offset:2px}
.w.del button::after{content:"\00d7";position:absolute;top:8px;right:8px;width:30px;height:30px;border-radius:50%;background:#ff4d4f;color:#fff;font-size:22px;line-height:29px;text-align:center}
#res{display:none;margin:18px auto 0;padding:10px 18px;font-size:14px}
</style></head><body>
<h1>MY TV</h1><p>Tap to change it on the TV.</p>
<div class="h"><h2>Clock</h2></div><div class="f" id="f"></div>
<div class="h"><h2>Wallpaper</h2>
<label class="b">Add<input id="add" type="file" accept="image/*" multiple></label>
<button class="s" id="del">Delete</button></div>
<p id="msg"></p><div class="w" id="w"></div>
<button id="res"></button>
<script>
/*DATA*/
var base=location.pathname.replace(/\/+${'$'}/,'');
var w=document.getElementById('w'),f=document.getElementById('f'),msg=document.getElementById('msg'),del=document.getElementById('del'),res=document.getElementById('res');
function send(p){return fetch(base+p,{method:'POST'})}
function only(box,b){[].forEach.call(box.children,function(x){x.className=''});b.className='on'}
function tile(id){var b=document.createElement('button');
b.style.backgroundImage='url('+base+'/t/'+id+'.jpg)';b.setAttribute('aria-label','Wallpaper');
if(id==S)b.className='on';
b.onclick=function(){
if(!w.classList.contains('del')){only(w,b);send('/wall/'+id);return}
if(!confirm('Delete this wallpaper?'))return;
send('/delete/'+id).then(function(){b.remove();if(b.className=='on'&&w.firstChild)w.firstChild.className='on';H=id.indexOf('my-')==0?H:H+1;paintRes()},function(){msg.textContent='The TV did not answer.'})};
w.appendChild(b)}
W.forEach(tile);
F.forEach(function(n,i){var b=document.createElement('button');b.textContent=n;
if(i==C)b.className='on';b.onclick=function(){only(f,b);send('/face/'+i)};f.appendChild(b)});
del.onclick=function(){var on=w.classList.toggle('del');del.className=on?'s on':'s';del.textContent=on?'Done':'Delete';msg.textContent=on?'Tap a wallpaper to delete it.':''};
function paintRes(){res.style.display=H>0?'block':'none';res.textContent='Bring back '+H+' deleted';}
paintRes();
res.onclick=function(){send('/restore').then(function(){location.reload()})};
function fit(file){return new Promise(function(ok,no){var u=URL.createObjectURL(file),i=new Image();
i.onerror=function(){URL.revokeObjectURL(u);no()};
i.onload=function(){var cw=1920,ch=1080,c=document.createElement('canvas');c.width=cw;c.height=ch;
var s=Math.max(cw/i.naturalWidth,ch/i.naturalHeight),sw=cw/s,sh=ch/s,g=c.getContext('2d');
g.imageSmoothingQuality='high';g.drawImage(i,(i.naturalWidth-sw)/2,(i.naturalHeight-sh)/2,sw,sh,0,0,cw,ch);
URL.revokeObjectURL(u);c.toBlob(function(b){b?ok(b):no()},'image/jpeg',.9)};i.src=u})}
document.getElementById('add').onchange=function(){var list=[].slice.call(this.files),n=0,bad=0;
function next(){if(n>=list.length){if(bad)msg.textContent=bad+' could not be added.';setTimeout(function(){location.reload()},bad?1500:150);return}
msg.textContent='Sending '+(n+1)+' of '+list.length+'...';
fit(list[n++]).then(function(b){return fetch(base+'/add',{method:'POST',body:b})}).then(function(r){if(!r.ok)bad++},function(){bad++}).then(next)}
if(list.length)next()};
</script></body></html>"""
    }
}
