package com.shivansh.mibox

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.view.KeyEvent
import android.view.SurfaceView
import android.graphics.Rect
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shivansh.mibox.data.AppEntry
import com.shivansh.mibox.data.AppTile
import com.shivansh.mibox.data.Demo
import com.shivansh.mibox.data.InstalledApps
import com.shivansh.mibox.data.Provider
import com.shivansh.mibox.data.Show
import com.shivansh.mibox.ui.AppArtSource
import com.shivansh.mibox.ui.Backdrop
import com.shivansh.mibox.ui.FocusCard
import com.shivansh.mibox.ui.Fonts
import com.shivansh.mibox.ui.Gap
import com.shivansh.mibox.ui.Glass
import com.shivansh.mibox.ui.Hero
import com.shivansh.mibox.ui.Images
import com.shivansh.mibox.ui.LinearScrim
import com.shivansh.mibox.ui.Motion
import com.shivansh.mibox.ui.Navigator
import com.shivansh.mibox.ui.Palette
import com.shivansh.mibox.ui.Previews
import com.shivansh.mibox.ui.RowLayoutManager
import com.shivansh.mibox.ui.Shelf
import com.shivansh.mibox.ui.TabBar
import com.shivansh.mibox.ui.Ui
import com.shivansh.mibox.ui.Workers
import com.shivansh.mibox.ui.fadeTo
import com.shivansh.mibox.ui.glideY
import com.shivansh.mibox.ui.rows.AppsAdapter
import com.shivansh.mibox.ui.rows.FavoritesAdapter
import com.shivansh.mibox.ui.rows.PosterAdapter
import com.shivansh.mibox.ui.rows.UpNextAdapter

/**
 * The home screen: a hero banner of the user's shows, the app icons, then Up Next, My Shows and
 * Favorites, with muted previews playing behind whatever has focus. The D-pad is handled here and
 * handed to [Navigator]; rows and cards never move focus on their own.
 */
class HomeActivity : Activity(), Previews.Listener {
    private lateinit var images: Images
    private lateinit var appArt: AppArtSource
    private lateinit var installed: InstalledApps
    private lateinit var backdrop: Backdrop
    private lateinit var previews: Previews
    private lateinit var hero: Hero
    private lateinit var shelf: Shelf
    private lateinit var nav: Navigator

    private lateinit var content: View
    private lateinit var heroView: View
    private lateinit var shelfView: View
    private lateinit var chrome: View
    private lateinit var scrimLeft: View
    private lateinit var scrimBottom: View
    private lateinit var scrimTop: View
    private lateinit var dim: View
    private lateinit var chip: View
    private lateinit var chipText: TextView
    private lateinit var appsRow: RecyclerView
    private lateinit var scroll: SpringAnimation
    private lateinit var tabBar: TabBar
    private lateinit var pageApps: View
    private lateinit var pageSoon: View
    private lateinit var appsGrid: RecyclerView
    private lateinit var gridAdapter: AppsAdapter

    private lateinit var appsAdapter: AppsAdapter
    private var apps: List<AppEntry> = emptyList()

    private var resumed = false
    private var lastMoveAt = 0L
    private var backConsumed = false
    private var ambient = false
    private var restDim = 0f
    private var previewDim = 0f
    private val main = Handler(Looper.getMainLooper())
    private val toAmbient = Runnable { enterAmbient() }

    /** Where the remote's focus is: the home rows, the tab bar, or another tab's page. */
    private enum class Area { HOME, TABS, PAGE }

    private var area = Area.HOME
    private var page = Page.HOME
    private var gridFocus = 0

    /** Resting on a tab for a moment switches to its page, as on tvOS. */
    private val pageSettle = Runnable { showPage(tabBar.focused) }
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Fonts.init(this)
        setContentView(R.layout.activity_home)

        images = Images(assets)
        appArt = AppArtSource(this)
        installed = InstalledApps(this)

        content = findViewById(R.id.content)
        heroView = findViewById(R.id.hero)
        shelfView = findViewById(R.id.shelf)
        chrome = findViewById(R.id.chrome)
        scrimLeft = findViewById(R.id.scrim_left)
        scrimBottom = findViewById(R.id.scrim_bottom)
        scrimTop = findViewById(R.id.scrim_top)
        dim = findViewById(R.id.dim)
        chip = findViewById(R.id.preview_chip)
        chipText = findViewById(R.id.preview_chip_text)
        shelfView.translationY = Ui.px(40f)

        scrimLeft.background = LinearScrim(90f, shades(0.88f, 0.55f, 0f), floatArrayOf(0f, 0.5f, 1f))
        scrimBottom.background = LinearScrim(0f, shades(0.92f, 0.6f, 0f), floatArrayOf(0f, 0.44f, 1f))
        scrimTop.background = LinearScrim(180f, shades(0.55f, 0f), floatArrayOf(0f, 1f))
        tabBar = findViewById(R.id.tabs)
        tabBar.background = Glass.control(Ui.px(60f), Palette.alpha(0xFFFFFF, 0.1f))
        chip.background = Glass.control(Ui.px(48f), Palette.alpha(Palette.NIGHT, 0.45f))
        pageApps = findViewById(R.id.page_apps)
        pageSoon = findViewById(R.id.page_soon)
        findViewById<View>(R.id.page_soon_badge).background = Glass.control(Ui.px(120f))
        findViewById<View>(R.id.apps_fade).background = LinearScrim(180f, shades(0f, 0.85f, 0f), floatArrayOf(0f, 0.53f, 1f))

        // Rows ease into place on a spring: quick to start, no overshoot, and a new press mid-way
        // carries the motion on instead of restarting it.
        scroll = SpringAnimation(content, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring.stiffness = 220f
            spring.dampingRatio = 0.95f
        }

        backdrop = Backdrop(findViewById(R.id.backdrop), findViewById(R.id.slot_lower), findViewById(R.id.slot_upper), assets) {
            // The backdrop covers the screen from here on; the window background would be pure overdraw.
            window.setBackgroundDrawable(null)
        }
        previews = Previews(findViewById<SurfaceView>(R.id.preview_surface), assets, this)

        hero = Hero(heroView, Demo.hero) { slide ->
            if (nav.row == 0 && page == Page.HOME) {
                backdrop.show(slide.show.backdrop)
                previews.cue(slide.show.clip, HERO_PREVIEW_MS)
            }
        }
        hero.playButton.setOnClickListener { openProvider(hero.current.show.provider, it) }
        shelf = Shelf(shelfView)

        appsAdapter = AppsAdapter(appArt).apply { onActivate = { tile, card -> openTile(tile, card) } }
        val upNext = UpNextAdapter(images).apply {
            items = Demo.upNext
            onActivate = { item, card -> openProvider(item.show.provider, card) }
        }
        val myShows = PosterAdapter(images).apply {
            items = Demo.myShows
            onActivate = { show, card -> openProvider(show.provider, card) }
        }
        val favorites = FavoritesAdapter(images).apply {
            items = Demo.favorites
            onActivate = { show, card -> openProvider(show.provider, card) }
        }

        appsRow = row(R.id.row_apps, appsAdapter, gap = 36)
        gridAdapter = AppsAdapter(appArt).apply { onActivate = { tile, card -> openTile(tile, card) } }
        appsGrid = findViewById<RecyclerView>(R.id.apps_grid).apply {
            layoutManager = GridLayoutManager(context, GRID_COLUMNS)
            adapter = gridAdapter
            itemAnimator = null
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    outRect.set(0, 0, 0, Ui.px(26))
                }
            })
            drawFocusedLast()
        }
        nav = Navigator(
            arrayOf(
                null,
                Navigator.Row(appsRow, keep = 3, step = 310 + 36),
                Navigator.Row(row(R.id.row_up_next, upNext, gap = 40), keep = 2, step = 416 + 40),
                Navigator.Row(row(R.id.row_my_shows, myShows, gap = 36), keep = 4, step = 232 + 36),
                Navigator.Row(row(R.id.row_favorites, favorites, gap = 40), keep = 1, step = 560 + 40),
            ),
            focusHero = {
                heroView.visibility = View.VISIBLE
                hero.playButton.requestFocus()
            },
            onMove = ::onMove,
        )

        hero.bind(0, entrance = true)
        backdrop.show(hero.current.show.backdrop)

        apps = installed.cached()
        showApps()
        refreshApps()
        launcherApps.registerCallback(appsCallback, Handler(Looper.getMainLooper()))

        content.post { nav.go(0, 0, animate = false) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hero.setRunning(nav.row == 0 && page == Page.HOME)
        if (page == Page.HOME) {
            if (area == Area.HOME) nav.refocus() else onMove(nav.row, nav.col, rowChanged = false)
        }
        when (area) {
            Area.TABS -> tabBar.requestFocus()
            Area.PAGE -> focusGrid(gridFocus, View.FOCUS_DOWN)
            Area.HOME -> Unit
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        hero.setRunning(false)
        main.removeCallbacks(toAmbient)
        exitAmbient()
        previews.release()
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(appsCallback)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: back to the top.
        val alreadyHome = hasWindowFocus() && (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        if (alreadyHome && intent.hasCategory(Intent.CATEGORY_HOME)) {
            main.removeCallbacks(pageSettle)
            showPage(Page.HOME)
            area = Area.HOME
            tabBar.setBarFocused(false)
            nav.home()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            images.trim()
            appArt.trim()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // In ambient mode the first press of anything only brings the UI back.
        if (ambient && event.keyCode != KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                exitAmbient()
                scheduleAmbient()
            }
            return true
        }
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> 0 to -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0 to 1
            KeyEvent.KEYCODE_DPAD_UP -> -1 to 0
            KeyEvent.KEYCODE_DPAD_DOWN -> 1 to 0
            else -> null
        }
        if (dir != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                // Holding a direction repeats, but no faster than the cards can follow.
                if (event.repeatCount == 0 || now - lastMoveAt >= REPEAT_MS) {
                    lastMoveAt = now
                    val (dRow, dCol) = dir
                    val first = event.repeatCount == 0
                    when (area) {
                        Area.HOME -> homeMove(dRow, dCol, first)
                        Area.TABS -> tabsMove(dRow, dCol, first)
                        Area.PAGE -> pageMove(dRow, dCol, first)
                    }
                }
            }
            return true
        }
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && area == Area.TABS) {
            // OK on a tab: switch now and step into its page.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                main.removeCallbacks(pageSettle)
                showPage(tabBar.focused)
                enterPage()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                backConsumed = when (area) {
                    // Back climbs: from a lower row to the hero, from the hero to the tab bar.
                    Area.HOME -> {
                        if (nav.row != 0) nav.home() else enterTabs()
                        true
                    }
                    Area.PAGE -> {
                        enterTabs()
                        true
                    }
                    Area.TABS -> when {
                        page != Page.HOME -> {
                            main.removeCallbacks(pageSettle)
                            tabBar.focusOn(Page.HOME)
                            showPage(Page.HOME)
                            true
                        }
                        // As the Home app, Back has nowhere to go; when opened as a regular app it exits.
                        else -> isDefaultHome()
                    }
                }
            }
            if (backConsumed) return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) scheduleAmbient()
        return super.dispatchKeyEvent(event)
    }

    private fun homeMove(dRow: Int, dCol: Int, first: Boolean) {
        val moved = when {
            nav.row == 0 && dCol != 0 -> {
                hero.step(dCol)
                true
            }
            nav.row == 0 && dRow < 0 -> {
                enterTabs()
                true
            }
            else -> nav.move(dRow, dCol)
        }
        if (!moved && first) (currentFocus as? FocusCard)?.bump(dCol, dRow)
    }

    private fun tabsMove(dRow: Int, dCol: Int, first: Boolean) {
        when {
            dCol != 0 -> if (tabBar.moveFocus(dCol)) {
                main.removeCallbacks(pageSettle)
                main.postDelayed(pageSettle, TAB_SETTLE_MS)
            } else if (first) {
                tabBar.bump(dCol)
            }
            dRow > 0 -> {
                main.removeCallbacks(pageSettle)
                showPage(tabBar.focused)
                enterPage()
            }
        }
    }

    /** In the Apps grid the RecyclerView finds the next card; off its top edge is the tab bar. */
    private fun pageMove(dRow: Int, dCol: Int, first: Boolean) {
        val focused = appsGrid.focusedChild
        if (focused == null) {
            focusGrid(gridFocus, View.FOCUS_DOWN)
            return
        }
        val direction = when {
            dCol > 0 -> View.FOCUS_RIGHT
            dCol < 0 -> View.FOCUS_LEFT
            dRow > 0 -> View.FOCUS_DOWN
            else -> View.FOCUS_UP
        }
        val next = appsGrid.focusSearch(focused, direction)
        when {
            next != null && next !== focused && next.parent === appsGrid -> next.requestFocus(direction)
            dRow < 0 -> enterTabs()
            first -> (focused as? FocusCard)?.bump(dCol, dRow)
        }
    }

    private fun enterTabs() {
        if (area == Area.PAGE) appsGrid.focusedChild?.let { gridFocus = appsGrid.getChildAdapterPosition(it).coerceAtLeast(0) }
        area = Area.TABS
        main.removeCallbacks(toAmbient)
        tabBar.setBarFocused(true)
        tabBar.requestFocus()
    }

    /** Down from the tab bar, into whatever page is showing. */
    private fun enterPage() {
        when (page) {
            Page.HOME -> {
                area = Area.HOME
                tabBar.setBarFocused(false)
                nav.go(0, 0, direction = View.FOCUS_DOWN)
            }
            Page.APPS -> {
                area = Area.PAGE
                tabBar.setBarFocused(false)
                focusGrid(gridFocus, View.FOCUS_DOWN)
            }
            // Coming-soon pages have nothing to focus yet.
            else -> tabBar.bump(0)
        }
    }

    private fun focusGrid(position: Int, direction: Int) {
        val count = gridAdapter.itemCount
        if (count == 0) return
        val target = position.coerceIn(0, count - 1)
        val card = appsGrid.findViewHolderForAdapterPosition(target)?.itemView
        if (card != null) {
            card.requestFocus(direction)
        } else {
            appsGrid.scrollToPosition(target)
            appsGrid.post { appsGrid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus(direction) }
        }
    }

    /** Swaps the content under the tab bar. Home keeps its state; the others sit on frosted glass. */
    private fun showPage(target: Int) {
        if (target == page) return
        val from = page
        page = target
        tabBar.select(target)
        if (target == Page.HOME) {
            pageApps.fadeTo(0f, 200)
            pageSoon.fadeTo(0f, 200)
            content.fadeTo(1f, 420)
            onMove(nav.row, nav.col, rowChanged = true)
            return
        }
        if (from == Page.HOME) {
            content.fadeTo(0f, 260)
            previews.cue(null, 0)
            main.removeCallbacks(toAmbient)
            exitAmbient()
            hero.setRunning(false)
            backdrop.setGlass(fullGlass = true, showBand = false)
            restDim = PAGE_DIM
            previewDim = PAGE_DIM
            applyDim()
            chrome.fadeTo(1f, 300)
            scrimTop.fadeTo(1f, 300)
            updateChip()
        }
        if (target == Page.APPS) {
            pageSoon.fadeTo(0f, 200)
            rise(pageApps)
        } else {
            pageApps.fadeTo(0f, 200)
            if (pageSoon.visibility == View.VISIBLE && pageSoon.alpha > 0.5f) {
                // One coming-soon page to another: dip out, swap the words, rise back in.
                pageSoon.animate().alpha(0f).setDuration(120).setStartDelay(0).setInterpolator(Motion.EASE).withEndAction {
                    bindSoon(target)
                    rise(pageSoon)
                }
            } else {
                bindSoon(target)
                rise(pageSoon)
            }
        }
    }

    private fun rise(view: View) {
        view.visibility = View.VISIBLE
        view.translationY = Ui.px(26f)
        view.animate().alpha(1f).translationY(0f).setDuration(460).setStartDelay(60).setInterpolator(Motion.SETTLE)
            .withEndAction(null)
    }

    private fun bindSoon(target: Int) {
        val (icon, title, line) = when (target) {
            Page.SEARCH -> Triple(R.drawable.ic_search, R.string.soon_search_title, R.string.soon_search_line)
            Page.SHOWS -> Triple(R.drawable.ic_tv, R.string.soon_shows_title, R.string.soon_shows_line)
            Page.MOVIES -> Triple(R.drawable.ic_film, R.string.soon_movies_title, R.string.soon_movies_line)
            else -> Triple(R.drawable.ic_library, R.string.soon_library_title, R.string.soon_library_line)
        }
        findViewById<ImageView>(R.id.page_soon_icon).setImageResource(icon)
        findViewById<TextView>(R.id.page_soon_title).setText(title)
        findViewById<TextView>(R.id.page_soon_line).setText(line)
    }

    private fun onMove(row: Int, col: Int, rowChanged: Boolean) {
        if (rowChanged) applyRow(row)
        val show: Show? = when (row) {
            0 -> {
                backdrop.show(hero.current.show.backdrop)
                restDim = 0f
                previewDim = 0f
                hero.current.show
            }
            1 -> {
                val tile = appsAdapter.items.getOrNull(col) ?: return
                val featured = tile.provider?.let { Demo.shelves[it] }
                shelf.bind(tile, featured, apps.size, animate = !rowChanged)
                backdrop.show((featured?.show ?: hero.current.show).backdrop, SETTLE_MS)
                restDim = if (featured != null) 0.06f else 0.6f
                previewDim = 0.06f
                featured?.show
            }
            2 -> behind(Demo.upNext[col].show, 0.72f, preview = 0.5f)
            3 -> behind(Demo.myShows[col], 0.7f, preview = 0.42f)
            else -> behind(Demo.favorites[col], 0.7f, preview = 0.42f)
        }
        applyDim()
        previews.cue(show?.clip, if (row == 0) HERO_PREVIEW_MS else ROW_PREVIEW_MS)
    }

    /** Lower rows sit on frosted glass: the mockup's dim, then a 28% veil on the glass itself. */
    private fun behind(show: Show, dimAmount: Float, preview: Float): Show {
        backdrop.show(show.backdrop, SETTLE_MS)
        restDim = 1f - (1f - dimAmount) * (1f - 0.28f)
        previewDim = preview
        return show
    }

    private fun applyDim() = dim.fadeTo(if (previews.isShowing) previewDim else restDim, 600)

    private fun applyRow(row: Int) {
        scroll.animateToFinalPosition(Ui.px(ROW_OFFSET[row]))
        heroView.fadeTo(if (row == 0) 1f else 0f, 400)
        heroView.glideY(if (row == 0) 0f else Ui.px(-40f), 1000)
        shelfView.fadeTo(if (row == 1) 1f else 0f, 360)
        shelfView.glideY(if (row == 1) 0f else Ui.px(40f), 520)
        val chromeAlpha = if (row <= 1) 1f else 0f
        chrome.fadeTo(chromeAlpha, 600)
        scrimTop.fadeTo(chromeAlpha, 600)
        backdrop.setGlass(fullGlass = row >= 2, showBand = row <= 1)
        // Once you're down in the shows, the app icons above recede instead of shouting.
        appsRow.fadeTo(if (row >= 2) 0.4f else 1f, 500)
        hero.setRunning(row == 0 && resumed)
        updateChip()
    }

    override fun onPreviewShown() {
        backdrop.setArtShown(false)
        applyDim()
        updateChip()
        scheduleAmbient()
    }

    override fun onPreviewGone(finished: Boolean) {
        main.removeCallbacks(toAmbient)
        exitAmbient()
        backdrop.setArtShown(true)
        applyDim()
        updateChip()
    }

    private fun updateChip() = chip.fadeTo(if (previews.isShowing && nav.row <= 1 && page == Page.HOME) 1f else 0f, 500)

    /** After a few quiet seconds of a hero preview, the UI steps back and lets it play. */
    private fun scheduleAmbient() {
        main.removeCallbacks(toAmbient)
        if (previews.isShowing && nav.row == 0 && area == Area.HOME) main.postDelayed(toAmbient, AMBIENT_MS)
    }

    private fun enterAmbient() {
        if (ambient || nav.row != 0 || area != Area.HOME || !previews.isShowing) return
        ambient = true
        hero.setAmbient(true)
        appsRow.fadeTo(0f, 600)
        appsRow.glideY(Ui.px(340f), 1000)
        chrome.fadeTo(0f, 600)
        scrimTop.fadeTo(0f, 600)
        scrimLeft.animate().alpha(0.4f).setDuration(900).setStartDelay(0)
        scrimBottom.animate().alpha(0.8f).setDuration(900).setStartDelay(0)
        chipText.setText(R.string.preview_ambient)
    }

    private fun exitAmbient() {
        if (!ambient) return
        ambient = false
        hero.setAmbient(false)
        appsRow.fadeTo(1f, 500)
        appsRow.glideY(0f, 800)
        chrome.fadeTo(1f, 500)
        scrimTop.fadeTo(1f, 500)
        scrimLeft.animate().alpha(1f).setDuration(600).setStartDelay(0)
        scrimBottom.animate().alpha(1f).setDuration(600).setStartDelay(0)
        chipText.setText(R.string.preview_muted)
    }

    private fun row(id: Int, adapter: RecyclerView.Adapter<*>, gap: Int): RecyclerView =
        findViewById<RecyclerView>(id).apply {
            layoutManager = RowLayoutManager(context)
            this.adapter = adapter
            itemAnimator = null
            setHasFixedSize(true)
            addItemDecoration(Gap(Ui.px(gap)))
            drawFocusedLast()
        }

    /** Draw the focused card last so its lift and shadow lie over its neighbours. */
    private fun RecyclerView.drawFocusedLast() = setChildDrawingOrderCallback { count, i ->
        val focused = focusedChild?.let { indexOfChild(it) } ?: -1
        when {
            focused < 0 -> i
            i == count - 1 -> focused
            i >= focused -> i + 1
            else -> i
        }
    }

    private fun showApps() {
        val tiles = InstalledApps.tiles(apps, getString(R.string.tile_settings), getString(R.string.tile_all_apps))
        appsAdapter.items = tiles
        gridAdapter.items = tiles.filter { it.kind != AppTile.Kind.ALL_APPS }
        findViewById<TextView>(R.id.page_apps_count).text = getString(R.string.page_apps_count, apps.size)
        if (nav.row == 1 && area == Area.HOME) appsRow.post { nav.refocus() }
        if (area == Area.PAGE) appsGrid.post { focusGrid(gridFocus, View.FOCUS_DOWN) }
    }

    private fun refreshApps() {
        Workers.apps.execute {
            val fresh = installed.query()
            runOnUiThread {
                if (!isDestroyed && fresh != apps) {
                    apps = fresh
                    installed.save(fresh)
                    showApps()
                }
            }
            appArt.prune(fresh)
        }
    }

    private val appsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refreshApps()
        override fun onPackageAdded(packageName: String, user: UserHandle) = refreshApps()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refreshApps()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refreshApps()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = refreshApps()
    }

    private fun openTile(tile: AppTile, card: View) {
        when (tile.kind) {
            AppTile.Kind.APP -> launch(tile.entry!!, card)
            AppTile.Kind.SETTINGS -> start(Intent(Settings.ACTION_SETTINGS), card)
            AppTile.Kind.ALL_APPS -> {
                showPage(Page.APPS)
                area = Area.PAGE
                gridFocus = 0
                appsGrid.scrollToPosition(0)
                appsGrid.post { focusGrid(0, View.FOCUS_DOWN) }
            }
        }
    }

    /** Opens the show's service. Phase 2 deep-links to the episode; for now it opens the app. */
    private fun openProvider(provider: Provider, from: View) {
        val entry = apps.firstOrNull { provider.owns(it.packageName) }
        if (entry != null) {
            launch(entry, from)
        } else {
            Toast.makeText(this, getString(R.string.provider_missing, provider.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launch(entry: AppEntry, from: View) {
        val category = if (entry.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(entry.component)
        if (!start(intent, from)) refreshApps()
    }

    private fun start(intent: Intent, from: View): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        return try {
            startActivity(intent, options)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    private fun shades(vararg alphas: Float) = IntArray(alphas.size) { Palette.alpha(Palette.NIGHT, alphas[it]) }

    private companion object {
        /** Content offset per row, in mockup px: the hero and the app icons share the top. */
        val ROW_OFFSET = floatArrayOf(0f, 0f, -790f, -1232f, -1510f)

        /** Lets focus rest on a card briefly before its backdrop decodes. */
        const val SETTLE_MS = 150L

        /** How long focus rests before a preview starts: longer on the hero, as in the mockup. */
        const val HERO_PREVIEW_MS = 1500L
        const val ROW_PREVIEW_MS = 1200L

        /** Quiet time with a hero preview playing before the UI steps back. */
        const val AMBIENT_MS = 2800L

        const val TAB_SETTLE_MS = 260L
        const val GRID_COLUMNS = 5

        /** How dark the frosted backdrop sits behind the other tabs' pages. */
        const val PAGE_DIM = 0.8f

        const val REPEAT_MS = 90L
    }
}

/** The tab bar's items, in order. */
private object Page {
    const val SEARCH = 0
    const val HOME = 1
    const val SHOWS = 2
    const val MOVIES = 3
    const val APPS = 4
    const val LIBRARY = 5
}
