package com.shivansh.mibox.minimal.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.WorkerThread
import com.shivansh.mibox.data.AppEntry
import com.shivansh.mibox.data.Brands
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** An app's tile art: its TV banner at tile size, or failing that its icon, plus a glow color. */
class AppArt(val bitmap: Bitmap, val isBanner: Boolean, val glow: Int)

/**
 * Renders app banners once at [tileWidth] x [tileHeight] and keeps them on disk, keyed by the
 * app's last update, so the rail fills from small PNGs on later starts instead of loading other
 * apps' resources.
 */
class AppArtSource(context: Context, private val tileWidth: Int, private val tileHeight: Int) {
    private val pm = context.packageManager
    private val dir = File(context.cacheDir, "app-art")
    private val iconSize = (tileHeight * 0.62f).toInt()
    private val loader = Loader<AppArt>(6 * 1024 * 1024, { it.bitmap.allocationByteCount }, Workers.images)

    fun load(target: View, entry: AppEntry, deliver: (AppArt, Boolean) -> Unit) =
        loader.load(target, key(entry), { produce(entry) }, deliver)

    fun trim() = loader.clear()

    /** Deletes cached art for apps that are gone or have updated since. */
    @WorkerThread
    fun prune(apps: List<AppEntry>) {
        val keep = apps.mapTo(HashSet(), ::key)
        dir.listFiles()?.forEach { if (it.name.substringBefore('~') !in keep) it.delete() }
    }

    /** Includes the tile size, so a change of icon size re-renders and prunes the old art. */
    private fun key(e: AppEntry) =
        "${e.packageName}_${Integer.toHexString(e.className.hashCode())}_${e.updatedAt}_$tileWidth"

    @WorkerThread
    private fun produce(e: AppEntry): AppArt? {
        dir.mkdirs()
        val base = key(e)
        val bannerFile = File(dir, "$base~banner.png")
        val iconFile = File(dir, "$base~icon.png")
        val known = Brands.glowFor(e.packageName)
        fun art(bitmap: Bitmap, banner: Boolean) = AppArt(bitmap, banner, known ?: vibrant(bitmap))

        if (bannerFile.exists()) BitmapFactory.decodeFile(bannerFile.path)?.let { return art(it, true) }
        if (iconFile.exists()) BitmapFactory.decodeFile(iconFile.path)?.let { return art(it, false) }

        val info = try {
            pm.getActivityInfo(e.component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        // Falls back to the application's banner when the activity has none.
        info.loadBanner(pm)?.let { banner ->
            val bitmap = render(banner, tileWidth, tileHeight)
            save(bitmap, bannerFile)
            return art(bitmap, true)
        }
        val bitmap = render(info.loadIcon(pm), iconSize, iconSize)
        save(bitmap, iconFile)
        return art(bitmap, false)
    }

    /** Draws [d] cover-cropped into a [width] x [height] bitmap. */
    private fun render(d: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: width
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: height
        val scale = max(width.toFloat() / iw, height.toFloat() / ih)
        val dw = (iw * scale).roundToInt()
        val dh = (ih * scale).roundToInt()
        val left = (width - dw) / 2
        val top = (height - dh) / 2
        d.setBounds(left, top, left + dw, top + dh)
        d.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: IOException) {
            file.delete()
        }
    }

    companion object {
        /** The strongest saturated hue in [bitmap], brightened for use as a glow; cream if there is none. */
        fun vibrant(bitmap: Bitmap): Int {
            val w = bitmap.width
            val h = bitmap.height
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            val bins = 24
            val weight = FloatArray(bins)
            val r = FloatArray(bins)
            val g = FloatArray(bins)
            val b = FloatArray(bins)
            val hsv = FloatArray(3)
            var i = 0
            while (i < px.size) {
                val c = px[i]
                i += 3
                if (Color.alpha(c) < 200) continue
                Color.colorToHSV(c, hsv)
                if (hsv[1] < 0.35f || hsv[2] < 0.35f) continue
                val wgt = hsv[1] * hsv[2] * hsv[2]
                val bin = (hsv[0] / 360f * bins).toInt().coerceIn(0, bins - 1)
                weight[bin] += wgt
                r[bin] += Color.red(c) * wgt
                g[bin] += Color.green(c) * wgt
                b[bin] += Color.blue(c) * wgt
            }
            val best = weight.indices.maxByOrNull { weight[it] } ?: return Palette.INK
            if (weight[best] < px.size / 3 * 0.01f) return Palette.INK
            val w0 = weight[best]
            Color.colorToHSV(Color.rgb((r[best] / w0).toInt(), (g[best] / w0).toInt(), (b[best] / w0).toInt()), hsv)
            hsv[1] = max(hsv[1], 0.55f)
            hsv[2] = max(hsv[2], 0.9f)
            return Color.HSVToColor(hsv)
        }
    }
}
