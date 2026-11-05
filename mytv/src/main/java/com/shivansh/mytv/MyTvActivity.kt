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
import android.view.Gravity
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
import com.shivansh.mytv.ui.GlassDrawable
import com.shivansh.mytv.ui.Hero
import com.shivansh.mytv.ui.ImageFiles
import com.shivansh.mytv.ui.Keys
import com.shivansh.mytv.ui.Link
import com.shivansh.mytv.ui.Menu
import com.shivansh.mytv.ui.Motion
import com.shivansh.mytv.ui.Palette
import com.shivansh.mytv.ui.Pill
import com.shivansh.mytv.ui.Posters
import com.shivansh.mytv.ui.Qr
import com.shivansh.mytv.ui.QrCard
import com.shivansh.mytv.ui.Remote
import com.shivansh.mytv.ui.RoundOutline
import com.shivansh.mytv.ui.Scenery
import com.shivansh.mytv.ui.Tiles
import com.shivansh.mytv.ui.Ui
import com.shivansh.mytv.ui.Worker
import com.shivansh.mytv.ui.fadeTo
import java.time.LocalDateTime
import java.security.SecureRandom

/**
 * MY TV. A floating bar of tabs over Home, laid out like Apple TV: a large clock on the wallpaper
 * above one row of your favorite apps, and an All tile that reveals the rest below that row. One
 * press down brings your favorite films and shows, with the focused one's backdrop filling the
 * screen. Favorites holds them all, Search finds films and shows on TMDB, and Style sets the
 * wallpaper and clock, from the remote or from a page on your phone.
 */
class MyTvActivity : Activity(), Link.Listener {
    private enum class Page { HOME, FAVORITES, SEARCH, STYLE }

    /** Where the remote is. MAIN and SIDE are a page's first and second things. */
    private enum class Zone { BAR, CORNER, FAVS, APPS, ALL, MAIN, SIDE, MOVE }

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
    private lateinit var allRack: Tiles
    private lateinit var grid: Tiles
    private lateinit var results: Tiles
    private lateinit var walls: Tiles
    private lateinit var faces: Tiles
    private lateinit var keys: Keys

    private lateinit var clockFace: ClockFace
    private lateinit var clockGroup: View
    private lateinit var heroView: View
    private lateinit var favRackView: View
    private lateinit var appRackView: View
    private lateinit var appsLift: View
    private lateinit var dock: View
    private lateinit var allSheet: View
    private lateinit var depthGroup: View
    private lateinit var phoneCard: View
    private lateinit var phoneCode: ImageView
    private lateinit var phoneLink: TextView
    private lateinit var remote: Remote
    private lateinit var pages: Map<Page, View>
    private lateinit var scene: View
    private lateinit var pageGlass: View
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
    private var zone = Zone.APPS

    /** Home's films, below the apps. */
    private var favsShowing = false
    private var heroShowing = false

    /** The rest of the apps, revealed below the first row. */
    private var allOpen = false
    private var allShown = false
    private var allLift = 0f
    private var phoneOpen = false
    private var phoneUrl: String? = null
    private var holding = false
    private var lastStep = 0L
    private val floorOn = HashMap<View, Boolean>()
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
        pageGlass = findViewById(R.id.page_glass)
        clockFace = findViewById(R.id.clock)
        clockGroup = findViewById(R.id.clock_group)
        heroView = findViewById(R.id.hero)
        favRackView = findViewById(R.id.rack_favs)
        appRackView = findViewById(R.id.rack_apps)
        appsLift = findViewById(R.id.apps_lift)
        dock = findViewById(R.id.dock)
        allSheet = findViewById(R.id.all_sheet)
        depthGroup = findViewById(R.id.depth_group)
        phoneCard = findViewById(R.id.phone_card)
        phoneCode = findViewById(R.id.phone_code)
        phoneLink = findViewById(R.id.phone_link)
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

        Scenery.scan(assets)
        scenery = Scenery(findViewById(R.id.wall_lower), findViewById(R.id.wall_upper), findViewById(R.id.depth), assets)
        hero = Hero(heroView, posters)
        link = Link(QrCard(findViewById(R.id.qr)), hero, tmdb, apiKey, account, this)
        menu = Menu(findViewById(R.id.menu))
        bar = Bar(findViewById<LinearLayout>(R.id.tabs), findViewById<Pill>(R.id.bar_pill))
        corner = Corner(listOf(findViewById(R.id.corner_network), findViewById(R.id.corner_settings)))
        favRack = Tiles(favRackView as FrameLayout, Ui.px(RACK_W), Ui.px(RACK_H), Ui.px(RACK_H), Tiles.ROW, top = Ui.px(40))
        appRack = Tiles(findViewById(R.id.dock), Ui.px(APP_W), Ui.px(APP_H), Ui.px(APP_H), Tiles.ROW, left = Ui.px(36), top = Ui.px(36), lift = 1.14f)
        allRack = Tiles(findViewById(R.id.all_grid), Ui.px(APP_W), Ui.px(APP_H), Ui.px(APP_H + LABEL_H), ALL_COLS, left = Ui.px(36), top = Ui.px(16), lift = 1.14f)
        grid = Tiles(findViewById(R.id.favs_grid), Ui.px(POSTER_W), Ui.px(POSTER_H), Ui.px(POSTER_H + LABEL_H), GRID_COLS, labelRest = 0.7f)
        results = Tiles(findViewById(R.id.search_results), Ui.px(RESULT_W), Ui.px(RESULT_H), Ui.px(RESULT_H + LABEL_H), RESULT_COLS, labelRest = 0.7f, left = Ui.px(40))
        walls = Tiles(findViewById(R.id.style_walls), Ui.px(WALL_W), Ui.px(WALL_H), Ui.px(WALL_H), Tiles.ROW, left = Ui.px(36), top = Ui.px(16))
        faces = Tiles(findViewById(R.id.style_faces), Ui.px(WALL_W), Ui.px(FACE_H), Ui.px(FACE_H), Tiles.ROW, left = Ui.px(36), top = Ui.px(16))
        keys = findViewById(R.id.keys)
        keys.onKey = ::onKey

        glass()
        clockFace.setFace(prefs.getInt(FACE, 0).coerceIn(0, ClockFace.COUNT - 1))
        fillStyle()
        watchNetwork()
        remote = Remote(assets, phoneSecret(), faceNames(), { wallIndex() to prefs.getInt(FACE, 0) }, ::phoneWallpaper, ::phoneFace)
        appRack.lit = zone == Zone.APPS
        paintScreen(animate = false)
        loadApps()
        loadFavs(offline = true)
    }

    /** Frosted panels: the bar, the corner, the dock, the Style sheet, the note and the page behind lists. */
    private fun glass() {
        fun frost(id: Int, radius: Float, tint: Int, edge: Boolean = true, selectable: Boolean = false) {
            val view = findViewById<View>(id)
            view.background = GlassDrawable(view, radius, tint, edge, selectable)
        }
        frost(R.id.bar_frame, Ui.px(44f), 0x33101218)
        frost(R.id.corner_network, Ui.px(32f), 0x33101218, selectable = true)
        frost(R.id.corner_settings, Ui.px(32f), 0x33101218, selectable = true)
        frost(R.id.dock, Ui.px(48f), 0x26101218)
        frost(R.id.all_sheet, Ui.px(48f), 0x4D101218)
        frost(R.id.style_sheet, Ui.px(48f), 0x4D101218)
        frost(R.id.phone_card, Ui.px(48f), 0x73101218)
        frost(R.id.note, Ui.px(40f), 0x66101218)
        frost(R.id.page_glass, 0f, 0x8C0B0D12.toInt(), edge = false)
        dock.outlineProvider = RoundOutline(Ui.px(48f))
        dock.clipToOutline = true
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
        phoneUrl = remote.start()
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
        remote.stop()
        closePhone()
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
            favsShowing = false
            allOpen = false
            showPage(Page.HOME)
            setZone(Zone.APPS)
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
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) step(event, 0, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) step(event, 0, 1)
            KeyEvent.KEYCODE_DPAD_UP -> if (down) step(event, -1, 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) step(event, 1, 0)
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

    /** A held direction repeats at a steady pace the eye can follow, not as fast as the remote sends it. */
    private fun step(event: KeyEvent, dRow: Int, dCol: Int) {
        val now = SystemClock.uptimeMillis()
        if (event.repeatCount > 0 && now - lastStep < REPEAT_MS) return
        lastStep = now
        move(dRow, dCol)
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
            Zone.APPS -> setAll(!allOpen)
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
                if (faces.focused >= ClockFace.COUNT) togglePhone() else pickFace()
            }
            else -> Unit
        }
    }

    private fun move(dRow: Int, dCol: Int) {
        closePhone()
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
            Zone.APPS -> when {
                dCol != 0 -> if (appRack.move(0, dCol)) paintAppLabel() else appRack.nudge(0, dCol)
                dRow < 0 -> setZone(Zone.BAR)
                dRow > 0 -> when {
                    allOpen -> if (allRack.size > 0) setZone(Zone.ALL) else appRack.nudge(dRow, 0)
                    else -> {
                        favsShowing = true
                        setZone(Zone.FAVS)
                    }
                }
            }
            Zone.FAVS -> when {
                dCol != 0 -> if (favRack.move(0, dCol)) paintFocus() else favRack.nudge(0, dCol)
                dRow < 0 -> {
                    favsShowing = false
                    setZone(Zone.APPS)
                }
                dRow > 0 -> favRack.nudge(dRow, 0)
            }
            Zone.ALL -> if (!allRack.move(dRow, dCol)) {
                if (dRow < 0) setZone(Zone.APPS) else allRack.nudge(dRow, dCol)
            }
            Zone.MOVE -> if (dCol != 0) {
                val from = appRack.focused
                val row = rowApps().toMutableList()
                if (from + dCol in row.indices && appRack.swap(dCol)) {
                    java.util.Collections.swap(row, from, appRack.focused)
                    saveRow(row)
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

    /** Back climbs: to the first row, the bar, then Home; there it does nothing unless opened as an app. */
    private fun back(): Boolean {
        if (phoneOpen) {
            closePhone()
            return true
        }
        when (zone) {
            Zone.MOVE -> endMove()
            Zone.CORNER -> leaveCorner()
            Zone.ALL -> setAll(false)
            Zone.APPS -> if (allOpen) setAll(false) else setZone(Zone.BAR)
            Zone.FAVS -> {
                favsShowing = false
                setZone(Zone.APPS)
            }
            Zone.MAIN -> setZone(Zone.BAR)
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
        allRack.lit = next == Zone.ALL
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
        closePhone()
        allOpen = false
        pages[page]?.fadeTo(0f, 180)
        page = next
        main.removeCallbacks(hideScene)
        scene.visibility = View.VISIBLE
        val solid = next == Page.FAVORITES || next == Page.SEARCH
        pageGlass.fadeTo(if (solid) 1f else 0f, 260)
        if (solid) main.postDelayed(hideScene, 320)
        pages[next]?.let {
            it.alpha = 0f
            it.fadeTo(1f, 240)
        }
        setZone(zone)
        scheduleRotate()
    }

    /** Fits everything to the page and rack: the title or the clock, and which rack. */
    private fun paintScreen(animate: Boolean) {
        bar.set(page.ordinal, zone == Zone.BAR)
        val home = page == Page.HOME
        val wantHero = home && favsShowing
        val wantApps = home && !favsShowing
        val wantClock = (wantApps && !allOpen) || page == Page.STYLE
        heroShowing = wantHero
        val travel = Ui.px(FLOOR_PX)
        floor(favRackView, wantHero, travel, animate)
        floor(heroView, wantHero, travel / 3f, animate)
        floor(appRackView, wantApps, -travel, animate)
        floor(allSheet, wantApps && allOpen, travel, animate)
        // Opening All, the clock clears out fast and returns late, so the GPU isn't blending it
        // under the sheet the whole way: on this box that is the difference between 60 and 30 fps.
        val swap = allOpen != allShown
        allShown = allOpen
        val clockBy = if (swap) 0f else -travel / 2f
        val clockMs = if (swap) QUICK_MS else FLOOR_MS
        val clockDelay = if (swap && wantClock) RETURN_MS else 0L
        floor(clockGroup, wantClock, clockBy, animate, clockMs, clockDelay)
        floor(depthGroup, wantClock && prefs.getInt(FACE, 0) == ClockFace.GLASS, clockBy, animate, clockMs, clockDelay)
        liftApps(animate)
        if (wantHero) {
            if (link.needed && resumed) link.start() else link.stop()
            paintFocus()
        } else {
            link.stop()
            scenery.wallpaper(wallIndex(), fadeMs = 700)
        }
    }

    /** With All open, the first row rises to make room for the rest below it. */
    private fun liftApps(animate: Boolean) {
        val target = if (allOpen) -allLift else 0f
        if (appsLift.translationY == target) return
        if (!animate) {
            appsLift.translationY = target
            return
        }
        appsLift.animate().translationY(target).setDuration(Motion.ms(FLOOR_MS)).setStartDelay(0).setInterpolator(Motion.SETTLE)
            .withEndAction { dock.invalidate() }
    }

    private fun setAll(open: Boolean) {
        if (open && allRack.size == 0) {
            showNote(getString(R.string.all_none))
            return
        }
        allOpen = open
        paintAppLabel()
        setZone(if (open) Zone.ALL else Zone.APPS)
    }

    /** One part of the page arriving or leaving by [by], all parts on one curve, like one scroll. */
    private fun floor(view: View, on: Boolean, by: Float, animate: Boolean, ms: Long = FLOOR_MS, delay: Long = 0) {
        if (floorOn[view] == on) return
        floorOn[view] = on
        view.animate().cancel()
        if (!animate) {
            view.alpha = if (on) 1f else 0f
            view.translationY = 0f
            view.visibility = if (on) View.VISIBLE else View.INVISIBLE
            return
        }
        if (on && view.alpha == 0f) view.translationY = by
        view.visibility = View.VISIBLE
        view.animate().translationY(if (on) 0f else by).alpha(if (on) 1f else 0f)
            .setDuration(Motion.ms(ms)).setStartDelay(Motion.ms(delay)).setInterpolator(Motion.SETTLE)
            .withEndAction { if (floorOn[view] == false) view.visibility = View.INVISIBLE }
    }

    private fun paintFocus() {
        if (link.needed) return
        val show = favs.getOrNull(favRack.focused)
        if (show == null) {
            hero.show(getString(R.string.tab_favorites), getString(R.string.favs_empty_title), "", getString(R.string.favs_empty_line), null)
            scenery.wallpaper(wallIndex(), fadeMs = 700)
            return
        }
        hero.title(show, Hero.kickerFor(show, getString(R.string.kicker_film), getString(R.string.kicker_series)))
        scenery.backdrop(show.backdrop)
    }

    private fun paintAppLabel() {
        val label = findViewById<TextView>(R.id.rack_apps_label)
        label.text = when {
            allOpen -> ""
            else -> rowApps().getOrNull(appRack.focused)?.label ?: getString(R.string.all_apps)
        }
    }

    private fun paintClock() {
        val now = LocalDateTime.now()
        val h24 = DateFormat.is24HourFormat(this)
        val hour = if (h24) "%02d".format(now.hour) else ((now.hour + 11) % 12 + 1).toString()
        clockFace.set(now, hour, if (h24) "" else if (now.hour < 12) "AM" else "PM")
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
        zone == Zone.ALL -> allRack
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
            if (zone == Zone.ALL) {
                items += Menu.Item(getString(R.string.menu_row_add)) { saveRow(rowApps() + app) }
            } else {
                items += Menu.Item(getString(R.string.menu_move)) { setZone(Zone.MOVE) }
                items += Menu.Item(getString(R.string.menu_row_remove)) { saveRow(rowApps() - app) }
            }
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

    /** The first row: the apps chosen for it, in their order; until then, the usual streaming apps. */
    private fun rowApps(): List<App> {
        val saved = prefs.getString(ROW, null)
        if (saved != null) return saved.split('|').mapNotNull { key -> all.firstOrNull { it.key == key } }
        val picked = DEFAULT_ROW.mapNotNull { name -> all.firstOrNull { it.component.packageName == name } }
        return (picked + all.filter { it !in picked }).take(ROW_SIZE)
    }

    /** Everything else, for All. */
    private fun restApps(): List<App> {
        val row = rowApps()
        return all.filter { it !in row }
    }

    private fun saveRow(row: List<App>) {
        prefs.edit().putString(ROW, row.joinToString("|") { it.key }).apply()
        if (zone != Zone.MOVE) fillApps()
    }

    private fun fillApps() {
        appRack.set(rowApps().map(::appCell) + allCell(), keep = true)
        allRack.set(restApps().map(::appCell), keep = true)
        fitAll()
        paintAppLabel()
    }

    /** The sheet only as tall as its rows, and the first row sitting just above it. */
    private fun fitAll() {
        val rows = ((allRack.size + ALL_COLS - 1) / ALL_COLS).coerceIn(1, ALL_ROWS)
        val height = Ui.px(ALL_HEAD + rows * (APP_H + LABEL_H) + (rows - 1) * 36)
        allSheet.layoutParams = allSheet.layoutParams.apply { this.height = height }
        allLift = height + Ui.px(4f)
        if (allOpen) appsLift.translationY = -allLift
    }

    private fun appCell(app: App) = Cell(this, Ui.px(APP_W), Ui.px(APP_H)).apply {
        label.text = app.label
        apps.load(app) { image.setImageBitmap(it) }
    }

    private fun allCell() = Cell(this, Ui.px(APP_W), Ui.px(APP_H)).apply {
        image.scaleType = ImageView.ScaleType.CENTER
        image.setImageResource(R.drawable.ic_all)
        image.translationY = -Ui.px(14f)
        words.text = getString(R.string.all_apps)
        words.visibility = View.VISIBLE
        words.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        words.setPadding(0, 0, 0, Ui.px(16))
        words.typeface = resources.getFont(R.font.geist_medium)
        words.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(24f))
    }

    private fun focusedApp(): App? = when (zone) {
        Zone.APPS -> rowApps().getOrNull(appRack.focused)
        Zone.ALL -> restApps().getOrNull(allRack.focused)
        else -> null
    }

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
        val current = wallIndex()
        val cells = Scenery.NAMES.indices.map { i ->
            Cell(this, Ui.px(WALL_W), Ui.px(WALL_H)).apply {
                mark.visibility = if (i == current) View.VISIBLE else View.GONE
                val path = Scenery.thumb(i)
                Worker.pool.execute {
                    val thumb = runCatching { com.shivansh.mytv.ui.Decode.asset(assets, path, Ui.px(WALL_W), Ui.px(WALL_H)) }.getOrNull()
                    if (thumb != null) main.post { image.setImageBitmap(thumb) }
                }
            }
        }
        walls.set(cells + wordsCell(getString(R.string.style_rotate), WALL_H, prefs.getBoolean(ROTATE, false)), keep = true)
        val face = prefs.getInt(FACE, 0)
        faces.set(
            faceNames().mapIndexed { i, name -> wordsCell(name, FACE_H, i == face) } + wordsCell(getString(R.string.style_phone), FACE_H, false),
            keep = true,
        )
    }

    private fun faceNames() = listOf(
        R.string.face_glass, R.string.face_flip, R.string.face_moon, R.string.face_weekday, R.string.face_halo, R.string.face_serif,
    ).map(::getString)

    private fun wallIndex() = prefs.getInt(WALLPAPER, 0).coerceIn(0, (Scenery.NAMES.size - 1).coerceAtLeast(0))

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
        walls.cell(wallIndex())?.mark?.visibility = View.GONE
        walls.cell(index)?.mark?.visibility = View.VISIBLE
        prefs.edit().putInt(WALLPAPER, index).apply()
        scenery.wallpaper(index, fadeMs)
    }

    private fun pickFace() = setFace(faces.focused)

    private fun setFace(index: Int) {
        faces.cell(prefs.getInt(FACE, 0))?.mark?.visibility = View.GONE
        faces.cell(index)?.mark?.visibility = View.VISIBLE
        prefs.edit().putInt(FACE, index).apply()
        clockFace.setFace(index)
        paintScreen(animate = true)
    }

    // ---- The phone ----

    /** The page's private address, the same each time so it can be kept as a bookmark. */
    private fun phoneSecret(): String {
        prefs.getString(PHONE, null)?.let { return it }
        val letters = "abcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        val code = (1..12).map { letters[random.nextInt(letters.length)] }.joinToString("")
        prefs.edit().putString(PHONE, code).apply()
        return code
    }

    private fun togglePhone() {
        if (phoneOpen) {
            closePhone()
            return
        }
        val url = phoneUrl
        if (url == null) {
            showNote(getString(R.string.phone_offline))
            return
        }
        phoneCode.setImageBitmap(Qr.code(url))
        phoneLink.text = url.removePrefix("http://").substringBeforeLast('/')
        phoneOpen = true
        phoneCard.fadeTo(1f, 240)
    }

    private fun closePhone() {
        if (!phoneOpen) return
        phoneOpen = false
        phoneCard.fadeTo(0f, 180)
    }

    /** A wallpaper from the phone: shown at once, so leave the films if they're up. */
    private fun phoneWallpaper(index: Int) {
        if (index !in Scenery.NAMES.indices) return
        if (page == Page.HOME && favsShowing) {
            favsShowing = false
            setZone(Zone.APPS)
        }
        setWallpaper(index, FADE_MS)
    }

    private fun phoneFace(index: Int) {
        if (index in 0 until ClockFace.COUNT) setFace(index)
    }

    /** With "Change by itself" on, a slow crossfade to the next wallpaper every few minutes. */
    private fun scheduleRotate() {
        main.removeCallbacks(rotate)
        if (resumed && prefs.getBoolean(ROTATE, false)) main.postDelayed(rotate, ROTATE_MS)
    }

    private fun nextWallpaper() {
        if (!heroShowing && Scenery.NAMES.isNotEmpty()) setWallpaper((wallIndex() + 1) % Scenery.NAMES.size, ROTATE_FADE_MS)
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
        /** The first row until it's changed: Android TV's usual streaming apps, if installed. */
        val DEFAULT_ROW = listOf(
            "com.google.android.youtube.tv", "com.google.android.youtube.tvmusic", "com.amazon.amazonvideo.livingroom",
            "in.startv.hotstar", "app.netmirror.newtv",
        )
        const val ROW_SIZE = 5

        const val ROW = "app_row"
        const val WALLPAPER = "wallpaper"
        const val FACE = "clock_face"
        const val ROTATE = "rotate"
        const val PHONE = "phone_code"

        /** Layout px. */
        const val RACK_W = 172
        const val RACK_H = 258
        const val APP_W = 256
        const val APP_H = 144
        const val LABEL_H = 64
        const val POSTER_W = 200
        const val POSTER_H = 300
        const val RESULT_W = 184
        const val RESULT_H = 276
        const val WALL_W = 208
        const val WALL_H = 117
        const val FACE_H = 92

        const val ALL_COLS = 6
        const val ALL_ROWS = 2
        const val ALL_HEAD = 92
        const val QUICK_MS = 200L
        const val RETURN_MS = 360L
        const val GRID_COLS = 7
        const val RESULT_COLS = 5
        const val MAX_QUERY = 30
        const val HOLD_MS = 500L
        const val REPEAT_MS = 150L
        const val FLOOR_MS = 620L
        const val FLOOR_PX = 300f
        const val SEARCH_PAUSE_MS = 380L
        const val NOTE_MS = 2_400L
        const val REFRESH_MS = 5 * 60 * 1000L
        const val FADE_MS = 900L
        const val ROTATE_FADE_MS = 4_000L
        const val ROTATE_MS = 10 * 60 * 1000L
    }
}
