package com.shivansh.mibox.ui

import android.widget.LinearLayout
import android.widget.TextView
import com.shivansh.mibox.R

/** The remote's buttons for where focus is, PS5-style: an outlined key, then what it does. */
object Hints {
    private const val QUIET = 0xFFC9C5CF.toInt()

    fun show(row: LinearLayout, pairs: List<Pair<String, Int>>) {
        val context = row.context
        row.removeAllViews()
        pairs.forEachIndexed { i, (key, label) ->
            row.addView(
                TextView(context).apply {
                    text = key
                    typeface = Fonts.semibold
                    includeFontPadding = false
                    textPx(17f)
                    setTextColor(Palette.INK)
                    setBackgroundResource(R.drawable.rating_outline)
                    setPadding(Ui.px(9), Ui.px(3), Ui.px(9), Ui.px(3))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) marginStart = Ui.px(36)
                },
            )
            row.addView(
                TextView(context).apply {
                    setText(label)
                    typeface = Fonts.regular
                    includeFontPadding = false
                    textPx(20f)
                    setTextColor(QUIET)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = Ui.px(12)
                },
            )
        }
    }
}
