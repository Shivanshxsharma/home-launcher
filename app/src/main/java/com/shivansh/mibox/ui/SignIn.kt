package com.shivansh.mibox.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.shivansh.mibox.R
import com.shivansh.mibox.data.tmdb.Auth
import com.shivansh.mibox.data.tmdb.Tmdb

/**
 * The sign-in page: a QR code for TMDB's approval page, and a quiet poll every few seconds until
 * the user approves on their phone. Runs only while the page is showing.
 */
class SignIn(root: View, private val auth: Auth, private val onSignedIn: (username: String) -> Unit) {
    private val qr: ImageView = root.findViewById(R.id.signin_qr)
    private val status: TextView = root.findViewById(R.id.signin_status)
    private val dot: View = root.findViewById(R.id.signin_dot)
    private val main = Handler(Looper.getMainLooper())
    private var token: String? = null
    private var tokenAt = 0L
    private var active = false
    private var generation = 0
    private var pulse: ObjectAnimator? = null

    init {
        root.findViewById<View>(R.id.signin_qr_card).background = GradientDrawable().apply {
            cornerRadius = Ui.px(24f)
            setColor(0xFFFFFFFF.toInt())
        }
    }

    fun start() {
        if (active) return
        active = true
        generation++
        val fresh = token != null && SystemClock.elapsedRealtime() - tokenAt < TOKEN_LIFE_MS
        if (fresh) {
            waiting()
            schedulePoll()
        } else {
            fetchToken()
        }
    }

    fun stop() {
        active = false
        generation++
        main.removeCallbacksAndMessages(null)
        pulse?.cancel()
    }

    private fun fetchToken() {
        val gen = generation
        say(R.string.signin_getting, Palette.alpha(0xFFFFFF, 0.4f))
        Workers.apps.execute {
            val result = runCatching {
                val t = auth.newToken()
                t to qrCode(auth.approvalUrl(t))
            }
            main.post {
                if (gen != generation) return@post
                result.onSuccess { (t, code) ->
                    token = t
                    tokenAt = SystemClock.elapsedRealtime()
                    qr.setImageBitmap(code)
                    qr.alpha = 0f
                    qr.animate().alpha(1f).setDuration(400).setStartDelay(0).setInterpolator(Motion.EASE)
                    waiting()
                    schedulePoll()
                }.onFailure {
                    say(R.string.signin_offline, 0xFFFF8A4B.toInt())
                    main.postDelayed({ if (gen == generation) fetchToken() }, RETRY_MS)
                }
            }
        }
    }

    private fun schedulePoll() = main.postDelayed(::poll, POLL_MS)

    private fun poll() {
        val t = token ?: return
        if (SystemClock.elapsedRealtime() - tokenAt > TOKEN_LIFE_MS) {
            token = null
            fetchToken()
            return
        }
        val gen = generation
        Workers.apps.execute {
            val result = runCatching { auth.trySession(t)?.let { auth.finish(it) } }
            main.post {
                if (gen != generation) return@post
                result.onSuccess { name ->
                    if (name == null) {
                        schedulePoll()
                    } else {
                        pulse?.cancel()
                        dot.alpha = 1f
                        status.text = status.resources.getString(R.string.signin_done, name)
                        dot.backgroundTintList = ColorStateList.valueOf(0xFF3DF5A8.toInt())
                        onSignedIn(name)
                    }
                }.onFailure { e ->
                    if (e is Tmdb.Failure && e.http == 401) {
                        // The token expired or was refused: start over with a new code.
                        token = null
                        fetchToken()
                    } else {
                        say(R.string.signin_offline, 0xFFFF8A4B.toInt())
                        schedulePoll()
                    }
                }
            }
        }
    }

    private fun waiting() {
        say(R.string.signin_waiting, Palette.INK)
        pulse?.cancel()
        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun say(text: Int, dotColor: Int) {
        status.setText(text)
        dot.backgroundTintList = ColorStateList.valueOf(dotColor)
    }

    /** Dark modules on white, drawn at its on-screen size so it stays crisp. */
    private fun qrCode(text: String): Bitmap {
        val size = Ui.px(264)
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF0A0A0C.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    private companion object {
        const val POLL_MS = 3_000L
        const val RETRY_MS = 5_000L

        /** TMDB request tokens last an hour; take a new one a little before that. */
        const val TOKEN_LIFE_MS = 50 * 60 * 1000L
    }
}
