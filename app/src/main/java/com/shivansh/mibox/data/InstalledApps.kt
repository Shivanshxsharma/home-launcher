package com.shivansh.mibox.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.annotation.WorkerThread

data class AppEntry(
    val packageName: String,
    val className: String,
    val label: String,
    /** True when the activity is a TV (LEANBACK_LAUNCHER) entry point rather than a phone one. */
    val leanback: Boolean,
    val updatedAt: Long,
) {
    val component get() = ComponentName(packageName, className)
}

/** One tile in the apps row. */
data class AppTile(val kind: Kind, val label: String, val entry: AppEntry? = null) {
    enum class Kind { APP, SETTINGS, ALL_APPS }

    val provider: Provider? get() = entry?.let { Provider.forPackage(it.packageName) }
}

/** Brand colors and row order for apps the mockup knows; everything else follows alphabetically. */
object Brands {
    private val order = listOf(
        "com.netflix", "com.amazon.amazonvideo", "com.amazon.avod", "in.startv.hotstar",
        "com.apple.atve", "com.google.android.youtube.tv", "com.spotify.tv", "com.sonyliv",
        "com.plexapp", "com.graymatrix.did",
    )

    private val glows = mapOf(
        "com.google.android.youtube.tv" to 0xFFFF4A3A.toInt(),
        "com.spotify.tv.android" to 0xFF2BD46A.toInt(),
        "com.plexapp.android" to 0xFFE5A00D.toInt(),
    )

    fun glowFor(pkg: String): Int? = Provider.forPackage(pkg)?.color ?: glows[pkg]

    fun rank(pkg: String): Int = order.indexOfFirst { pkg.startsWith(it) }.let { if (it < 0) order.size else it }
}

/** The launchable apps on the box, with a cached copy so the row can draw before PackageManager answers. */
class InstalledApps(private val context: Context) {
    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun cached(): List<AppEntry> = decode(prefs.getString(KEY, null))

    fun save(apps: List<AppEntry>) {
        prefs.edit().putString(KEY, encode(apps)).apply()
    }

    @WorkerThread
    fun query(): List<AppEntry> {
        // Settings gets its own tile at the end of the row, so keep it out of the list.
        val settingsPkg = pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
        val seen = HashSet<String>()
        val apps = ArrayList<AppEntry>()
        for ((category, leanback) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val activity = info.activityInfo
                val pkg = activity.packageName
                if (pkg == context.packageName || pkg == settingsPkg) continue
                // One tile per app; the TV entry point wins over a phone one.
                if (!seen.add(pkg)) continue
                val updated = try {
                    pm.getPackageInfo(pkg, 0).lastUpdateTime
                } catch (_: PackageManager.NameNotFoundException) {
                    0L
                }
                apps += AppEntry(pkg, activity.name, info.loadLabel(pm).toString().trim(), leanback, updated)
            }
        }
        return apps.sortedWith(
            compareBy<AppEntry>({ Brands.rank(it.packageName) }, { !it.leanback }, { it.label.lowercase() }),
        )
    }

    private fun encode(apps: List<AppEntry>) = apps.joinToString("\n") {
        listOf(it.packageName, it.className, it.label.replace('\t', ' ').replace('\n', ' '), it.leanback, it.updatedAt)
            .joinToString("\t")
    }

    private fun decode(raw: String?): List<AppEntry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 5) null else AppEntry(f[0], f[1], f[2], f[3].toBoolean(), f[4].toLongOrNull() ?: 0L)
        }
    }

    companion object {
        private const val KEY = "list.v1"

        fun tiles(apps: List<AppEntry>, settingsLabel: String, allAppsLabel: String): List<AppTile> =
            apps.map { AppTile(AppTile.Kind.APP, it.label, it) } +
                AppTile(AppTile.Kind.SETTINGS, settingsLabel) +
                AppTile(AppTile.Kind.ALL_APPS, allAppsLabel)
    }
}
