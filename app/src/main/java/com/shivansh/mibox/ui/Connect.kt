package com.shivansh.mibox.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.shivansh.mibox.R
import com.shivansh.mibox.data.tmdb.KeyLink
import com.shivansh.mibox.data.tmdb.Tmdb

/**
 * The Connect page: a QR code that opens a page served by the TV itself, where the user pastes
 * their TMDB key on their phone. Serves only while the page is showing.
 */
class Connect(root: View, tmdb: Tmdb, private val onConnected: (key: String) -> Unit) {
    private val qr: ImageView = root.findViewById(R.id.connect_qr)
    private val url: TextView = root.findViewById(R.id.connect_url)
    private val line: TextView = root.findViewById(R.id.connect_line)
    private val status: TextView = root.findViewById(R.id.connect_status)
    private val dot: View = root.findViewById(R.id.connect_dot)
    private val main = Handler(Looper.getMainLooper())
    private val link = KeyLink(
        tmdb::verify,
        onChecking = { if (active) say(R.string.connect_checking, Palette.INK, pulsing = true) },
        onRejected = { offline ->
            if (active) say(if (offline) R.string.connect_offline else R.string.connect_refused, ORANGE, pulsing = true)
        },
    ) { key ->
        if (!active) return@KeyLink
        stop()
        say(R.string.connect_done, GREEN, pulsing = false)
        onConnected(key)
    }
    private var active = false
    private var generation = 0
    private var pulse: ObjectAnimator? = null

    init {
        root.findViewById<View>(R.id.connect_qr_card).background = GradientDrawable().apply {
            cornerRadius = Ui.px(24f)
            setColor(0xFFFFFFFF.toInt())
        }
    }

    /** [replacing]: the TV already has a key, and a new one would take its place. */
    fun start(replacing: Boolean) {
        line.setText(if (replacing) R.string.connect_line_replace else R.string.connect_line)
        if (active) return
        active = true
        val gen = ++generation
        say(R.string.connect_starting, Palette.alpha(0xFFFFFF, 0.4f), pulsing = false)
        Workers.apps.execute {
            val address = link.start()
            val code = address?.let { runCatching { Qr.code(it) }.getOrNull() }
            main.post {
                if (gen != generation) {
                    link.stop()
                    return@post
                }
                if (address == null || code == null) {
                    // No network address yet (Wi-Fi still joining): try again shortly.
                    say(R.string.connect_no_network, ORANGE, pulsing = false)
                    active = false
                    main.postDelayed({ if (gen == generation) start(replacing) }, RETRY_MS)
                    return@post
                }
                qr.setImageBitmap(code)
                qr.alpha = 0f
                qr.animate().alpha(1f).setDuration(400).setStartDelay(0).setInterpolator(Motion.EASE)
                url.text = url.resources.getString(R.string.connect_or_open, address.removePrefix("http://"))
                say(R.string.connect_waiting, Palette.INK, pulsing = true)
            }
        }
    }

    fun stop() {
        active = false
        generation++
        main.removeCallbacksAndMessages(null)
        pulse?.cancel()
        Workers.apps.execute { link.stop() }
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
        const val RETRY_MS = 5_000L
        const val GREEN = 0xFF3DF5A8.toInt()
        const val ORANGE = 0xFFFF8A4B.toInt()
    }
}
