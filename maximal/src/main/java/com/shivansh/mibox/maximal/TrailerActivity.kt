package com.shivansh.mibox.maximal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shivansh.mibox.maximal.ui.Fonts
import com.shivansh.mibox.maximal.ui.Palette
import com.shivansh.mibox.maximal.ui.Ui
import com.shivansh.mibox.maximal.ui.fadeTo
import com.shivansh.mibox.maximal.ui.textPx
import org.json.JSONArray

/**
 * A title's trailer, full-screen, in YouTube's embedded player. Back closes it, straight back to
 * Maximal; OK pauses and plays; left and right skip ten seconds. When YouTube won't play a
 * trailer here (region-locked, or not allowed in embeds), the next one for the title is tried.
 * Its own activity, so the launcher isn't drawn at all while the video plays.
 */
class TrailerActivity : Activity() {
    private var web: WebView? = null
    private lateinit var caption: LinearLayout
    private lateinit var heading: TextView
    private val main = Handler(Looper.getMainLooper())
    private val hideCaption = Runnable { caption.fadeTo(0f, 500) }
    private val giveUp = Runnable { unavailable() }
    private var started = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Fonts.init(this)
        val keys = intent.getStringArrayListExtra(EXTRA_KEYS).orEmpty()
        if (keys.isEmpty()) {
            finish()
            return
        }
        val player = try {
            WebView(this)
        } catch (_: Exception) {
            // No WebView on this box: the YouTube app plays it instead.
            openInYouTube(keys.first())
            return
        }
        player.setBackgroundColor(Color.BLACK)
        player.isFocusable = false
        player.settings.javaScriptEnabled = true
        player.settings.mediaPlaybackRequiresUserGesture = false
        player.addJavascriptInterface(Bridge(), "Android")
        player.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w(TAG, "page: ${message.message()}")
                return true
            }
        }
        player.loadDataWithBaseURL(ORIGIN, page(keys), "text/html", "utf-8", null)
        web = player

        heading = TextView(this).apply {
            text = getString(R.string.trailer_heading, intent.getStringExtra(EXTRA_TITLE).orEmpty())
            typeface = Fonts.title
            includeFontPadding = false
            isAllCaps = true
            textPx(34f)
            setTextColor(Palette.INK)
            setShadowLayer(Ui.px(12f), 0f, 0f, 0xCC000000.toInt())
        }
        val hints = TextView(this).apply {
            setText(R.string.trailer_hint)
            typeface = Fonts.regular
            includeFontPadding = false
            letterSpacing = 0.04f
            textPx(21f)
            setTextColor(Palette.QUIET)
            gravity = Gravity.CENTER_VERTICAL
        }
        caption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.px(112), 0, Ui.px(112), Ui.px(64))
            addView(heading)
            addView(hints, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.px(18) })
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(player, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            },
        )
        // Nothing playing by now means YouTube isn't going to play any of them.
        main.postDelayed(giveUp, START_TIMEOUT_MS)
    }

    /** Keys go to the player, never to the page: the WebView takes no focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (down && event.repeatCount == 0) finish()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (down && event.repeatCount == 0) {
                    run("toggle()")
                    showCaption()
                }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) run("seek(-10)")
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) run("seek(10)")
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) showCaption()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        // A trailer never lingers in the background (Home pressed, an app opened over it).
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        web?.apply {
            loadUrl("about:blank")
            destroy()
        }
        web = null
        super.onDestroy()
    }

    private fun run(script: String) {
        web?.evaluateJavascript(script, null)
    }

    private fun showCaption() {
        caption.fadeTo(1f, 200)
        main.removeCallbacks(hideCaption)
        main.postDelayed(hideCaption, CAPTION_MS)
    }

    /** None of the title's trailers will play here: say so briefly, then back to the launcher. */
    private fun unavailable() {
        if (isFinishing) return
        web?.alpha = 0f
        heading.setText(R.string.trailer_unavailable)
        caption.fadeTo(1f, 200)
        main.removeCallbacks(hideCaption)
        main.postDelayed({ finish() }, 2_600)
    }

    private fun openInYouTube(key: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")))
        } catch (_: ActivityNotFoundException) {
            // Nothing can play it at all.
        }
        finish()
    }

    /** Calls from the page. */
    inner class Bridge {
        @JavascriptInterface
        fun playing() {
            main.post {
                if (started) return@post
                started = true
                main.removeCallbacks(giveUp)
                main.postDelayed(hideCaption, CAPTION_MS)
            }
        }

        @JavascriptInterface
        fun ended() {
            main.post { finish() }
        }

        /** YouTube refused one trailer (e.g. 150: not allowed here); the page moves on to the next. */
        @JavascriptInterface
        fun refused(key: String, code: Int) {
            Log.w(TAG, "trailer $key refused by YouTube, error $code")
        }

        @JavascriptInterface
        fun failed() {
            main.post { unavailable() }
        }
    }

    /**
     * YouTube's IFrame player filling the screen, without its controls (the remote is the
     * control). On an error it moves to the next trailer; past the last one it tells the activity.
     */
    private fun page(keys: List<String>) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style>
        </head><body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        var keys = ${JSONArray(keys)}, at = 0, player = null;
        function onYouTubeIframeAPIReady() {
          player = new YT.Player('p', {
            videoId: keys[0],
            playerVars: { autoplay: 1, controls: 0, rel: 0, iv_load_policy: 3, playsinline: 1, fs: 0, origin: '$ORIGIN' },
            events: {
              onReady: function (e) { e.target.playVideo(); },
              onStateChange: function (e) {
                if (e.data === 1) Android.playing();
                if (e.data === 0) Android.ended();
              },
              onError: function (e) {
                Android.refused(keys[at], e.data);
                at++;
                if (at < keys.length) player.loadVideoById(keys[at]); else Android.failed();
              }
            }
          });
        }
        function toggle() { if (!player) return; if (player.getPlayerState() === 1) player.pauseVideo(); else player.playVideo(); }
        function seek(d) { if (player) player.seekTo(Math.max(0, player.getCurrentTime() + d), true); }
        </script></body></html>
    """.trimIndent()

    companion object {
        private const val TAG = "Trailer"
        private const val EXTRA_KEYS = "keys"
        private const val EXTRA_TITLE = "title"

        /** The page's origin: YouTube's embed wants one that names the app it plays in (else error 152). */
        private const val ORIGIN = "https://com.shivansh.mibox.maximal"
        private const val CAPTION_MS = 3_500L
        private const val START_TIMEOUT_MS = 25_000L

        fun intent(context: Context, title: String, keys: List<String>): Intent =
            Intent(context, TrailerActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putStringArrayListExtra(EXTRA_KEYS, ArrayList(keys))
    }
}
