package com.shivansh.mytv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shivansh.mibox.data.Show
import com.shivansh.mibox.data.YouTube
import com.shivansh.mibox.data.tmdb.Account
import com.shivansh.mibox.data.tmdb.ApiKey
import com.shivansh.mibox.data.tmdb.Catalog
import com.shivansh.mibox.data.tmdb.Lists
import com.shivansh.mibox.data.tmdb.Tmdb
import com.shivansh.mytv.ui.Bar
import com.shivansh.mytv.ui.Cell
import com.shivansh.mytv.ui.ClockFace
import com.shivansh.mytv.ui.Corner
import com.shivansh.mytv.ui.Hero
import com.shivansh.mytv.ui.ImageFiles
import com.shivansh.mytv.ui.Keys
import com.shivansh.mytv.ui.Link
import com.shivansh.mytv.ui.Menu
import com.shivansh.mytv.ui.Motion
import com.shivansh.mytv.ui.Palette
import com.shivansh.mytv.ui.Pill
import com.shivansh.mytv.ui.Posters
import com.shivansh.mytv.ui.QrCard
import com.shivansh.mytv.ui.Scenery
import com.shivansh.mytv.ui.Tiles
import com.shivansh.mytv.ui.Ui
import com.shivansh.mytv.ui.Worker
import com.shivansh.mytv.ui.fadeTo
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MY TV. A floating bar of tabs, and Home in two quiet racks, one on screen at a time, Apple TV
 * style: first your favorite films and shows, with the focused one's backdrop filling the screen;
 * one press down, your apps under a large clock on the wallpaper. Favorites holds them all, Search
 * finds films and shows on TMDB, and Style sets the wallpaper and clock face.
 */
class MyTvActivity : Activity(), Link.Listener {
    private enum class Page { HOME, FAVORITES, SEARCH, STYLE }

    /** Where the remote is. MAIN and SIDE are a page's first and second things. */
    private enum class Zone { BAR, CORNER, FAVS, APPS, MAIN, SIDE, MOVE }

    private lateinit var prefs: SharedPreferences
    private lateinit var apiKey: ApiKey
    private lateinit var tmdb: Tmdb
    private lateinit var account: Account
    private lateinit var catalog: Catalog
    private lateinit var lists: Lists
    private lateinit var link: Link
    private lateinit var apps: Apps
    private lateinit var posters: Posters
    private lateinit var scenery: Scenery
    private lateinit var hero: Hero
    private lateinit var menu: Menu
    private lateinit var bar: Bar
    private lateinit var corner: Corner
    private lateinit var favRack: Tiles
    private lateinit var appRack: Tiles
    private lateinit var grid: Tiles
    private lateinit var results: Tiles
    private lateinit var walls: Tiles
    private lateinit var faces: Tiles
    private lateinit var keys: Keys

    private lateinit var clockFace: ClockFace
    private lateinit var clockGroup: View
    private lateinit var date: TextView
    private lateinit var heroView: View
    private lateinit var favRackView: View
    private lateinit var appRackView: View
    private lateinit var pages: Map<Page, View>
    private lateinit var scene: View
    private lateinit var searchQuery: TextView
    private lateinit var searchNone: TextView
    private lateinit var note: TextView
    private lateinit var networkIcon: ImageView
    private lateinit var networkLabel: TextView
    private var networkWatch: ConnectivityManager.NetworkCallback? = null

    private var all: List<App> = emptyList()
    private var favs: List<Show> = emptyList()
    private var found: List<Show> = emptyList()
    private var query = ""
    private var searchGeneration = 0
    private var lastRefresh = 0L

    private var page = Page.HOME
    private var zone = Zone.FAVS

    /** Which of Home's two racks is showing. */
    private var favsShowing = true
    private var heroShowing = true
    private var holding = false
    private var resumed = false
    private val main = Handler(Looper.getMainLooper())
    private val holdOk = Runnable {
        holding = false
        openOptions()
    }
    private val hideScene = Runnable { if (page == Page.FAVORITES || page == Page.SEARCH) scene.visibility = View.INVISIBLE }
    private val hideNote = Runnable { note.fadeTo(0f, 300) }
    private val rotate = Runnable { nextWallpaper() }
    private val tick = Runnable { paintClock() }
    private val runSearch = Runnable { search() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        setContentView(R.layout.activity_mytv)
        ImageFiles.init(this)
        prefs = getSharedPreferences("mytv", MODE_PRIVATE)
        apiKey = ApiKey(this)
        tmdb = Tmdb(this, apiKey)
        account = Account(this)
        catalog = Catalog(tmdb, account)
        lists = Lists(tmdb, account)
        apps = Apps(this, Ui.px(APP_W), Ui.px(APP_H))
        posters = Posters()

        scene = findViewById(R.id.scene)
        clockFace = findViewById(R.id.clock)
        clockGroup = findViewById(R.id.clock_group)
        date = findViewById(R.id.date)
        heroView = findViewById(R.id.hero)
        favRackView = findViewById(R.id.rack_favs)
        appRackView = findViewById(R.id.rack_apps)
        pages = mapOf(
            Page.FAVORITES to findViewById<View>(R.id.page_favs),
            Page.SEARCH to findViewById<View>(R.id.page_search),
            Page.STYLE to findViewById<View>(R.id.page_style),
        )
        searchQuery = findViewById(R.id.search_query)
        searchNone = findViewById(R.id.search_none)
        note = findViewById(R.id.note)
        networkIcon = findViewById(R.id.corner_network_icon)
        networkLabel = findViewById(R.id.corner_network_label)

        scenery = Scenery(findViewById(R.id.wall_lower), findViewById(R.id.wall_upper), assets)
        hero = Hero(heroView)
        link = Link(QrCard(findViewById(R.id.qr)), hero, tmdb, apiKey, account, this)
        menu = Menu(findViewById(R.id.menu))
        bar = Bar(findViewById<LinearLayout>(R.id.tabs), findViewById<Pill>(R.id.bar_pill))
        corner = Corner(listOf(findViewById(R.id.corner_network), findViewById(R.id.corner_settings)))
        favRack = Tiles(favRackView as FrameLayout, Ui.px(RACK_W), Ui.px(RACK_H), Ui.px(RACK_H), Tiles.ROW)
        appRack = Tiles(findViewById(R.id.rack_apps_row), Ui.px(APP_W), Ui.px(APP_H), Ui.px(APP_H + LABEL_H), Tiles.ROW)
        grid = Tiles(findViewById(R.id.favs_grid), Ui.px(POSTER_W), Ui.px(POSTER_H), Ui.px(POSTER_H + LABEL_H), GRID_COLS, labelRest = 0.7f)
        results = Tiles(findViewById(R.id.search_results), Ui.px(RESULT_W), Ui.px(RESULT_H), Ui.px(RESULT_H + LABEL_H), RESULT_COLS, labelRest = 0.7f, left = Ui.px(40))
        walls = Tiles(findViewById(R.id.style_walls), Ui.px(WALL_W), Ui.px(WALL_H), Ui.px(WALL_H), Tiles.ROW)
        faces = Tiles(findViewById(R.id.style_faces), Ui.px(WALL_W), Ui.px(FACE_H), Ui.px(FACE_H), Tiles.ROW)
        keys = findViewById(R.id.keys)
        keys.onKey = ::onKey

        clockFace.setFace(prefs.getInt(FACE, 0).coerceIn(0, ClockFace.COUNT - 1))
        fillStyle()
        watchNetwork()
        paintScreen(animate = false)
        loadApps()
        loadFavs(offline = true)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        paintClock()
        loadApps()
        val now = SystemClock.uptimeMillis()
        if (now - lastRefresh > REFRESH_MS) {
            lastRefresh = now
            loadFavs(offline = false)
        }
        paintScreen(animate = false)
        scheduleRotate()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(tick)
        main.removeCallbacks(rotate)
        main.removeCallbacks(holdOk)
        holding = false
        menu.close()
        link.stop()
    }

    override fun onDestroy() {
        networkWatch?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed: back to the first rack.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            endMove()
            corner.leave()
            favsShowing = true
            showPage(Page.HOME)
            setZone(Zone.FAVS)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) posters.trim()
    }

    // ---- The remote ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menu.isOpen) {
            menu.key(event)
            return true
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) move(0, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) move(0, 1)
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(-1, 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(1, 0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> ok(event)
            KeyEvent.KEYCODE_MENU -> if (down && event.repeatCount == 0) openOptions()
            KeyEvent.KEYCODE_BACK -> {
                if (!down || event.repeatCount > 0) return true
                if (!back()) return super.dispatchKeyEvent(event)
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    /** On a title or an app, OK runs on release, so that holding it can open the options instead. */
    private fun ok(event: KeyEvent) {
        if (zone == Zone.MOVE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) endMove()
            return
        }
        if (focusedShow() == null && focusedApp() == null) {
            if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || page == Page.SEARCH)) press()
            return
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                holding = true
                main.postDelayed(holdOk, HOLD_MS)
            }
            KeyEvent.ACTION_UP -> if (holding) {
                holding = false
                main.removeCallbacks(holdOk)
                focusedTiles()?.squish()
                focusedShow()?.let(::openTitle) ?: focusedApp()?.let(::launch)
            }
        }
    }

    private fun press() {
        when (zone) {
            Zone.BAR -> enterPage()
            Zone.CORNER -> openCorner()
            Zone.MAIN -> when (page) {
                Page.SEARCH -> keys.press()
                Page.STYLE -> {
                    walls.squish()
                    pickWallpaper()
                }
                else -> Unit
            }
            Zone.SIDE -> if (page == Page.STYLE) {
                faces.squish()
                pickFace()
            }
            else -> Unit
        }
    }

    private fun move(dRow: Int, dCol: Int) {
        when (zone) {
            Zone.BAR -> when {
                dCol > 0 && page == Page.entries.last() -> setZone(Zone.CORNER).also { corner.enter() }
                dCol != 0 -> Page.entries.getOrNull(page.ordinal + dCol)?.let(::showPage)
                dRow > 0 -> enterPage()
            }
            Zone.CORNER -> when {
                dCol != 0 -> if (!corner.move(dCol) && dCol < 0) leaveCorner()
                dRow > 0 -> {
                    corner.leave()
                    enterPage()
                }
            }
            Zone.FAVS -> when {
                dCol != 0 -> if (favRack.move(0, dCol)) paintFocus()
                dRow > 0 -> {
                    favsShowing = false
                    setZone(Zone.APPS)
                }
                dRow < 0 -> setZone(Zone.BAR)
            }
            Zone.APPS -> when {
                dCol != 0 -> if (appRack.move(0, dCol)) paintAppLabel()
                dRow < 0 -> {
                    favsShowing = true
                    setZone(Zone.FAVS)
                }
            }
            Zone.MOVE -> if (dCol != 0) {
                val from = appRack.focused
                if (appRack.swap(dCol)) {
                    val order = appList().map { it.key }.toMutableList()
                    java.util.Collections.swap(order, from, appRack.focused)
                    prefs.edit().putString(ORDER, order.joinToString("|")).apply()
                }
            }
            Zone.MAIN -> when (page) {
                Page.FAVORITES -> if (!grid.move(dRow, dCol) && dRow < 0) setZone(Zone.BAR)
                Page.SEARCH -> if (!keys.move(dRow, dCol)) {
                    if (dRow < 0) setZone(Zone.BAR)
                    if (dCol > 0 && results.size > 0) setZone(Zone.SIDE)
                }
                Page.STYLE -> when {
                    dCol != 0 -> walls.move(0, dCol)
                    dRow > 0 -> setZone(Zone.SIDE)
                    dRow < 0 -> setZone(Zone.BAR)
                }
                else -> Unit
            }
            Zone.SIDE -> when (page) {
                Page.SEARCH -> if (!results.move(dRow, dCol) && (dCol < 0 || dRow < 0)) setZone(Zone.MAIN)
                Page.STYLE -> when {
                    dCol != 0 -> faces.move(0, dCol)
                    dRow < 0 -> setZone(Zone.MAIN)
                }
                else -> Unit
            }
        }
    }

    /** Back climbs: to the first rack, the bar, then Home; there it does nothing unless opened as an app. */
    private fun back(): Boolean {
        when (zone) {
            Zone.MOVE -> endMove()
            Zone.CORNER -> leaveCorner()
            Zone.APPS -> {
                favsShowing = true
                setZone(Zone.FAVS)
            }
            Zone.FAVS, Zone.MAIN -> setZone(Zone.BAR)
            Zone.SIDE -> setZone(if (page == Page.SEARCH) Zone.MAIN else Zone.BAR)
            Zone.BAR -> {
                if (page == Page.HOME) return isDefaultHome()
                showPage(Page.HOME)
            }
        }
        return true
    }

    private fun setZone(next: Zone) {
        zone = next
        favRack.lit = next == Zone.FAVS
        appRack.lit = next == Zone.APPS || next == Zone.MOVE
        grid.lit = page == Page.FAVORITES && next == Zone.MAIN
        keys.setLit(page == Page.SEARCH && next == Zone.MAIN)
        results.lit = page == Page.SEARCH && next == Zone.SIDE
        walls.lit = page == Page.STYLE && next == Zone.MAIN
        faces.lit = page == Page.STYLE && next == Zone.SIDE
        paintScreen(animate = true)
    }

    private fun enterPage() {
        when (page) {
            Page.HOME -> setZone(if (favsShowing) Zone.FAVS else Zone.APPS)
            Page.FAVORITES -> setZone(if (grid.size > 0) Zone.MAIN else Zone.BAR)
            else -> setZone(Zone.MAIN)
        }
    }

    private fun leaveCorner() {
        corner.leave()
        setZone(Zone.BAR)
    }

    private fun openCorner() {
        val intent = Intent(if (corner.focused == Corner.NETWORK) Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_SETTINGS)
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---- What's on screen ----

    private fun showPage(next: Page) {
        if (next == page) return
        pages[page]?.fadeTo(0f, 180)
        page = next
        main.removeCallbacks(hideScene)
        scene.visibility = View.VISIBLE
        if (next == Page.FAVORITES || next == Page.SEARCH) main.postDelayed(hideScene, 240)
        pages[next]?.let {
            it.alpha = 0f
            it.fadeTo(1f, 240)
        }
        setZone(zone)
        scheduleRotate()
    }

    /** Fits everything to the page and rack; the title or the clock, and which rack. */
    private fun paintScreen(animate: Boolean) {
        bar.set(page.ordinal, zone == Zone.BAR)
        val home = page == Page.HOME
        val wantHero = home && favsShowing
        val wantClock = (home && !favsShowing) || page == Page.STYLE
        val speed = if (animate) 260L else 0L
        if (wantHero != heroShowing || !animate) {
            heroShowing = wantHero
            slide(favRackView, wantHero, Ui.px(70f))
            slide(appRackView, home && !favsShowing, -Ui.px(70f))
            heroView.fadeTo(if (wantHero) 1f else 0f, speed)
        } else {
            slide(appRackView, home && !favsShowing, -Ui.px(70f))
        }
        clockGroup.fadeTo(if (wantClock) 1f else 0f, speed)
        if (wantHero) {
            if (link.needed && resumed) link.start() else link.stop()
            paintFocus()
        } else {
            link.stop()
            scenery.wallpaper(prefs.getInt(WALLPAPER, 0).coerceIn(0, Scenery.NAMES.size - 1), fadeMs = 620)
        }
    }

    /** A rack arrives on a spring from [from] below or above, or leaves the same way. */
    private fun slide(view: View, on: Boolean, from: Float) {
        val shown = view.visibility == View.VISIBLE && view.alpha > 0f
        if (on == shown) return
        if (on) {
            view.translationY = from
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().translationY(0f).alpha(1f).setDuration(Motion.ms(520)).setStartDelay(0).setInterpolator(Motion.LIFT).withEndAction(null)
        } else {
            view.animate().translationY(from).alpha(0f).setDuration(Motion.ms(220)).setStartDelay(0).setInterpolator(Motion.EASE)
                .withEndAction { view.visibility = View.INVISIBLE }
        }
    }

    private fun paintFocus() {
        if (link.needed) return
        val show = favs.getOrNull(favRack.focused)
        if (show == null) {
            hero.show(getString(R.string.tab_favorites), getString(R.string.favs_empty_title), "", getString(R.string.favs_empty_line), null)
            return
        }
        hero.show(Hero.kickerFor(show, getString(R.string.kicker_film), getString(R.string.kicker_series)), show.title, Hero.metaFor(show), show.overview, null)
        scenery.backdrop(show.backdrop)
    }

    private fun paintAppLabel() {
        val label = findViewById<TextView>(R.id.rack_apps_label)
        label.text = appList().getOrNull(appRack.focused)?.label ?: getString(R.string.row_apps)
    }

    private fun paintClock() {
        val now = LocalDateTime.now()
        val h24 = DateFormat.is24HourFormat(this)
        val hour = if (h24) "%02d".format(now.hour) else ((now.hour + 11) % 12 + 1).toString()
        clockFace.set(hour, "%02d".format(now.minute), now.hour, now.minute)
        date.text = now.format(DATE)
        main.removeCallbacks(tick)
        // To the next whole minute: the clock does nothing in between.
        if (resumed) main.postDelayed(tick, (60 - now.second) * 1000L - now.nano / 1_000_000 + 50)
    }

    private fun showNote(text: String) {
        note.text = text
        note.fadeTo(1f, 200)
        main.removeCallbacks(hideNote)
        main.postDelayed(hideNote, NOTE_MS)
    }

    // ---- Films and shows ----

    private fun loadFavs(offline: Boolean) {
        Worker.net.execute {
            val fresh = runCatching { catalog.favorites(offline) }.getOrNull() ?: return@execute
            main.post {
                if (isDestroyed || fresh.isEmpty() && offline) return@post
                if (fresh.map { it.id } != favs.map { it.id }) {
                    favs = fresh
                    fillFavs()
                }
            }
        }
    }

    private fun fillFavs() {
        favRack.set(favs.map { poster(it, RACK_W, RACK_H, label = false) }, keep = true)
        grid.set(favs.map { poster(it, POSTER_W, POSTER_H, label = true) }, keep = true)
        if (page == Page.HOME && favsShowing) paintFocus()
    }

    private fun poster(show: Show, width: Int, height: Int, label: Boolean) = Cell(this, Ui.px(width), Ui.px(height)).apply {
        if (label) this.label.text = show.title
        posters.load(image, show.poster ?: show.still, Ui.px(width), Ui.px(height))
    }

    private fun focusedShow(): Show? = when {
        zone == Zone.FAVS -> favs.getOrNull(favRack.focused)
        page == Page.FAVORITES && zone == Zone.MAIN -> favs.getOrNull(grid.focused)
        page == Page.SEARCH && zone == Zone.SIDE -> found.getOrNull(results.focused)
        else -> null
    }

    private fun focusedTiles(): Tiles? = when {
        zone == Zone.FAVS -> favRack
        zone == Zone.APPS -> appRack
        page == Page.FAVORITES -> grid
        page == Page.SEARCH -> results
        else -> null
    }

    private fun isFavorite(show: Show) = favs.any { it.tmdbId == show.tmdbId && it.mediaType == show.mediaType }

    /** The full record (its service and trailers), then [use] on the main thread. */
    private fun withDetails(show: Show, use: (Show) -> Unit) {
        if (show.detailed) {
            use(show)
            return
        }
        Worker.net.execute {
            val rich = runCatching { catalog.enrich(show) }.getOrDefault(show)
            main.post { if (!isDestroyed) use(rich) }
        }
    }

    /** Opens the title's streaming app, or its trailer when none of them has it. */
    private fun openTitle(show: Show) = withDetails(show) { rich ->
        val app = rich.provider?.let { provider -> all.firstOrNull { provider.owns(it.component.packageName) } }
        when {
            app != null -> launch(app)
            rich.trailers.isNotEmpty() -> YouTube.play(this, rich.trailers.first())
            else -> showNote(rich.service.ifEmpty { getString(R.string.not_streaming) })
        }
    }

    private fun playTrailer(show: Show) = withDetails(show) { rich ->
        val key = rich.trailers.firstOrNull()
        if (key == null || !YouTube.play(this, key)) showNote(getString(R.string.no_trailer))
    }

    private fun toggleFavorite(show: Show) {
        if (!account.signedIn) {
            showNote(getString(R.string.sign_in_first))
            favsShowing = true
            showPage(Page.HOME)
            setZone(Zone.FAVS)
            return
        }
        val on = !isFavorite(show)
        favs = if (on) listOf(show) + favs else favs.filter { it.tmdbId != show.tmdbId || it.mediaType != show.mediaType }
        fillFavs()
        showNote(getString(if (on) R.string.note_added else R.string.note_removed))
        Worker.net.execute {
            val failed = runCatching { lists.set(Lists.FAVORITE, show, on) }.isFailure
            if (failed) main.post { showNote(getString(R.string.note_failed)) }
        }
    }

    private fun openOptions() {
        val show = focusedShow()
        val items = ArrayList<Menu.Item>()
        if (show != null) {
            items += Menu.Item(getString(R.string.menu_open)) { openTitle(show) }
            items += Menu.Item(getString(R.string.menu_trailer)) { playTrailer(show) }
            items += Menu.Item(getString(if (isFavorite(show)) R.string.menu_remove else R.string.menu_add)) { toggleFavorite(show) }
        } else {
            val app = focusedApp() ?: return
            items += Menu.Item(getString(R.string.menu_open)) { launch(app) }
            items += Menu.Item(getString(R.string.menu_move)) { setZone(Zone.MOVE) }
        }
        menu.open(items)
    }

    // ---- Apps ----

    private fun loadApps() {
        Worker.pool.execute {
            val fresh = apps.query()
            main.post {
                if (isDestroyed || fresh == all) return@post
                all = fresh
                apps.prune(fresh)
                fillApps()
            }
        }
    }

    /** Every app, in the order the user set, with new ones at the end. */
    private fun appList(): List<App> {
        val order = prefs.getString(ORDER, null)?.split('|').orEmpty().withIndex().associate { (i, key) -> key to i }
        return all.sortedBy { order[it.key] ?: Int.MAX_VALUE }
    }

    private fun fillApps() {
        appRack.set(
            appList().map { app ->
                Cell(this, Ui.px(APP_W), Ui.px(APP_H)).apply {
                    label.text = app.label
                    apps.load(app) { image.setImageBitmap(it) }
                }
            },
            keep = true,
        )
        paintAppLabel()
    }

    private fun focusedApp(): App? = if (zone == Zone.APPS) appList().getOrNull(appRack.focused) else null

    private fun endMove() {
        if (zone != Zone.MOVE) return
        setZone(Zone.APPS)
    }

    private fun launch(app: App) {
        val category = if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            loadApps()
        } catch (_: SecurityException) {
            loadApps()
        }
    }

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    // ---- Search ----

    private fun onKey(key: Keys.Key) {
        query = when (key.action) {
            Keys.Action.CHAR -> (query + key.label).take(MAX_QUERY)
            Keys.Action.SPACE -> if (query.isEmpty()) query else "$query "
            Keys.Action.DELETE -> query.dropLast(1)
            Keys.Action.CLEAR -> ""
        }
        searchQuery.text = query.ifEmpty { getString(R.string.search_placeholder) }
        searchQuery.alpha = if (query.isEmpty()) 0.55f else 1f
        main.removeCallbacks(runSearch)
        main.postDelayed(runSearch, SEARCH_PAUSE_MS)
    }

    private fun search() {
        val q = query.trim()
        val gen = ++searchGeneration
        if (q.isEmpty()) {
            showResults(emptyList(), message = null)
            return
        }
        Worker.net.execute {
            val list = runCatching { catalog.search(q) }.getOrNull()
            main.post {
                if (isDestroyed || gen != searchGeneration) return@post
                showResults(list.orEmpty(), getString(if (list == null) R.string.search_offline else R.string.search_none))
            }
        }
    }

    private fun showResults(list: List<Show>, message: String?) {
        found = list
        results.set(list.map { poster(it, RESULT_W, RESULT_H, label = true) }, keep = false)
        searchNone.text = message
        searchNone.visibility = if (list.isEmpty() && message != null) View.VISIBLE else View.GONE
        if (zone == Zone.SIDE && list.isEmpty()) setZone(Zone.MAIN)
    }

    // ---- Style ----

    private fun fillStyle() {
        val current = prefs.getInt(WALLPAPER, 0)
        val cells = Scenery.NAMES.indices.map { i ->
            Cell(this, Ui.px(WALL_W), Ui.px(WALL_H)).apply {
                mark.visibility = if (i == current) View.VISIBLE else View.GONE
                val path = Scenery.path(i)
                Worker.pool.execute {
                    val thumb = runCatching { com.shivansh.mytv.ui.Decode.asset(assets, path, Ui.px(WALL_W), Ui.px(WALL_H)) }.getOrNull()
                    if (thumb != null) main.post { image.setImageBitmap(thumb) }
                }
            }
        }
        walls.set(cells + wordsCell(getString(R.string.style_rotate), WALL_H, prefs.getBoolean(ROTATE, false)), keep = true)
        val face = prefs.getInt(FACE, 0)
        faces.set(
            listOf(R.string.face_contour, R.string.face_long, R.string.face_shapes, R.string.face_dial, R.string.face_serif).mapIndexed { i, name ->
                wordsCell(getString(name), FACE_H, i == face)
            },
            keep = true,
        )
    }

    private fun wordsCell(text: String, height: Int, marked: Boolean) = Cell(this, Ui.px(WALL_W), Ui.px(height)).apply {
        words.text = text
        words.visibility = View.VISIBLE
        words.typeface = resources.getFont(R.font.geist_medium)
        words.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(26f))
        mark.visibility = if (marked) View.VISIBLE else View.GONE
        image.visibility = View.GONE
    }

    private fun pickWallpaper() {
        val i = walls.focused
        if (i >= Scenery.NAMES.size) {
            val on = !prefs.getBoolean(ROTATE, false)
            prefs.edit().putBoolean(ROTATE, on).apply()
            walls.cell(i)?.mark?.visibility = if (on) View.VISIBLE else View.GONE
            scheduleRotate()
            return
        }
        setWallpaper(i, FADE_MS)
    }

    private fun setWallpaper(index: Int, fadeMs: Long) {
        walls.cell(prefs.getInt(WALLPAPER, 0))?.mark?.visibility = View.GONE
        walls.cell(index)?.mark?.visibility = View.VISIBLE
        prefs.edit().putInt(WALLPAPER, index).apply()
        scenery.wallpaper(index, fadeMs)
    }

    private fun pickFace() {
        faces.cell(prefs.getInt(FACE, 0))?.mark?.visibility = View.GONE
        faces.cell()?.mark?.visibility = View.VISIBLE
        prefs.edit().putInt(FACE, faces.focused).apply()
        clockFace.setFace(faces.focused)
    }

    /** With "Change by itself" on, a slow crossfade to the next wallpaper every few minutes. */
    private fun scheduleRotate() {
        main.removeCallbacks(rotate)
        if (resumed && prefs.getBoolean(ROTATE, false)) main.postDelayed(rotate, ROTATE_MS)
    }

    private fun nextWallpaper() {
        if (!heroShowing) setWallpaper((prefs.getInt(WALLPAPER, 0) + 1) % Scenery.NAMES.size, ROTATE_FADE_MS)
        scheduleRotate()
    }

    // ---- Network ----

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        paintNetwork(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
        val watch = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                runOnUiThread { if (!isDestroyed) paintNetwork(caps) }
            }

            override fun onLost(network: Network) {
                runOnUiThread { if (!isDestroyed) paintNetwork(null) }
            }
        }
        cm.registerDefaultNetworkCallback(watch)
        networkWatch = watch
    }

    private fun paintNetwork(caps: NetworkCapabilities?) {
        val (icon, label) = when {
            caps == null -> R.drawable.ic_wifi_off to R.string.network_offline
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.drawable.ic_ethernet to R.string.network_ethernet
            else -> R.drawable.ic_wifi to R.string.network_wifi
        }
        networkIcon.setImageResource(icon)
        networkLabel.setText(label)
    }

    // ---- TMDB set-up ----

    override fun onKeyConnected(key: String) {
        apiKey.save(key)
    }

    override fun onSignedIn(name: String) {
        showNote(getString(R.string.note_signed_in, name))
        tmdb.forget("account-")
        loadFavs(offline = false)
        paintScreen(animate = true)
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())

        const val ORDER = "app_order"
        const val WALLPAPER = "wallpaper"
        const val FACE = "face"
        const val ROTATE = "rotate"

        /** Layout px. */
        const val RACK_W = 168
        const val RACK_H = 252
        const val APP_W = 272
        const val APP_H = 153
        const val LABEL_H = 48
        const val POSTER_W = 200
        const val POSTER_H = 300
        const val RESULT_W = 184
        const val RESULT_H = 276
        const val WALL_W = 208
        const val WALL_H = 117
        const val FACE_H = 92

        const val GRID_COLS = 7
        const val RESULT_COLS = 5
        const val MAX_QUERY = 30
        const val HOLD_MS = 500L
        const val SEARCH_PAUSE_MS = 380L
        const val NOTE_MS = 2_400L
        const val REFRESH_MS = 5 * 60 * 1000L
        const val FADE_MS = 900L
        const val ROTATE_FADE_MS = 4_000L
        const val ROTATE_MS = 10 * 60 * 1000L
    }
}
