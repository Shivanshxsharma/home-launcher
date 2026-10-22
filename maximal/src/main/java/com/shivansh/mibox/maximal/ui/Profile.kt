package com.shivansh.mibox.maximal.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shivansh.mibox.data.tmdb.Account
import com.shivansh.mibox.data.tmdb.ApiKey
import com.shivansh.mibox.data.tmdb.Auth
import com.shivansh.mibox.data.tmdb.KeyLink
import com.shivansh.mibox.data.tmdb.Tmdb
import com.shivansh.mibox.maximal.R

/**
 * Profile, over the art. Three states, each a step of TMDB set-up:
 * - **Connect**: no key yet (or replacing one). A QR code opens a page the TV serves on the home
 *   network, where the key is pasted on a phone (the Home launcher's [KeyLink], shared).
 * - **Sign in**: a QR code for TMDB's approval page; it polls until the phone approves.
 * - **Account**: signed in, with Sign out and Use a different key.
 * The QR work runs only while the panel is open.
 */
class Profile(
    private val panel: View,
    private val tmdb: Tmdb,
    private val apiKey: ApiKey,
    private val account: Account,
    private val listener: Listener,
) {
    interface Listener {
        fun onKeyConnected(key: String)
        fun onSignedIn(name: String)
        fun onSignedOut()
    }

    private enum class Mode { CONNECT, SIGN_IN, ACCOUNT }

    private val title: TextView = panel.findViewById(R.id.profile_title)
    private val line: TextView = panel.findViewById(R.id.profile_line)
    private val statusRow: View = panel.findViewById(R.id.profile_status_row)
    private val status: TextView = panel.findViewById(R.id.profile_status)
    private val dot: View = panel.findViewById(R.id.profile_dot)
    private val qrColumn: View = panel.findViewById(R.id.profile_qr_column)
    private val qr: ImageView = panel.findViewById(R.id.profile_qr)
    private val url: TextView = panel.findViewById(R.id.profile_url)
    private val pills = Pills(panel.findViewById<LinearLayout>(R.id.profile_actions))
    private val main = Handler(Looper.getMainLooper())
    private val auth = Auth(tmdb, account)
    private val link = KeyLink(
        tmdb::verify,
        onChecking = { if (mode == Mode.CONNECT && isOpen) say(R.string.connect_checking, Palette.INK, pulsing = true) },
        onRejected = { offline ->
            if (mode == Mode.CONNECT && isOpen) say(if (offline) R.string.connect_offline else R.string.connect_refused, ORANGE, pulsing = true)
        },
    ) { key ->
        if (mode != Mode.CONNECT || !isOpen) return@KeyLink
        stopWork()
        say(R.string.connect_done, GREEN, pulsing = false)
        listener.onKeyConnected(key)
        // Straight on to the optional sign-in, now that there's a key.
        main.postDelayed({ if (isOpen) start(Mode.SIGN_IN) }, 1_400)
    }

    private var mode = Mode.CONNECT
    private var generation = 0
    private var pulse: ObjectAnimator? = null
    private var token: String? = null
    private var tokenAt = 0L

    var isOpen = false
        private set

    init {
        panel.background = Frost.card(Ui.px(44f))
        panel.findViewById<View>(R.id.profile_qr_card).background = GradientDrawable().apply {
            cornerRadius = Ui.px(20f)
            setColor(0xFFFFFFFF.toInt())
        }
    }

    fun open() {
        isOpen = true
        panel.scaleX = 0.96f
        panel.scaleY = 0.96f
        panel.fadeTo(1f, 220)
        panel.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(Motion.SETTLE)
        start(
            when {
                !apiKey.present -> Mode.CONNECT
                !account.signedIn -> Mode.SIGN_IN
                else -> Mode.ACCOUNT
            },
        )
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        stopWork()
        panel.fadeTo(0f, 160)
    }

    /** Keys while open: up and down choose an action, OK runs it. Back is the activity's. */
    fun key(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> pills.move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> pills.move(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) pills.press()
        }
    }

    private fun start(next: Mode) {
        stopWork()
        mode = next
        val gen = ++generation
        val replacing = next == Mode.CONNECT && apiKey.present
        when (next) {
            Mode.CONNECT -> {
                title.setText(R.string.connect_title)
                line.setText(if (replacing) R.string.connect_line_replace else R.string.connect_line)
                showQr(true)
                pills.set(emptyList())
                say(R.string.connect_starting, Palette.alpha(0xFFFFFF, 0.4f), pulsing = false)
                Workers.misc.execute {
                    val address = link.start()
                    val code = address?.let { runCatching { Qr.code(it) }.getOrNull() }
                    main.post {
                        if (gen != generation) {
                            Workers.misc.execute { link.stop() }
                            return@post
                        }
                        if (address == null || code == null) {
                            say(R.string.connect_no_network, ORANGE, pulsing = false)
                            main.postDelayed({ if (gen == generation && isOpen) start(Mode.CONNECT) }, RETRY_MS)
                            return@post
                        }
                        setQr(code)
                        url.text = url.resources.getString(R.string.connect_or_open, address.removePrefix("http://"))
                        say(R.string.connect_waiting, Palette.INK, pulsing = true)
                    }
                }
            }
            Mode.SIGN_IN -> {
                title.setText(R.string.signin_title)
                line.setText(R.string.signin_line)
                showQr(true)
                url.text = ""
                pills.set(listOf(Pills.Pill(R.drawable.ic_plus, panel.resources.getString(R.string.action_change_key)) { start(Mode.CONNECT) }))
                pills.focus(-1)
                val fresh = token != null && SystemClock.elapsedRealtime() - tokenAt < TOKEN_LIFE_MS
                if (fresh) {
                    say(R.string.signin_waiting, Palette.INK, pulsing = true)
                    schedulePoll(gen)
                } else {
                    fetchToken(gen)
                }
            }
            Mode.ACCOUNT -> {
                title.setText(R.string.signed_in_title)
                line.text = panel.resources.getString(R.string.signed_in_as, account.username) + " " +
                    panel.resources.getString(R.string.signed_in_line)
                showQr(false)
                statusRow.visibility = View.GONE
                pills.set(
                    listOf(
                        Pills.Pill(R.drawable.ic_check, panel.resources.getString(R.string.action_sign_out)) {
                            account.clear()
                            tmdb.forget("account-")
                            listener.onSignedOut()
                            start(Mode.SIGN_IN)
                        },
                        Pills.Pill(R.drawable.ic_plus, panel.resources.getString(R.string.action_change_key)) { start(Mode.CONNECT) },
                    ),
                )
                pills.focus(0)
            }
        }
    }

    private fun fetchToken(gen: Int) {
        say(R.string.signin_getting, Palette.alpha(0xFFFFFF, 0.4f), pulsing = false)
        Workers.misc.execute {
            val result = runCatching {
                val t = auth.newToken()
                t to Qr.code(auth.approvalUrl(t))
            }
            main.post {
                if (gen != generation) return@post
                result.onSuccess { (t, code) ->
                    token = t
                    tokenAt = SystemClock.elapsedRealtime()
                    setQr(code)
                    say(R.string.signin_waiting, Palette.INK, pulsing = true)
                    schedulePoll(gen)
                }.onFailure {
                    say(R.string.signin_offline, ORANGE, pulsing = false)
                    main.postDelayed({ if (gen == generation) fetchToken(gen) }, RETRY_MS)
                }
            }
        }
    }

    private fun schedulePoll(gen: Int) = main.postDelayed({ poll(gen) }, POLL_MS)

    private fun poll(gen: Int) {
        if (gen != generation) return
        val t = token ?: return
        if (SystemClock.elapsedRealtime() - tokenAt > TOKEN_LIFE_MS) {
            token = null
            fetchToken(gen)
            return
        }
        Workers.misc.execute {
            val result = runCatching { auth.trySession(t)?.let { auth.finish(it) } }
            main.post {
                if (gen != generation) return@post
                result.onSuccess { name ->
                    if (name == null) {
                        schedulePoll(gen)
                    } else {
                        token = null
                        listener.onSignedIn(name)
                        start(Mode.ACCOUNT)
                    }
                }.onFailure { e ->
                    if (e is Tmdb.Failure && e.http == 401) {
                        token = null
                        fetchToken(gen)
                    } else {
                        say(R.string.signin_offline, ORANGE, pulsing = false)
                        schedulePoll(gen)
                    }
                }
            }
        }
    }

    private fun stopWork() {
        generation++
        main.removeCallbacksAndMessages(null)
        pulse?.cancel()
        Workers.misc.execute { link.stop() }
    }

    private fun showQr(on: Boolean) {
        qrColumn.visibility = if (on) View.VISIBLE else View.GONE
        statusRow.visibility = if (on) View.VISIBLE else View.GONE
        if (on) qr.setImageDrawable(null)
    }

    private fun setQr(code: android.graphics.Bitmap) {
        qr.setImageBitmap(code)
        qr.alpha = 0f
        qr.animate().alpha(1f).setDuration(400).setStartDelay(0).setInterpolator(Motion.EASE)
    }

    private fun say(text: Int, color: Int, pulsing: Boolean) {
        status.setText(text)
        dot.backgroundTintList = ColorStateList.valueOf(color)
        pulse?.cancel()
        dot.alpha = 1f
        if (!pulsing) return
        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private companion object {
        const val POLL_MS = 3_000L
        const val RETRY_MS = 5_000L

        /** TMDB request tokens last an hour; take a new one a little before that. */
        const val TOKEN_LIFE_MS = 50 * 60 * 1000L
        const val GREEN = 0xFF3DF5A8.toInt()
        const val ORANGE = 0xFFFF8A4B.toInt()
    }
}
