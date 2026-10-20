package com.shivansh.mibox.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shivansh.mibox.R

/**
 * The menu that opens when OK is held on a show: a few actions on a glass panel. It never takes
 * focus; while it is open the activity hands it the remote's keys. Up and down choose, OK runs the
 * choice, and Back, left or right close it.
 */
class ContextMenu(private val root: View, private val onClose: () -> Unit) {
    class Item(val icon: Int, val label: String, val run: () -> Unit)

    private val panel: View = root.findViewById(R.id.menu_panel)
    private val title: TextView = root.findViewById(R.id.menu_title)
    private val sub: TextView = root.findViewById(R.id.menu_sub)
    private val pill: View = root.findViewById(R.id.menu_pill)
    private val options: LinearLayout = root.findViewById(R.id.menu_options)
    private val rowHeight = Ui.px(72)
    private val dark = root.context.getColor(R.color.ink_dark)

    private var items: List<Item> = emptyList()
    private var selected = 0

    var isOpen = false
        private set

    init {
        val radius = Ui.px(44f)
        panel.background = LayerDrawable(
            arrayOf(
                GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(Palette.alpha(0x17171C, 0.94f))
                },
                Keyline(radius, Ui.px(1.5f), strength = 0.85f),
            ),
        )
    }

    fun open(heading: String, line: String, list: List<Item>) {
        items = list
        title.text = heading
        sub.text = line
        sub.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        options.removeAllViews()
        for (item in list) options.addView(row(item))
        select(0, animate = false)
        isOpen = true
        root.fadeTo(1f, 180)
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        panel.animate().scaleX(1f).scaleY(1f).setDuration(380).setStartDelay(0).setInterpolator(Motion.SETTLE)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        root.fadeTo(0f, 160)
        panel.animate().scaleX(0.97f).scaleY(0.97f).setDuration(160).setStartDelay(0).setInterpolator(Motion.EASE)
        onClose()
    }

    /** Every key while open comes here; the menu swallows them all. */
    fun key(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> select(selected - 1, animate = true)
            KeyEvent.KEYCODE_DPAD_DOWN -> select(selected + 1, animate = true)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) {
                val item = items.getOrNull(selected)
                close()
                item?.run?.invoke()
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.repeatCount == 0) close()
        }
    }

    private fun select(index: Int, animate: Boolean) {
        if (items.isEmpty()) return
        selected = index.coerceIn(0, items.size - 1)
        val y = (selected * rowHeight).toFloat()
        if (animate) {
            pill.animate().translationY(y).setDuration(240).setStartDelay(0).setInterpolator(Motion.SETTLE)
        } else {
            pill.animate().cancel()
            pill.translationY = y
        }
        for (i in 0 until options.childCount) {
            val color = if (i == selected) dark else Palette.INK
            val row = options.getChildAt(i) as LinearLayout
            (row.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
            (row.getChildAt(1) as TextView).setTextColor(color)
        }
    }

    private fun row(item: Item): View {
        val context = root.context
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.px(26), 0, Ui.px(24), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight)
            addView(
                ImageView(context).apply {
                    setImageResource(item.icon)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(Ui.px(24), Ui.px(24)),
            )
            addView(
                TextView(context).apply {
                    text = item.label
                    typeface = Fonts.semibold
                    includeFontPadding = false
                    textPx(25f)
                    maxLines = 1
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = Ui.px(20)
                },
            )
        }
    }
}
