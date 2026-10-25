package com.shivansh.mibox.minimal.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shivansh.mibox.data.tmdb.Account
import com.shivansh.mibox.data.tmdb.ApiKey
import com.shivansh.mibox.data.tmdb.Auth
import com.shivansh.mibox.data.tmdb.KeyLink
import com.shivansh.mibox.data.tmdb.Tmdb
import com.shivansh.mibox.minimal.R

/**
 * Favs before there is anything to show: TMDB set-up from a phone, a QR code and the words.
 * - **Connect**: no key yet. The QR code opens a page this TV serves on the home network, where
 *   the key is pasted (the Home launcher's [KeyLink], shared).
 * - **Sign in**: a QR code for TMDB's approval page; it polls until the phone approves.
 * The work runs only while [start]ed, i.e. while Favs is on screen.
 */
class Link(
    private val qr: QrCard,
    private val info: Info,
    private val tmdb: Tmdb,
    private val apiKey: ApiKey,
    private val account: Account,
    private val listener: Listener,
) {
    interface Listener {
        fun onKeyConnected(key: String)
        fun onSignedIn(name: String)
    }

    private enum class Mode { CONNECT, SIGN_IN }

    private val res = info.resources
    private val main = Handler(Looper.getMainLooper())
    private val auth = Auth(tmdb, account)
    private val link = KeyLink(
        tmdb::verify,
        onChecking = { if (mode == Mode.CONNECT && active) say(R.string.connect_checking, Palette.INK) },
        onRejected = { offline -> if (mode == Mode.CONNECT && active) say(if (offline) R.string.connect_offline else R.string.connect_refused, Palette.ORANGE) },
    ) { key ->
        if (mode != Mode.CONNECT || !active) return@KeyLink
        stopWork()
        say(R.string.connect_done, Palette.INK)
        listener.onKeyConnected(key)
        // Straight on to sign-in, now that there's a key.
        main.postDelayed({ if (active) begin(Mode.SIGN_IN) }, 1_400)
    }

    private var mode = Mode.CONNECT
    private var generation = 0
    private var token: String? = null
    private var tokenAt = 0L

    var active = false
        private set

    /** True when Favs has nothing to show until TMDB is set up. */
    val needed: Boolean get() = !apiKey.present || !account.signedIn

    fun start() {
        if (active) return
        active = true
        begin(if (!apiKey.present) Mode.CONNECT else Mode.SIGN_IN)
    }

    fun stop() {
        if (!active) return
        active = false
        qr.show(null, null)
        stopWork()
    }

    private fun begin(next: Mode) {
        stopWork()
        mode = next
        val gen = ++generation
        qr.show(null, null)
        when (next) {
            Mode.CONNECT -> {
                info.show(res.getString(R.string.connect_kicker), res.getString(R.string.connect_title), "", res.getString(R.string.connect_line), null)
                say(R.string.connect_starting, Palette.DIM)
                Workers.misc.execute {
                    val address = link.start()
                    val code = address?.let { runCatching { Qr.code(it) }.getOrNull() }
                    main.post {
                        if (gen != generation) {
                            Workers.misc.execute { link.stop() }
                            return@post
                        }
                        if (address == null || code == null) {
                            say(R.string.connect_no_network, Palette.ORANGE)
                            main.postDelayed({ if (gen == generation && active) begin(Mode.CONNECT) }, RETRY_MS)
                            return@post
                        }
                        qr.show(code, res.getString(R.string.connect_or_open, address.removePrefix("http://")))
                        say(R.string.connect_waiting, Palette.ORANGE)
                    }
                }
            }
            Mode.SIGN_IN -> {
                info.show(res.getString(R.string.signin_kicker), res.getString(R.string.signin_title), "", res.getString(R.string.signin_line), null)
                val fresh = token != null && SystemClock.elapsedRealtime() - tokenAt < TOKEN_LIFE_MS
                if (fresh) {
                    fetchToken(gen, reuse = true)
                } else {
                    fetchToken(gen, reuse = false)
                }
            }
        }
    }

    private fun fetchToken(gen: Int, reuse: Boolean) {
        say(if (reuse) R.string.signin_waiting else R.string.signin_getting, if (reuse) Palette.ORANGE else Palette.DIM)
        Workers.misc.execute {
            val result = runCatching {
                val t = if (reuse) token!! else auth.newToken()
                t to Qr.code(auth.approvalUrl(t))
            }
            main.post {
                if (gen != generation) return@post
                result.onSuccess { (t, code) ->
                    if (!reuse) {
                        token = t
                        tokenAt = SystemClock.elapsedRealtime()
                    }
                    qr.show(code, res.getString(R.string.scan))
                    say(R.string.signin_waiting, Palette.ORANGE)
                    schedulePoll(gen)
                }.onFailure {
                    say(R.string.signin_offline, Palette.ORANGE)
                    main.postDelayed({ if (gen == generation) fetchToken(gen, reuse = false) }, RETRY_MS)
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
            fetchToken(gen, reuse = false)
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
                        stop()
                        listener.onSignedIn(name)
                    }
                }.onFailure { e ->
                    if (e is Tmdb.Failure && e.http == 401) {
                        token = null
                        fetchToken(gen, reuse = false)
                    } else {
                        say(R.string.signin_offline, Palette.ORANGE)
                        schedulePoll(gen)
                    }
                }
            }
        }
    }

    private fun stopWork() {
        generation++
        main.removeCallbacksAndMessages(null)
        Workers.misc.execute { link.stop() }
    }

    private fun say(text: Int, color: Int) = info.status(res.getString(text), color)

    private companion object {
        const val POLL_MS = 3_000L
        const val RETRY_MS = 5_000L

        /** TMDB request tokens last an hour; take a new one a little before that. */
        const val TOKEN_LIFE_MS = 50 * 60 * 1000L
    }
}
