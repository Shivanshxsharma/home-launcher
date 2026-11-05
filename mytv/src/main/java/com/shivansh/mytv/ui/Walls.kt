package com.shivansh.mytv.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import kotlin.math.max

/** One wallpaper. [picture] and [thumb] are asset paths, or absolute paths when [added]. */
class Wall(val id: String, val picture: String, val thumb: String, val added: Boolean)

/**
 * The wallpapers on offer: pictures sent from the phone, newest first, then those in this build.
 * Deleting an added one removes its files. A built-in one can't leave the app, so it is hidden, and
 * [restore] brings those back: a slip on the phone shouldn't be permanent.
 */
object Walls {
    private const val WIDTH = 1920
    private const val HEIGHT = 1080
    private const val THUMB_W = 416
    private const val THUMB_H = 234
    private const val MAX_ADDED = 40
    private const val HIDDEN = "walls_hidden"
    private lateinit var assets: AssetManager
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    private lateinit var thumbs: File

    @Volatile
    var all: List<Wall> = emptyList()
        private set

    /** How many built-in wallpapers are hidden, for the phone's restore button. */
    @Volatile
    var hidden = 0
        private set

    fun init(context: Context, prefs: SharedPreferences) {
        assets = context.assets
        this.prefs = prefs
        dir = File(context.filesDir, "walls").apply { mkdirs() }
        thumbs = File(dir, "thumbs").apply { mkdirs() }
        scan()
    }

    fun find(id: String) = all.firstOrNull { it.id == id }

    private fun hiddenIds() = prefs.getStringSet(HIDDEN, emptySet()).orEmpty()

    @Synchronized
    private fun scan() {
        val added = dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".jpg") }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val id = file.name.removeSuffix(".jpg")
                Wall(id, file.absolutePath, File(thumbs, file.name).absolutePath, added = true)
            }
        val names = assets.list("wallpapers").orEmpty().filter { it.endsWith(".jpg") }.map { it.removeSuffix(".jpg") }.sorted()
        val gone = hiddenIds()
        val built = names.filter { it !in gone }.map { Wall(it, "wallpapers/$it.jpg", "wallpapers/thumbs/$it.jpg", added = false) }
        hidden = names.count { it in gone }
        all = added + built
    }

    /** The thumbnail's bytes, for the phone page. */
    fun thumbBytes(wall: Wall): ByteArray =
        if (wall.added) File(wall.thumb).readBytes() else assets.open(wall.thumb).use { it.readBytes() }

    /** The thumbnail, for the Style page. Slow: call off the main thread. */
    fun thumbBitmap(wall: Wall, width: Int, height: Int): Bitmap =
        if (wall.added) Decode.file(File(wall.thumb), width, height) else Decode.asset(assets, wall.thumb, width, height)

    /** Makes a wallpaper of the picture in [source], or null if it isn't one. Slow: call off the main thread. */
    @Synchronized
    fun add(source: File): Wall? {
        if (all.count { it.added } >= MAX_ADDED) return null
        val picture = runCatching { Decode.file(source, WIDTH, HEIGHT) }.getOrNull() ?: return null
        val id = "my-" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        val file = File(dir, "$id.jpg")
        val thumb = File(thumbs, "$id.jpg")
        val saved = runCatching {
            val full = cover(picture, WIDTH, HEIGHT)
            file.outputStream().use { full.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val small = cover(full, THUMB_W, THUMB_H)
            thumb.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            full.recycle()
            small.recycle()
        }.isSuccess
        picture.recycle()
        if (!saved) {
            file.delete()
            thumb.delete()
            return null
        }
        scan()
        return find(id)
    }

    @Synchronized
    fun delete(id: String) {
        val wall = find(id) ?: return
        if (wall.added) {
            File(wall.picture).delete()
            File(wall.thumb).delete()
        } else {
            prefs.edit().putStringSet(HIDDEN, hiddenIds() + id).apply()
        }
        scan()
    }

    @Synchronized
    fun restore() {
        prefs.edit().remove(HIDDEN).apply()
        scan()
    }

    /** [source] scaled and cropped from its centre to fill exactly [width] by [height]. */
    private fun cover(source: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val w = (width / scale).toInt()
        val h = (height / scale).toInt()
        val x = (source.width - w) / 2
        val y = (source.height - h) / 2
        Canvas(out).drawBitmap(source, Rect(x, y, x + w, y + h), Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
