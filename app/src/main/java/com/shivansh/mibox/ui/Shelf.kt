package com.shivansh.mibox.ui

import android.view.View
import android.widget.TextView
import com.shivansh.mibox.R
import com.shivansh.mibox.data.AppTile
import com.shivansh.mibox.data.ProviderShelf

/**
 * The top shelf over the app icons: the focused app's featured show, or just the app. Moving
 * between apps crossfades it: the old text dips out, the new one rises in.
 */
class Shelf(root: View) {
    private val body: View = root.findViewById(R.id.shelf_body)
    private val label: TextView = root.findViewById(R.id.shelf_label)
    private val title: TextView = root.findViewById(R.id.shelf_title)
    private val line: TextView = root.findViewById(R.id.shelf_line)
    private var shownKey: String? = null

    fun bind(tile: AppTile, featured: ProviderShelf?, appCount: Int, animate: Boolean) {
        val key = "${tile.kind}:${tile.entry?.packageName}:${tile.label}"
        if (key == shownKey) return
        shownKey = key
        if (!animate) {
            apply(tile, featured, appCount)
            return
        }
        body.animate().alpha(0f).translationY(-Ui.px(8f)).setDuration(120).setStartDelay(0).setInterpolator(Motion.EASE)
            .withEndAction {
                apply(tile, featured, appCount)
                body.translationY = Ui.px(14f)
                body.animate().alpha(1f).translationY(0f).setDuration(420).setStartDelay(0).setInterpolator(Motion.SETTLE)
            }
    }

    private fun apply(tile: AppTile, featured: ProviderShelf?, appCount: Int) {
        val res = label.resources
        when {
            featured != null -> {
                label.text = res.getString(R.string.shelf_from_shows, tile.label)
                title.text = featured.show.title
                title.showTitle(featured.show.style, 124f)
                title.lineBox(0.9f, top = 20f, bottom = 0f)
                line.text = featured.line
            }
            tile.kind == AppTile.Kind.SETTINGS -> {
                label.text = res.getString(R.string.shelf_system)
                plainTitle(tile.label)
                line.text = res.getString(R.string.shelf_settings_line)
            }
            tile.kind == AppTile.Kind.ALL_APPS -> {
                label.text = res.getString(R.string.shelf_everything)
                plainTitle(tile.label)
                line.text = res.getString(R.string.shelf_apps_count, appCount)
            }
            else -> {
                label.text = res.getString(R.string.shelf_app)
                plainTitle(tile.label)
                line.text = res.getString(R.string.shelf_open, tile.label)
            }
        }
    }

    private fun plainTitle(text: String) {
        title.text = text
        title.typeface = Fonts.semibold
        title.isAllCaps = false
        title.letterSpacing = -0.035f
        title.textPx(112f)
        title.lineBox(0.9f, top = 20f, bottom = 0f)
    }
}
