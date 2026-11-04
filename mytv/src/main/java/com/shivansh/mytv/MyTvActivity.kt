package com.shivansh.mytv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shivansh.mytv.ui.Cell
import com.shivansh.mytv.ui.Clock
import com.shivansh.mytv.ui.Keys
import com.shivansh.mytv.ui.Menu
import com.shivansh.mytv.ui.Motion
import com.shivansh.mytv.ui.Palette
import com.shivansh.mytv.ui.Tiles
import com.shivansh.mytv.ui.Ui
import com.shivansh.mytv.ui.Wallpaper
import com.shivansh.mytv.ui.Worker
import com.shivansh.mytv.ui.fadeTo

/**
 * MY TV, the lightest of the launchers: no network, no video, nothing moving on Home. A wallpaper,
 * a clock and a small row of favorites; Favorites to choose and order them; Search for apps on the
 * box; Style for the wallpaper and the clock face. The remote moves between the tabs and each
 * page's rows; holding OK on an app opens its options.
 */
class MyTvActivity : Activity() {
    private enum class Page { HOME, FAVORITES, SEARCH, STYLE }

    /** MAIN is the page's first thing (the row, grid, keyboard or wallpapers), SIDE its second. */
    private enum class Zone { TABS, MAIN, SIDE, MOVE }

    private lateinit var prefs: SharedPreferences
    private lateinit var apps: Apps
    private lateinit var clock: Clock
    private lateinit var wallpaper: Wallpaper
    private lateinit var menu: Menu
    private lateinit var homeRow: Tiles
    private lateinit var grid: Tiles
    private lateinit var results: Tiles
    private lateinit var walls: Tiles
    private lateinit var faces: Tiles
    private lateinit var keys: Keys

    private lateinit var scene: View
    private lateinit var homeRowView: View
    private lateinit var homeEmpty: View
    private lateinit var pages: Map<Page, View>
    private lateinit var tabViews: List<TextView>
    private lateinit var favsHint: TextView
    private lateinit var searchQuery: TextView
    private lateinit var searchNone: View

    private var all: List<App> = emptyList()
    private var favorites: MutableList<String> = ArrayList()
    private var found: List<App> = emptyList()
    private var gridApps: List<App> = emptyList()
    private var query = ""

    private var page = Page.HOME
    private var zone = Zone.TABS
    private var moveFrom = Zone.MAIN
    private var holding = false
    private var resumed = false
    private val main = Handler(Looper.getMainLooper())
    private val holdOk = Runnable {
        holding = false
        openOptions()
    }
    private val hideScene = Runnable { if (page == Page.FAVORITES || page == Page.SEARCH) scene.visibility = View.INVISIBLE }
    private val rotate = Runnable { nextWallpaper() }
    private val ticks = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = clock.tick()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        setContentView(R.layout.activity_mytv)
        prefs = getSharedPreferences("mytv", MODE_PRIVATE)
        apps = Apps(this, Ui.px(TILE_W), Ui.px(TILE_H))

        scene = findViewById(R.id.scene)
        homeRowView = findViewById(R.id.home_row)
        homeEmpty = findViewById(R.id.home_empty)
        pages = mapOf(
            Page.FAVORITES to findViewById<View>(R.id.page_favs),
            Page.SEARCH to findViewById<View>(R.id.page_search),
            Page.STYLE to findViewById<View>(R.id.page_style),
        )
        val tabs = findViewById<LinearLayout>(R.id.tabs)
        tabViews = (0 until tabs.childCount).map { tabs.getChildAt(it) as TextView }
        favsHint = findViewById(R.id.favs_hint)
        searchQuery = findViewById(R.id.search_query)
        searchNone = findViewById(R.id.search_none)

        clock = Clock(scene)
        wallpaper = Wallpaper(findViewById(R.id.wall_lower), findViewById(R.id.wall_upper), assets)
        menu = Menu(findViewById(R.id.menu))
        val cell = Ui.px(TILE_H + LABEL_H)
        homeRow = Tiles(homeRowView as FrameLayout, Ui.px(TILE_W), Ui.px(TILE_H), cell, Tiles.ROW)
        grid = Tiles(findViewById(R.id.favs_grid), Ui.px(TILE_W), Ui.px(TILE_H), cell, GRID_COLS, labelRest = 0.7f)
        results = Tiles(findViewById(R.id.search_results), Ui.px(TILE_W), Ui.px(TILE_H), cell, RESULT_COLS, labelRest = 0.7f, left = Ui.px(40))
        walls = Tiles(findViewById(R.id.style_walls), Ui.px(WALL_W), Ui.px(WALL_H), Ui.px(WALL_H), Tiles.ROW)
        faces = Tiles(findViewById(R.id.style_faces), Ui.px(WALL_W), Ui.px(FACE_H), Ui.px(FACE_H), Tiles.ROW)
        keys = findViewById(R.id.keys)
        keys.onKey = ::onKey

        favorites = prefs.getString(FAVORITES, null)?.split('|')?.filter { it.isNotEmpty() }?.toMutableList() ?: ArrayList()
        clock.setFace(prefs.getInt(FACE, 0))
        wallpaper.show(prefs.getInt(WALLPAPER, 0).coerceIn(0, Wallpaper.NAMES.size - 1), fadeMs = 300)
        fillStyle()
        setZone(Zone.TABS)
        paintTabs()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        registerReceiver(
            ticks,
            IntentFilter(Intent.ACTION_TIME_TICK).apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
        )
        clock.tick()
        loadApps()
        scheduleRotate()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        unregisterReceiver(ticks)
        main.removeCallbacks(rotate)
        main.removeCallbacks(holdOk)
        holding = false
        menu.close()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed: back to the wallpaper.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            endMove()
            showPage(Page.HOME)
            setZone(if (homeRow.size > 0) Zone.MAIN else Zone.TABS)
        }
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

    /** OK runs on release, so that holding it can open the options instead. */
    private fun ok(event: KeyEvent) {
        val app = focusedApp()
        if (zone == Zone.MOVE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) endMove()
            return
        }
        if (app == null) {
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
                launch(app)
            }
        }
    }

    private fun press() {
        when (zone) {
            Zone.TABS -> enterPage()
            Zone.MAIN -> when (page) {
                Page.SEARCH -> keys.press()
                Page.STYLE -> pickWallpaper()
                else -> Unit
            }
            Zone.SIDE -> if (page == Page.STYLE) pickFace()
            Zone.MOVE -> Unit
        }
    }

    private fun move(dRow: Int, dCol: Int) {
        when (zone) {
            Zone.TABS -> when {
                dCol != 0 -> Page.entries.getOrNull(page.ordinal + dCol)?.let(::showPage)
                dRow > 0 -> enterPage()
            }
            Zone.MOVE -> if (dCol != 0) reorder(dCol)
            Zone.MAIN -> when (page) {
                Page.HOME -> if (!homeRow.move(dRow, dCol) && dRow < 0) setZone(Zone.TABS)
                Page.FAVORITES -> if (!grid.move(dRow, dCol) && dRow < 0) setZone(Zone.TABS)
                Page.SEARCH -> if (!keys.move(dRow, dCol)) {
                    if (dRow < 0) setZone(Zone.TABS)
                    if (dCol > 0 && results.size > 0) setZone(Zone.SIDE)
                }
                Page.STYLE -> when {
                    dCol != 0 -> walls.move(0, dCol)
                    dRow > 0 -> setZone(Zone.SIDE)
                    dRow < 0 -> setZone(Zone.TABS)
                }
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

    /** Back climbs to the tabs, then Home; there it does nothing unless opened as an app. */
    private fun back(): Boolean {
        when {
            zone == Zone.MOVE -> endMove()
            zone == Zone.SIDE -> setZone(if (page == Page.SEARCH) Zone.MAIN else Zone.TABS)
            zone == Zone.MAIN && page == Page.HOME -> setZone(Zone.TABS)
            zone == Zone.MAIN -> setZone(Zone.TABS)
            page != Page.HOME -> showPage(Page.HOME)
            else -> return isDefaultHome()
        }
        return true
    }

    private fun setZone(next: Zone) {
        zone = next
        homeRow.lit = page == Page.HOME && (next == Zone.MAIN || next == Zone.MOVE)
        grid.lit = page == Page.FAVORITES && (next == Zone.MAIN || next == Zone.MOVE)
        keys.setLit(page == Page.SEARCH && next == Zone.MAIN)
        results.lit = page == Page.SEARCH && next == Zone.SIDE
        walls.lit = page == Page.STYLE && next == Zone.MAIN
        faces.lit = page == Page.STYLE && next == Zone.SIDE
        paintTabs()
    }

    private fun enterPage() {
        val hasMain = when (page) {
            Page.HOME -> homeRow.size > 0
            Page.FAVORITES -> grid.size > 0
            else -> true
        }
        if (hasMain) setZone(Zone.MAIN)
    }

    // ---- Tabs and pages ----

    private fun showPage(next: Page) {
        if (next == page) return
        pages[page]?.fadeTo(0f, 160)
        page = next
        main.removeCallbacks(hideScene)
        val solid = next == Page.FAVORITES || next == Page.SEARCH
        scene.visibility = View.VISIBLE
        if (solid) main.postDelayed(hideScene, 220)
        pages[next]?.let {
            it.alpha = 0f
            it.fadeTo(1f, 200)
        }
        paintHome()
        setZone(zone)
        scheduleRotate()
    }

    private fun paintTabs() {
        tabViews.forEachIndexed { i, tab ->
            val active = i == page.ordinal
            val pill = active && zone == Zone.TABS
            tab.background = if (pill) android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.px(32f)
                setColor(Palette.INK)
            } else {
                null
            }
            tab.setTextColor(if (pill) Palette.DARK else Palette.INK)
            tab.alpha = if (active) 1f else 0.62f
            val scale = if (pill) 1.06f else 1f
            tab.animate().scaleX(scale).scaleY(scale).setDuration(300).setStartDelay(0).setInterpolator(Motion.LIFT)
        }
    }

    private fun paintHome() {
        val home = page == Page.HOME
        homeRowView.visibility = if (home) View.VISIBLE else View.INVISIBLE
        homeEmpty.visibility = if (home && homeRow.size == 0) View.VISIBLE else View.GONE
    }

    // ---- Apps and favorites ----

    private fun loadApps() {
        Worker.pool.execute {
            val fresh = apps.query()
            main.post {
                if (isDestroyed || fresh == all) return@post
                val first = all.isEmpty()
                all = fresh
                apps.prune(fresh)
                val known = fresh.mapTo(HashSet()) { it.key }
                if (favorites.isEmpty() && first && !prefs.contains(FAVORITES)) {
                    // A first start: the first few TV apps, to be changed on the Favorites tab.
                    favorites = fresh.filter { it.leanback }.take(FIRST_FAVORITES).mapTo(ArrayList()) { it.key }
                }
                favorites.retainAll(known)
                saveFavorites()
                fillApps(keep = true)
                if (first && page == Page.HOME && homeRow.size > 0) setZone(Zone.MAIN)
            }
        }
    }

    private fun saveFavorites() = prefs.edit().putString(FAVORITES, favorites.joinToString("|")).apply()

    private fun favoriteApps(): List<App> {
        val byKey = all.associateBy { it.key }
        return favorites.mapNotNull { byKey[it] }
    }

    private fun fillApps(keep: Boolean) {
        val mine = favoriteApps()
        homeRow.set(mine.map { tile(it, mark = false) }, keep)
        gridApps = mine + all.filter { it.key !in favorites }
        grid.set(gridApps.map { tile(it, mark = it.key in favorites) }, keep)
        fillResults()
        paintHome()
        setZone(zone)
    }

    private fun tile(app: App, mark: Boolean) = Cell(this, Ui.px(TILE_W), Ui.px(TILE_H)).apply {
        label.text = app.label
        this.mark.visibility = if (mark) View.VISIBLE else View.GONE
        apps.load(app) { image.setImageBitmap(it) }
    }

    private fun focusedApp(): App? = when {
        page == Page.HOME && zone == Zone.MAIN -> favoriteApps().getOrNull(homeRow.focused)
        page == Page.FAVORITES && zone == Zone.MAIN -> gridApps.getOrNull(grid.focused)
        page == Page.SEARCH && zone == Zone.SIDE -> found.getOrNull(results.focused)
        else -> null
    }

    private fun openOptions() {
        val app = focusedApp() ?: return
        val saved = app.key in favorites
        val items = ArrayList<Menu.Item>()
        items += Menu.Item(getString(R.string.menu_open)) { launch(app) }
        items += Menu.Item(getString(if (saved) R.string.menu_remove else R.string.menu_add)) { toggleFavorite(app) }
        if (saved && page != Page.SEARCH) items += Menu.Item(getString(R.string.menu_move)) { startMove() }
        menu.open(items)
    }

    private fun toggleFavorite(app: App) {
        if (!favorites.remove(app.key)) favorites.add(app.key)
        saveFavorites()
        fillApps(keep = true)
        if (page == Page.HOME && homeRow.size == 0) setZone(Zone.TABS)
    }

    private fun startMove() {
        moveFrom = zone
        setZone(Zone.MOVE)
        if (page == Page.FAVORITES) favsHint.setText(R.string.move_hint)
    }

    private fun reorder(delta: Int) {
        val tiles = if (page == Page.HOME) homeRow else grid
        val from = tiles.focused
        if (!tiles.swap(delta, favoriteApps().size)) return
        val mine = favoriteApps().map { it.key }.toMutableList()
        java.util.Collections.swap(mine, from, tiles.focused)
        favorites = mine
    }

    private fun endMove() {
        if (zone != Zone.MOVE) return
        saveFavorites()
        favsHint.setText(R.string.favs_hint)
        fillApps(keep = true)
        setZone(moveFrom)
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
        fillResults()
    }

    /** Names that start with the query first, then names that contain it. Instant: it's all in memory. */
    private fun fillResults() {
        val q = query.trim().lowercase()
        found = if (q.isEmpty()) {
            all
        } else {
            all.filter { q in it.label.lowercase() }.sortedBy { !it.label.lowercase().startsWith(q) }
        }
        searchQuery.text = query.ifEmpty { getString(R.string.search_placeholder) }
        searchQuery.alpha = if (query.isEmpty()) 0.55f else 1f
        searchNone.visibility = if (found.isEmpty() && q.isNotEmpty()) View.VISIBLE else View.GONE
        results.set(found.map { tile(it, mark = false) }, keep = false)
    }

    // ---- Style ----

    private fun fillStyle() {
        val current = prefs.getInt(WALLPAPER, 0)
        val cells = Wallpaper.NAMES.indices.map { i ->
            Cell(this, Ui.px(WALL_W), Ui.px(WALL_H)).apply {
                mark.visibility = if (i == current) View.VISIBLE else View.GONE
                val path = Wallpaper.path(i)
                Worker.pool.execute {
                    val thumb = runCatching { Wallpaper.decode(assets, path, Ui.px(WALL_W), Ui.px(WALL_H)) }.getOrNull()
                    if (thumb != null) main.post { image.setImageBitmap(thumb) }
                }
            }
        }
        val rotates = wordsCell(getString(R.string.style_rotate), WALL_H, prefs.getBoolean(ROTATE, false))
        walls.set(cells + rotates, keep = true)
        val face = prefs.getInt(FACE, 0)
        faces.set(
            listOf(R.string.face_stacked, R.string.face_wide, R.string.face_dial, R.string.face_serif).mapIndexed { i, name ->
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
        if (i >= Wallpaper.NAMES.size) {
            val on = !prefs.getBoolean(ROTATE, false)
            prefs.edit().putBoolean(ROTATE, on).apply()
            walls.cell(i)?.mark?.visibility = if (on) View.VISIBLE else View.GONE
            scheduleRotate()
            return
        }
        walls.cell(prefs.getInt(WALLPAPER, 0))?.mark?.visibility = View.GONE
        walls.cell(i)?.mark?.visibility = View.VISIBLE
        prefs.edit().putInt(WALLPAPER, i).apply()
        wallpaper.show(i, FADE_MS)
    }

    private fun pickFace() {
        faces.cell(prefs.getInt(FACE, 0))?.mark?.visibility = View.GONE
        faces.cell()?.mark?.visibility = View.VISIBLE
        prefs.edit().putInt(FACE, faces.focused).apply()
        clock.setFace(faces.focused)
    }

    /** With "Change by itself" on, a slow crossfade to the next wallpaper every few minutes on Home. */
    private fun scheduleRotate() {
        main.removeCallbacks(rotate)
        if (resumed && page == Page.HOME && prefs.getBoolean(ROTATE, false)) main.postDelayed(rotate, ROTATE_MS)
    }

    private fun nextWallpaper() {
        val next = (prefs.getInt(WALLPAPER, 0) + 1) % Wallpaper.NAMES.size
        walls.cell(prefs.getInt(WALLPAPER, 0))?.mark?.visibility = View.GONE
        walls.cell(next)?.mark?.visibility = View.VISIBLE
        prefs.edit().putInt(WALLPAPER, next).apply()
        wallpaper.show(next, ROTATE_FADE_MS)
        scheduleRotate()
    }

    private companion object {
        const val FAVORITES = "favorites"
        const val WALLPAPER = "wallpaper"
        const val FACE = "face"
        const val ROTATE = "rotate"

        /** Layout px. */
        const val TILE_W = 272
        const val TILE_H = 153
        const val LABEL_H = 48
        const val WALL_W = 208
        const val WALL_H = 117
        const val FACE_H = 92

        const val GRID_COLS = 5
        const val RESULT_COLS = 3
        const val FIRST_FAVORITES = 5
        const val MAX_QUERY = 30
        const val HOLD_MS = 500L
        const val FADE_MS = 900L
        const val ROTATE_FADE_MS = 4_000L
        const val ROTATE_MS = 10 * 60 * 1000L
    }
}
