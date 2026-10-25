package com.shivansh.mibox.minimal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * The seconds ruler under the clock: a baseline with a tick for every second, longer every five
 * and fifteen. Drawn once; the orange second hand is a separate view that steps along it.
 */
class Ruler(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint().apply { strokeWidth = Ui.px(2f) }

    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        val bottom = height.toFloat()
        val span = width - Ui.px(6f)
        paint.color = Palette.LINE
        canvas.drawLine(0f, bottom - paint.strokeWidth / 2, width.toFloat(), bottom - paint.strokeWidth / 2, paint)
        for (s in 0..59) {
            val x = Ui.px(3f) + span * s / 59f
            val tall = when {
                s % 15 == 0 -> height * 0.7f
                s % 5 == 0 -> height * 0.42f
                else -> height * 0.2f
            }
            paint.color = if (s % 15 == 0) Palette.DIM else Palette.LINE
            canvas.drawLine(x, bottom - tall, x, bottom, paint)
        }
    }

    /** Where the second hand sits for [second] (0..59), as a translation from the left edge. */
    fun offsetFor(second: Int, handWidth: Int): Float = Ui.px(3f) + (width - Ui.px(6f)) * second / 59f - handWidth / 2f
}
