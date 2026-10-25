package com.shivansh.mibox.minimal

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shivansh.mibox.data.AppEntry
import com.shivansh.mibox.data.HomeContent
import com.shivansh.mibox.data.InstalledApps
import com.shivansh.mibox.data.MediaType
import com.shivansh.mibox.data.Show
import com.shivansh.mibox.data.tmdb.Account
import com.shivansh.mibox.data.tmdb.ApiKey
import com.shivansh.mibox.data.tmdb.Catalog
import com.shivansh.mibox.data.tmdb.Tmdb
import com.shivansh.mibox.minimal.ui.AppArtSource
import com.shivansh.mibox.minimal.ui.Backdrop
import com.shivansh.mibox.minimal.ui.Clock
import com.shivansh.mibox.minimal.ui.Fonts
import com.shivansh.mibox.minimal.ui.ImageFiles
import com.shivansh.mibox.minimal.ui.Info
import com.shivansh.mibox.minimal.ui.Link
import com.shivansh.mibox.minimal.ui.Motion
import com.shivansh.mibox.minimal.ui.Palette
import com.shivansh.mibox.minimal.ui.Posters
import com.shivansh.mibox.minimal.ui.QrCard
import com.shivansh.mibox.minimal.ui.Strip
import com.shivansh.mibox.minimal.ui.Ui
import com.shivansh.mibox.minimal.ui.Workers
import com.shivansh.mibox.minimal.ui.fadeTo

/**
 * Minimal: two floors and nothing else. Home is a large clock over a row of app banners. One
 * press down is Favs, the user's TMDB favorite shows and films: the focused one's backdrop fills
 * the screen, its words sit at the left and the posters run along the bottom. Left and right move
 * along a row, up and down between the floors. Content and TMDB set-up are the Home launcher's,
 * shared.
 */
class MinimalActivity : Activity(), Link.Listener {
    private enum class Floor { HOME, FAVS }

    private lateinit var apiKey: ApiKey
    private lateinit var tmdb: Tmdb
    private lateinit var account: Account
    private lateinit var catalog: Catalog
    private lateinit var installed: InstalledApps

    private lateinit var clock: Clock
    private lateinit var appRow: Strip
    private lateinit var favRow: Strip
    private lateinit var backdrop: Backdrop
    private lateinit var info: Info
    private lateinit var link: Link
    private lateinit var posters: Posters
    private lateinit var appArt: AppArtSource

    private lateinit var root: View
    private lateinit var homeFloor: View
    private lateinit var favsFloor: View
    private lateinit var appsLabel: TextView
    private lateinit var appsCount: TextView
    private lateinit var favsCount: TextView
    private lateinit var downHint: TextView
    private lateinit var note: TextView

    /** TMDB content from the cache first, then fresh; null before TMDB is connected. */
    private var content: HomeContent? = null
    private var apps: List<AppEntry> = emptyList()
    private var favs: List<Show> = emptyList()
    private var lastRefresh = 0L

    /** Full records fetched for favs that came into focus, by show id. */
    private val enriched = HashMap<String, Show>()

    private var floor = Floor.HOME
    private val main = Handler(Looper.getMainLooper())
    private val hideNote = Runnable { note.fadeTo(0f, 300) }
    private val enrichSoon = Runnable { enrichFocused() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Fonts.init(this)
        setContentView(R.layout.activity_minimal)
        ImageFiles.init(this)

        apiKey = ApiKey(this)
        tmdb = Tmdb(this, apiKey)
        account = Account(this)
        catalog = Catalog(tmdb, account)
        installed = InstalledApps(this)

        root = findViewById(R.id.root)
        homeFloor = findViewById(R.id.home_floor)
        favsFloor = findViewById(R.id.favs_floor)
        appsLabel = findViewById(R.id.apps_label)
        appsCount = findViewById(R.id.apps_count)
        favsCount = findViewById(R.id.favs_count)
        downHint = findViewById(R.id.down_hint)
        note = findViewById(R.id.note)

        clock = Clock(homeFloor, findViewById(R.id.corner))
        appRow = Strip(findViewById<LinearLayout>(R.id.apps_track), findViewById(R.id.apps_bar))
        favRow = Strip(findViewById<LinearLayout>(R.id.favs_track), findViewById(R.id.favs_bar))
        appRow.lit = true
        favRow.lit = true
        backdrop = Backdrop(findViewById(R.id.backdrop))
        info = Info(findViewById(R.id.info))
        link = Link(QrCard(findViewById(R.id.qr)), info, tmdb, apiKey, account, this)
        posters = Posters()
        appArt = AppArtSource(this, Ui.px(APP_W), Ui.px(APP_H))

        content = catalog.load(offline = true)
        apps = ours(installed.cached())
        favs = favorites()
        fillApps(keep = false)
        paintDownHint()
    }

    override fun onResume() {
        super.onResume()
        clock.start()
        refreshContent()
        refreshApps()
        if (floor == Floor.FAVS && link.needed) link.start()
    }

    override fun onPause() {
        super.onPause()
        clock.stop()
        link.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed: back up to the clock.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) goHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            posters.trim()
            appArt.trim()
        }
    }

    // ---- The remote ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) move(event.keyCode)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (down && event.repeatCount == 0) openFocused()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (!down || event.repeatCount > 0) return true
                if (back()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun move(key: Int) {
        val row = if (floor == Floor.HOME) appRow else favRow
        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (row.move(-1)) onFocus()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (row.move(1)) onFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> goFavs()
            KeyEvent.KEYCODE_DPAD_UP -> goHome()
        }
    }

    /** Back climbs to the clock; there it does nothing as the Home screen, and leaves when opened as an app. */
    private fun back(): Boolean {
        if (floor == Floor.FAVS) {
            goHome()
            return true
        }
        return isDefaultHome()
    }

    // ---- The two floors ----

    private fun goFavs() {
        if (floor == Floor.FAVS) return
        floor = Floor.FAVS
        clock.big = false
        val drop = root.height * SLIDE
        leave(homeFloor, -drop)
        favsFloor.animate().cancel()
        favsFloor.translationY = drop
        favsFloor.alpha = 0f
        favsFloor.visibility = View.VISIBLE
        favsFloor.animate().translationY(0f).alpha(1f).setDuration(SLIDE_MS).setStartDelay(60).setInterpolator(Motion.SETTLE).withEndAction(null)
        backdrop.setShown(true, SLIDE_MS)
        fillFavs(keep = true)
    }

    private fun goHome() {
        if (floor == Floor.HOME) return
        floor = Floor.HOME
        clock.big = true
        link.stop()
        main.removeCallbacks(enrichSoon)
        val drop = root.height * SLIDE
        leave(favsFloor, drop)
        homeFloor.animate().cancel()
        homeFloor.translationY = -drop
        homeFloor.visibility = View.VISIBLE
        homeFloor.animate().translationY(0f).alpha(1f).setDuration(SLIDE_MS).setStartDelay(60).setInterpolator(Motion.SETTLE).withEndAction(null)
        backdrop.setShown(false, FADE_OUT_MS)
        paintDownHint()
    }

    /** The floor being left slides [by] and fades, then stops drawing. */
    private fun leave(floorView: View, by: Float) {
        floorView.animate().translationY(by).alpha(0f).setDuration(FADE_OUT_MS).setStartDelay(0).setInterpolator(Motion.EASE)
            .withEndAction { floorView.visibility = View.INVISIBLE }
    }

    private fun paintDownHint() {
        downHint.text = if (favs.isEmpty()) getString(R.string.row_favs) else getString(R.string.down_hint_count, favs.size)
    }

    // ---- Home: the apps ----

    private fun fillApps(keep: Boolean) {
        appRow.set(apps.map(::appTile), Ui.px(APP_W), Ui.px(APP_H), keep)
        onFocus()
    }

    /** An app's TV banner, or its icon centred on its own colour. */
    private fun appTile(entry: AppEntry): View = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Palette.PANEL)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        appArt.load(this, entry) { art, _ ->
            setImageBitmap(art.bitmap)
            scaleType = if (art.isBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER
            setBackgroundColor(if (art.isBanner) Palette.PANEL else Palette.alpha(art.glow, 0.28f))
        }
    }

    // ---- Favs ----

    private fun favorites(): List<Show> {
        val c = content ?: return emptyList()
        if (!account.signedIn) return emptyList()
        return (c.showFavorites + c.movieFavorites).map { enriched[it.id] ?: it }
    }

    private fun fillFavs(keep: Boolean) {
        if (link.needed) {
            favRow.set(emptyList(), Ui.px(FAV_W), Ui.px(FAV_H), keep = false)
            favsCount.text = ""
            backdrop.picture(null)
            link.start()
            return
        }
        link.stop()
        favs = favorites()
        val tiles = favs.map { show ->
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Palette.PANEL)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                posters.load(this, show.poster ?: show.still, Ui.px(FAV_W), Ui.px(FAV_H))
            }
        }
        favRow.set(tiles, Ui.px(FAV_W), Ui.px(FAV_H), keep)
        onFocus()
    }

    // ---- The focused tile ----

    private fun onFocus() {
        if (floor == Floor.HOME) {
            val entry = apps.getOrNull(appRow.focused)
            appsLabel.text = if (entry == null) getString(R.string.row_apps) else getString(R.string.row_apps_focused, entry.label)
            appsCount.text = if (entry != null) getString(R.string.row_count, appRow.focused + 1, appRow.size) else ""
            return
        }
        main.removeCallbacks(enrichSoon)
        val show = favs.getOrNull(favRow.focused)
        favsCount.text = if (show != null) getString(R.string.row_count, favRow.focused + 1, favRow.size) else ""
        if (show == null) {
            info.show(
                getString(R.string.row_favs),
                getString(R.string.favs_empty_title),
                "",
                getString(if (content == null) R.string.favs_offline else R.string.favs_empty_line),
                null,
            )
            backdrop.picture(null)
            return
        }
        val kind = getString(if (show.mediaType == MediaType.MOVIE) R.string.kicker_film else R.string.kicker_series)
        info.show(
            if (show.service.isEmpty()) kind else "$kind  /  ${show.service}",
            show.title,
            Info.metaFor(show),
            show.overview,
            show.provider?.let { getString(R.string.open_in, it.label) } ?: getString(R.string.not_streaming),
        )
        backdrop.picture(show.backdrop)
        if (!show.detailed) main.postDelayed(enrichSoon, ENRICH_MS)
    }

    private fun enrichFocused() {
        val show = favs.getOrNull(favRow.focused) ?: return
        Workers.content.execute {
            val rich = runCatching { catalog.enrich(show) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || rich == null || !rich.detailed) return@runOnUiThread
                enriched[show.id] = rich
                favs = favs.map { if (it.id == show.id) rich else it }
                if (floor == Floor.FAVS && favs.getOrNull(favRow.focused)?.id == show.id) onFocus()
            }
        }
    }

    private fun openFocused() {
        if (floor == Floor.HOME) {
            val entry = apps.getOrNull(appRow.focused) ?: return
            launch(entry, appRow.tile() ?: return)
        } else {
            val show = favs.getOrNull(favRow.focused) ?: return
            openShow(show, favRow.tile() ?: return)
        }
    }

    // ---- TMDB set-up ----

    override fun onKeyConnected(key: String) {
        apiKey.save(key)
        refreshContent(force = true)
    }

    override fun onSignedIn(name: String) {
        showNote(getString(R.string.note_signed_in, name))
        tmdb.forget("account-")
        refreshContent(force = true)
    }

    // ---- Content ----

    private fun refreshContent(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastRefresh < REFRESH_MS) return
        lastRefresh = now
        Workers.content.execute {
            val fresh = runCatching { catalog.load(offline = false) }.getOrNull() ?: return@execute
            runOnUiThread { if (!isDestroyed) applyContent(fresh) }
        }
    }

    private fun applyContent(fresh: HomeContent) {
        if (fresh == content) return
        content = fresh
        val before = favs.map { it.id }
        favs = favorites()
        if (floor == Floor.FAVS) {
            if (favs.map { it.id } != before || favRow.size != favs.size) fillFavs(keep = true) else onFocus()
        } else {
            paintDownHint()
        }
    }

    private fun refreshApps() {
        Workers.misc.execute {
            val fresh = installed.query()
            runOnUiThread {
                val mine = ours(fresh)
                if (isDestroyed || mine == apps) return@runOnUiThread
                apps = mine
                installed.save(fresh)
                fillApps(keep = true)
            }
            appArt.prune(fresh)
        }
    }

    /** Every app but this one. */
    private fun ours(list: List<AppEntry>) = list.filter { it.packageName != packageName }

    // ---- Opening things ----

    private fun showNote(text: String) {
        note.text = text.uppercase()
        note.fadeTo(1f, 160)
        main.removeCallbacks(hideNote)
        main.postDelayed(hideNote, NOTE_MS)
    }

    /** Opens the show's service. */
    private fun openShow(show: Show, from: View) {
        val provider = show.provider
        if (provider == null) {
            showNote(show.service.ifEmpty { getString(R.string.not_streaming) })
            return
        }
        val entry = apps.firstOrNull { provider.owns(it.packageName) }
        if (entry != null) launch(entry, from) else showNote(getString(R.string.provider_missing, provider.label))
    }

    private fun launch(entry: AppEntry, from: View) {
        val category = if (entry.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        try {
            startActivity(intent, options)
        } catch (_: ActivityNotFoundException) {
            refreshApps()
        } catch (_: SecurityException) {
            refreshApps()
        }
    }

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    private companion object {
        /** Tile sizes in layout px: app banners at 16:9, posters at 2:3. */
        const val APP_W = 384
        const val APP_H = 216
        const val FAV_W = 180
        const val FAV_H = 270

        /** Moving between floors: each slides this share of the screen and crossfades. */
        const val SLIDE = 0.3f
        const val SLIDE_MS = 420L
        const val FADE_OUT_MS = 220L

        const val ENRICH_MS = 350L
        const val REFRESH_MS = 60 * 1000L
        const val NOTE_MS = 2_400L
    }
}
