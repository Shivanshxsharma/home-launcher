package com.shivansh.mibox.ui

import android.content.res.AssetManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Muted, autoplaying previews behind the UI, one at a time.
 *
 * The video goes to a [SurfaceView], which the box composites in hardware under the window (on
 * the Mi Box, its video plane), so playback costs the GPU almost nothing. The UI shows it by
 * fading its own backdrop away once the first frame is up ([Listener.onPreviewShown]).
 *
 * Phase 1 plays the bundled demo clips; phase 2 swaps in trailers from TMDB.
 */
class Previews(
    private val surface: SurfaceView,
    private val assets: AssetManager,
    private val listener: Listener,
) : SurfaceHolder.Callback {
    interface Listener {
        fun onPreviewShown()

        /** [finished] is true when the clip played to its end, false when it was cut short. */
        fun onPreviewGone(finished: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surfaceReady = false
    private var cued: String? = null
    private var due = false
    private var loading: String? = null
    private var shown = false
    private var waiting = false
    private val starter = Runnable {
        waiting = false
        due = true
        start()
    }

    init {
        surface.holder.addCallback(this)
        // The clips are 720p; the compositor scales them to the screen.
        surface.holder.setFixedSize(1280, 720)
    }

    val isShowing get() = shown

    /** Plays [clip] after [delayMs] of focus resting, stopping whatever plays now. Null stops. */
    fun cue(clip: String?, delayMs: Long) {
        if (clip != null && clip == cued && (loading == clip || waiting)) return
        stop()
        cued = clip
        if (clip != null) {
            waiting = true
            main.postDelayed(starter, delayMs)
        }
    }

    /** Cuts the preview now. The last frame stays on the surface while the art fades back over it. */
    fun stop() {
        main.removeCallbacks(starter)
        waiting = false
        due = false
        cued = null
        loading = null
        player?.let {
            try {
                if (it.isPlaying) it.pause()
            } catch (_: IllegalStateException) {
                it.reset()
            }
        }
        if (shown) {
            shown = false
            listener.onPreviewGone(finished = false)
        }
    }

    /** Lets go of the decoder, e.g. while another app is in front. */
    fun release() {
        stop()
        player?.release()
        player = null
    }

    private fun start() {
        val clip = cued ?: return
        if (!due || !surfaceReady || loading == clip) return
        val p = player ?: MediaPlayer().also { player = it }
        try {
            p.reset()
            assets.openFd(clip).use { fd -> p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            p.setSurface(surface.holder.surface)
            p.setVolume(0f, 0f)
            p.setOnPreparedListener { if (loading == clip) it.start() }
            p.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && loading == clip && !shown) {
                    shown = true
                    listener.onPreviewShown()
                }
                false
            }
            p.setOnCompletionListener {
                if (loading != clip) return@setOnCompletionListener
                loading = null
                cued = null
                if (shown) {
                    shown = false
                    listener.onPreviewGone(finished = true)
                }
            }
            p.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            loading = clip
            p.prepareAsync()
        } catch (_: Exception) {
            loading = null
            p.reset()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        release()
    }
}
