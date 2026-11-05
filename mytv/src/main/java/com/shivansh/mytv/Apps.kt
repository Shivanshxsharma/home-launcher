package com.shivansh.mytv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import com.shivansh.mytv.ui.Palette
import com.shivansh.mytv.ui.Worker
import kotlin.math.max
import kotlin.math.roundToInt

data class App(val label: String, val component: ComponentName, val leanback: Boolean) {
    val key: String get() = component.flattenToShortString()
}

/** The box's launchable apps and their tile art, rendered once as small RGB_565 bitmaps. */
class Apps(private val context: Context, private val width: Int, private val height: Int) {
    private val pm = context.packageManager
    private val art = HashMap<String, Bitmap>()
    private val main = Handler(Looper.getMainLooper())

    /** TV apps first in the box's own terms, then phone-style apps that have no TV entry. Slow: call off the main thread. */
    fun query(): List<App> {
        fun list(category: String, leanback: Boolean) = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { App(it.loadLabel(pm).toString(), ComponentName(it.activityInfo.packageName, it.activityInfo.name), leanback) }
        val tv = list(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        val packages = tv.map { it.component.packageName }.toSet()
        val rest = list(Intent.CATEGORY_LAUNCHER, false).filter { it.component.packageName !in packages }
        return (tv + rest).sortedBy { it.label.lowercase() }
    }

    fun load(app: App, deliver: (Bitmap) -> Unit) {
        art[app.key]?.let {
            deliver(it)
            return
        }
        Worker.pool.execute {
            val bitmap = runCatching { render(app) }.getOrNull() ?: return@execute
            main.post {
                art[app.key] = bitmap
                deliver(bitmap)
            }
        }
    }

    /** Lets go of art for apps that are gone. */
    fun prune(apps: List<App>) {
        val keep = apps.mapTo(HashSet()) { it.key }
        art.keys.retainAll(keep)
    }

    private fun render(app: App): Bitmap? {
        val info = try {
            pm.getActivityInfo(app.component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        val banner = info.loadBanner(pm)
        if (banner != null) {
            cover(banner, canvas)
        } else {
            canvas.drawColor(Palette.CARD)
            val icon = info.loadIcon(pm)
            val size = (height * 0.56f).roundToInt()
            val l = (width - size) / 2
            val t = (height - size) / 2
            icon.setBounds(l, t, l + size, t + size)
            icon.draw(canvas)
        }
        return bitmap
    }

    private fun cover(d: Drawable, canvas: Canvas) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: width
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: height
        val scale = max(width.toFloat() / iw, height.toFloat() / ih)
        val w = (iw * scale).roundToInt()
        val h = (ih * scale).roundToInt()
        val l = (width - w) / 2
        val t = (height - h) / 2
        d.setBounds(l, t, l + w, t + h)
        d.draw(canvas)
    }
}

/** The apps opened lately, latest first: MY TV's own list, as Android doesn't show one app another's. */
object Recents {
    private const val KEY = "recent_apps"
    private const val MAX = 12

    fun keys(prefs: SharedPreferences) = prefs.getString(KEY, null)?.split('|').orEmpty().filter { it.isNotEmpty() }

    fun note(prefs: SharedPreferences, key: String) = save(prefs, (listOf(key) + keys(prefs).filter { it != key }).take(MAX))

    fun forget(prefs: SharedPreferences, key: String) = save(prefs, keys(prefs).filter { it != key })

    fun clear(prefs: SharedPreferences) = prefs.edit().remove(KEY).apply()

    private fun save(prefs: SharedPreferences, keys: List<String>) = prefs.edit().putString(KEY, keys.joinToString("|")).apply()
}
