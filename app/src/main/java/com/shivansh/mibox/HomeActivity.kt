package com.shivansh.mibox

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.SurfaceView
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shivansh.mibox.data.AppEntry
import com.shivansh.mibox.data.AppTile
import com.shivansh.mibox.data.Demo
import com.shivansh.mibox.data.HomeContent
import com.shivansh.mibox.data.MediaShelf
import com.shivansh.mibox.data.MediaType
import com.shivansh.mibox.data.Row
import com.shivansh.mibox.data.tmdb.Account
import com.shivansh.mibox.data.tmdb.Auth
import com.shivansh.mibox.data.tmdb.Catalog
import com.shivansh.mibox.data.tmdb.Lists
import com.shivansh.mibox.data.tmdb.Tmdb
import com.shivansh.mibox.data.InstalledApps
import com.shivansh.mibox.data.Provider
import com.shivansh.mibox.data.Show
import com.shivansh.mibox.ui.AppArtSource
import com.shivansh.mibox.ui.Backdrop
import com.shivansh.mibox.ui.Cards
import com.shivansh.mibox.ui.ContextMenu
import com.shivansh.mibox.ui.FocusCard
import com.shivansh.mibox.ui.Fonts
import com.shivansh.mibox.ui.Gap
import com.shivansh.mibox.ui.Glass
import com.shivansh.mibox.ui.Hero
import com.shivansh.mibox.ui.ImageFiles
import com.shivansh.mibox.ui.Images
import com.shivansh.mibox.ui.MediaPage
import com.shivansh.mibox.ui.SearchPage
import com.shivansh.mibox.ui.LinearScrim
import com.shivansh.mibox.ui.Motion
import com.shivansh.mibox.ui.Navigator
import com.shivansh.mibox.ui.Palette
import com.shivansh.mibox.ui.Previews
import com.shivansh.mibox.ui.RowLayoutManager
import com.shivansh.mibox.ui.Shelf
import com.shivansh.mibox.ui.SignIn
import com.shivansh.mibox.ui.TabBar
import com.shivansh.mibox.ui.TitleLogos
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
    private lateinit var pageSearch: View
    private lateinit var searchPage: SearchPage
    private lateinit var pageSignin: View
    private lateinit var pageMedia: View
    private lateinit var mediaPage: MediaPage
    private lateinit var logos: TitleLogos
    private lateinit var menu: ContextMenu
    private lateinit var note: TextView
    private lateinit var lists: Lists
    private lateinit var tmdb: Tmdb
    private lateinit var account: Account
    private lateinit var catalog: Catalog
    private lateinit var signIn: SignIn
    private lateinit var upNextAdapter: UpNextAdapter
    private lateinit var myShowsAdapter: PosterAdapter
    private lateinit var favoritesAdapter: FavoritesAdapter
    private lateinit var popularAdapter: PosterAdapter
    private lateinit var acclaimedAdapter: FavoritesAdapter
    private lateinit var sections: ViewGroup

    /** What the home screen shows: TMDB content (cached copy first, then fresh), or the demo shows. */
    private var home: HomeContent = Demo.content()
    private var lastRefresh = 0L
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
    private val hideNote = Runnable { note.fadeTo(0f, 400) }

    /** List changes sent to TMDB that a fetched list may not show yet: key to (on, when). */
    private val pendingLists = HashMap<String, Pair<Boolean, Long>>()

    /** The titles behind those changes, so a newly saved one shows in the lists straight away. */
    private val pendingShows = HashMap<String, Show>()

    /** OK held on a show or an app opens its menu; a short press is an ordinary click. */
    private var holding = false
    private var holdShow: Show? = null
    private var holdApp: AppTile? = null
    private val holdOk = Runnable {
        holding = false
        holdShow?.let(::openMenu) ?: holdApp?.let(::openAppMenu)
    }

    /** The packages on the Home row, in the user's order (an app is added from the Apps tab). */
    private var favorites = mutableListOf<String>()

    /** Where the app being moved along the Home row is, or -1; and its little shake. */
    private var moving = -1
    private var jiggle: ObjectAnimator? = null
    private var menuFrom = Area.HOME

    /** The Shows and Movies tabs' genre shelves once loaded, and when each was last asked for. */
    private val browse = HashMap<MediaType, List<MediaShelf>>()
    private val browseAsked = HashMap<MediaType, Long>()

    /** Full records fetched for titles focused on those tabs, by show id. */
    private val enriched = HashMap<String, Show>()
    private val enriching = HashSet<String>()
    private var enrichNext: Show? = null
    private val enrichSoon = Runnable { enrichNext?.let(::enrich) }

    /** Where the remote's keys go: the home rows, the tab bar, another tab's page, or the hold-OK menu. */
    private enum class Area { HOME, TABS, PAGE, MENU, MOVE }

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

        // Light shading only where the words are, so the art reads full-bleed and vivid, then a
        // long fade at the bottom where the apps row sits (Apple TV's look).
        scrimLeft.background = LinearScrim(90f, shades(0.72f, 0.42f, 0.14f, 0f), floatArrayOf(0f, 0.38f, 0.7f, 1f))
        scrimBottom.background = LinearScrim(0f, shades(0.84f, 0.5f, 0.16f, 0f), floatArrayOf(0f, 0.36f, 0.7f, 1f))
        scrimTop.background = LinearScrim(180f, shades(0.45f, 0f), floatArrayOf(0f, 1f))
        tabBar = findViewById(R.id.tabs)
        tabBar.background = Glass.control(Ui.px(60f), Palette.alpha(0xFFFFFF, 0.1f))
        chip.background = Glass.control(Ui.px(48f), Palette.alpha(Palette.NIGHT, 0.45f))
        pageApps = findViewById(R.id.page_apps)
        pageSearch = findViewById(R.id.page_search)
        pageSignin = findViewById(R.id.page_signin)
        pageMedia = findViewById(R.id.page_media)
        logos = TitleLogos(assets)
        note = findViewById(R.id.note)
        note.background = Glass.control(Ui.px(60f), Palette.alpha(Palette.NIGHT, 0.72f))
        menu = ContextMenu(findViewById(R.id.menu)) {
            area = menuFrom
            hero.setRunning(resumed && area == Area.HOME && nav.row == 0 && page == Page.HOME)
            scheduleAmbient()
        }
        ImageFiles.init(this)
        tmdb = Tmdb(this)
        account = Account(this)
        catalog = Catalog(tmdb, account)
        lists = Lists(tmdb, account)
        signIn = SignIn(pageSignin, Auth(tmdb, account), ::onSignedIn)
        // Last known TMDB content straight from disk (no network), so the first frame is real.
        home = catalog.load(offline = true) ?: Demo.content()
        findViewById<View>(R.id.search_fade).background = LinearScrim(180f, shades(0f, 0.85f, 0f), floatArrayOf(0f, 0.53f, 1f))
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

        hero = Hero(heroView, home.hero, logos) { slide ->
            if (nav.row == 0 && page == Page.HOME) {
                backdrop.show(slide.show.backdrop)
                previews.cue(slide.show.clip, HERO_PREVIEW_MS)
            }
        }
        hero.inList = { inList(Lists.WATCHLIST, it) }
        hero.playButton.setOnClickListener { openShow(hero.current.show, it) }
        hero.listButton.setOnClickListener { toggleList(Lists.WATCHLIST, hero.current.show) }
        hero.trailerButton.setOnClickListener { playTrailer(hero.current.show) }
        shelf = Shelf(shelfView)

        appsAdapter = AppsAdapter(appArt).apply { onActivate = { tile, card -> openTile(tile, card) } }
        upNextAdapter = UpNextAdapter(images).apply { onActivate = { item, card -> openShow(item.show, card) } }
        myShowsAdapter = PosterAdapter(images).apply { onActivate = { show, card -> openShow(show, card) } }
        favoritesAdapter = FavoritesAdapter(images).apply { onActivate = { show, card -> openShow(show, card) } }
        popularAdapter = PosterAdapter(images).apply { onActivate = { show, card -> openShow(show, card) } }
        acclaimedAdapter = FavoritesAdapter(images).apply { onActivate = { show, card -> openShow(show, card) } }
        sections = findViewById(R.id.sections)
        bindRows(home)

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
                Navigator.Row(row(R.id.row_up_next, upNextAdapter, gap = 40), keep = 2, step = 416 + 40),
                Navigator.Row(row(R.id.row_my_shows, myShowsAdapter, gap = 36), keep = 4, step = 232 + 36),
                Navigator.Row(row(R.id.row_favorites, favoritesAdapter, gap = 40), keep = 1, step = 560 + 40),
                Navigator.Row(row(R.id.row_popular, popularAdapter, gap = 36), keep = 4, step = 232 + 36),
                Navigator.Row(row(R.id.row_acclaimed, acclaimedAdapter, gap = 40), keep = 1, step = 560 + 40),
            ),
            focusHero = {
                heroView.visibility = View.VISIBLE
                // A refresh refocuses the hero; whoever was on Add to List stays there.
                if (!hero.buttonFocused) hero.focusPlay()
            },
            onMove = ::onMove,
        )

        val mediaAdapter = PosterAdapter(images, Cards.PAGE_POSTER).apply { onActivate = { show, card -> openShow(show, card) } }
        mediaPage = MediaPage(pageMedia, row(R.id.media_row, mediaAdapter, gap = MediaPage.GAP), mediaAdapter, logos, ::onMediaShow)

        val searchAdapter = PosterAdapter(images, Cards.PAGE_POSTER).apply { onActivate = { show, card -> openShow(show, card) } }
        val searchGrid = findViewById<RecyclerView>(R.id.search_grid).apply {
            layoutManager = GridLayoutManager(context, SearchPage.COLUMNS)
            adapter = searchAdapter
            itemAnimator = null
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    outRect.set(0, 0, 0, Ui.px(26))
                }
            })
            drawFocusedLast()
        }
        searchPage = SearchPage(pageSearch, searchGrid, searchAdapter, ::onSearchShow, ::search, ::listen)
        // The mic key only where the box has a speech recognizer (Google's, on Android TV).
        searchPage.keyboard.voice = packageManager.resolveActivity(voiceIntent(), 0) != null

        hero.bind(0, entrance = true)
        backdrop.show(hero.current.show.backdrop)

        apps = installed.cached()
        showApps()
        refreshApps()
        launcherApps.registerCallback(appsCallback, Handler(Looper.getMainLooper()))

        content.post {
            nav.go(0, 0, animate = false)
            if (intent?.action == ACTION_SHOW_APPS) openApps()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshContent()
        hero.setRunning(nav.row == 0 && page == Page.HOME)
        if (page == Page.HOME) {
            if (area == Area.HOME) nav.refocus() else onMove(nav.row, nav.col, rowChanged = false)
        }
        when (area) {
            Area.TABS -> tabBar.requestFocus()
            Area.PAGE -> when {
                isMedia(page) -> mediaPage.refocus()
                page == Page.SEARCH -> searchPage.refocus()
                else -> focusGrid(gridFocus, View.FOCUS_DOWN)
            }
            Area.HOME, Area.MENU, Area.MOVE -> Unit
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(holdOk)
        holding = false
        menu.close()
        stopMoving()
        hero.setRunning(false)
        main.removeCallbacks(toAmbient)
        exitAmbient()
        previews.release()
        signIn.stop()
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(appsCallback)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_SHOW_APPS) {
            openApps()
            return
        }
        // Home pressed while already home: back to the top.
        val alreadyHome = hasWindowFocus() && (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        if (alreadyHome && intent.hasCategory(Intent.CATEGORY_HOME)) {
            menu.close()
            stopMoving()
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
        if (area == Area.MENU) {
            menu.key(event)
            return true
        }
        if (area == Area.MOVE) {
            moveKey(event)
            return true
        }
        // The remote's Menu key opens the same menu as holding OK.
        if (event.keyCode == KeyEvent.KEYCODE_MENU && (area == Area.HOME || area == Area.PAGE)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                focusedShow()?.let(::openMenu) ?: focusedApp()?.let(::openAppMenu)
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
                        Area.MENU, Area.MOVE -> Unit
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
        // The remote's search key (where it reaches apps), from anywhere: straight to Search.
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openSearch()
            return true
        }
        if (area == Area.PAGE && page == Page.SEARCH && typeOnSearch(event)) return true
        if (ok && area == Area.PAGE && page == Page.SEARCH && searchPage.keysFocused) {
            if (event.action == KeyEvent.ACTION_DOWN) searchPage.press(event.repeatCount)
            return true
        }
        if (ok && area == Area.PAGE && isMedia(page) && mediaPage.genresFocused) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) mediaPage.okOnGenres()
            return true
        }
        if (ok && (area == Area.HOME || area == Area.PAGE)) {
            val show = focusedShow()
            val app = if (show == null) focusedApp() else null
            if (show != null || app != null || holding) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                        holding = true
                        holdShow = show
                        holdApp = app
                        main.postDelayed(holdOk, HOLD_MS)
                        scheduleAmbient()
                    } else if (holding) {
                        // The key has started repeating (or is flagged as a long press): it's a hold.
                        main.removeCallbacks(holdOk)
                        holdOk.run()
                    }
                    // Let go before the menu opened: an ordinary click. After it opened, nothing.
                    KeyEvent.ACTION_UP -> if (holding) {
                        holding = false
                        main.removeCallbacks(holdOk)
                        currentFocus?.performClick()
                    }
                }
                return true
            }
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
                    Area.MENU, Area.MOVE -> true
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
                heroSideways(dCol)
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

    /** Across the hero's buttons first; past either end, the previous or next slide. */
    private fun heroSideways(dCol: Int) {
        if (hero.moveButton(dCol)) {
            hero.holdSlide()
        } else {
            hero.step(dCol)
            hero.focusPlay()
        }
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
        if (isMedia(page)) {
            if (!mediaPage.move(dRow, dCol, first)) enterTabs()
            return
        }
        if (page == Page.SEARCH) {
            if (!searchPage.move(dRow, dCol, first)) enterTabs()
            return
        }
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
            Page.SEARCH -> {
                area = Area.PAGE
                tabBar.setBarFocused(false)
                searchPage.enter()
            }
            // Shows, Movies, and the Library once signed in.
            else -> if (isMedia(page)) {
                area = Area.PAGE
                tabBar.setBarFocused(false)
                mediaPage.enter()
            } else {
                // The sign-in and coming-soon pages have nothing to focus.
                tabBar.bump(0)
            }
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
        if (from == Page.LIBRARY) signIn.stop()
        val shown: View? = when {
            target == Page.HOME -> null
            target == Page.APPS -> pageApps
            isMedia(target) -> pageMedia
            target == Page.LIBRARY && !account.signedIn -> pageSignin
            else -> pageSearch
        }
        for (other in arrayOf(pageApps, pageSearch, pageSignin, pageMedia)) if (other !== shown) other.fadeTo(0f, 200)
        if (target == Page.HOME) {
            backdrop.drift = true
            scrimLeft.fadeTo(1f, 420)
            scrimBottom.fadeTo(1f, 420)
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
            chrome.fadeTo(1f, 300)
            scrimTop.fadeTo(1f, 300)
            updateChip()
        }
        // Shows and Movies sit on the focused title's own art, sharp and full-bleed; the quieter
        // pages on dark frosted glass.
        backdrop.setGlass(fullGlass = shown !== pageMedia, showBand = false)
        backdrop.drift = shown !== pageMedia
        // Shows and Movies carry their shading inside the picture; the live scrims step aside once
        // the shaded art has had time to arrive.
        // Search doesn't need them over its dimmed glass either, and they cost the GPU dearly.
        val scrims = if (shown === pageMedia || shown === pageSearch) 0f else 1f
        scrimLeft.fadeTo(scrims, 700, delay = if (shown === pageMedia) 350 else 0)
        scrimBottom.fadeTo(scrims, 700, delay = if (shown === pageMedia) 350 else 0)
        scrimTop.fadeTo(scrims, 700, delay = if (shown === pageMedia) 350 else 0)
        restDim = when (shown) {
            pageMedia -> MEDIA_DIM
            pageSearch -> SEARCH_DIM
            else -> PAGE_DIM
        }
        previewDim = restDim
        applyDim()
        when (shown) {
            pageSignin -> {
                rise(pageSignin)
                signIn.start()
            }
            pageApps -> rise(pageApps)
            pageMedia -> {
                // Shows to Movies: the page drops away and rises again with the other lists.
                pageMedia.alpha = 0f
                bindMedia(target, fresh = true)
                rise(pageMedia)
            }
            else -> {
                pageSearch.alpha = 0f
                searchPage.setTrending(trendingMix())
                rise(pageSearch)
            }
        }
    }

    /** The pages built on [MediaPage]: Shows, Movies, and the Library when signed in. */
    private fun isMedia(target: Int) =
        target == Page.SHOWS || target == Page.MOVIES || (target == Page.LIBRARY && account.signedIn)

    /**
     * Fills a [MediaPage] tab. Shows and Movies: Trending, the genres (once loaded; Trending alone
     * until then), and the user's own list last. Library: the user's four lists.
     */
    private fun bindMedia(target: Int, fresh: Boolean) {
        val shelves = if (target == Page.LIBRARY) {
            libraryShelves()
        } else {
            val movies = target == Page.MOVIES
            val type = if (movies) MediaType.MOVIE else MediaType.TV
            val genres = browse[type]
                ?: listOf(MediaShelf("trending", getString(R.string.shelf_trending), if (movies) home.trendingMovies else home.trendingShows))
            loadBrowse(type)
            genres + if (movies) {
                listShelf(Lists.WATCHLIST, MediaType.MOVIE, R.string.media_watchlist, home.movieWatchlist, R.string.media_empty_movies)
            } else {
                listShelf(Lists.WATCHLIST, MediaType.TV, R.string.media_my_shows, home.showWatchlist, R.string.media_empty_shows)
            }
        }
        mediaPage.bind(
            shelves.map { shelf -> shelf.copy(items = shelf.items.map { enriched[it.id] ?: it }) },
            getString(R.string.media_offline),
            fresh,
            lists = target == Page.LIBRARY,
        )
    }

    /** Everything the user has saved on TMDB, a shelf per list. */
    private fun libraryShelves() = listOf(
        listShelf(Lists.WATCHLIST, MediaType.TV, R.string.media_my_shows, home.showWatchlist, R.string.media_empty_shows),
        listShelf(Lists.WATCHLIST, MediaType.MOVIE, R.string.media_watchlist, home.movieWatchlist, R.string.media_empty_movies),
        listShelf(Lists.FAVORITE, MediaType.TV, R.string.library_favorite_shows, home.showFavorites, R.string.library_empty_favorite_shows),
        listShelf(Lists.FAVORITE, MediaType.MOVIE, R.string.library_favorite_films, home.movieFavorites, R.string.library_empty_favorite_films),
    )

    private fun listShelf(list: String, type: MediaType, label: Int, items: List<Show>, empty: Int) = MediaShelf(
        "$list:${type.api}",
        getString(label),
        withPending(list, type, items),
        getString(if (account.signedIn) empty else R.string.media_empty_signed_out),
    )

    /**
     * [items] from TMDB with the changes still on their way there: titles just removed are left
     * out, and titles just saved are in (at the end of My Shows, which runs oldest first; at the
     * front of the others, newest first).
     */
    private fun withPending(list: String, type: MediaType, items: List<Show>): List<Show> {
        val now = SystemClock.uptimeMillis()
        fun pending(key: String) = pendingLists[key]?.takeIf { now - it.second < PENDING_MS }?.first
        val kept = items.filter { pending(it.listKey(list)) != false }
        val added = pendingShows.values.filter { show ->
            show.mediaType == type && pending(show.listKey(list)) == true && kept.none { it.id == show.id }
        }
        return if (list == Lists.WATCHLIST && type == MediaType.TV) kept + added else added + kept
    }

    /** The genre shelves: the disk copy first (instant), then fresh from TMDB. */
    private fun loadBrowse(type: MediaType) {
        val now = SystemClock.uptimeMillis()
        browseAsked[type]?.let { if (now - it < BROWSE_MS) return }
        browseAsked[type] = now
        val cold = type !in browse
        Workers.browse.execute {
            if (cold) {
                val cached = runCatching { catalog.browse(type, offline = true) }.getOrNull()
                if (cached != null && cached.count { it.items.isNotEmpty() } > 1) runOnUiThread { applyBrowse(type, cached) }
            }
            val fresh = runCatching { catalog.browse(type, offline = false) }.getOrNull() ?: return@execute
            runOnUiThread { applyBrowse(type, fresh) }
        }
    }

    private fun applyBrowse(type: MediaType, shelves: List<MediaShelf>) {
        if (isDestroyed || shelves.none { it.items.isNotEmpty() }) return
        browse[type] = shelves
        val target = if (type == MediaType.MOVIE) Page.MOVIES else Page.SHOWS
        if (page != target) return
        bindMedia(page, fresh = false)
        if (area == Area.PAGE) mediaPage.refocus()
    }

    /** A title came to the front on Shows, Movies or the Library: its art behind the page, its full record soon after. */
    private fun onMediaShow(show: Show) = onTitle(show, shaded = true)

    /** The same on Search, whose frosted glass needs no shading in the art. */
    private fun onSearchShow(show: Show) = onTitle(show, shaded = false)

    private fun onTitle(show: Show, shaded: Boolean) {
        backdrop.show(show.backdrop, MEDIA_SETTLE_MS, shaded)
        main.removeCallbacks(enrichSoon)
        if (show.detailed || show.tmdbId == 0) return
        // Only once focus rests, so sweeping along the row doesn't fetch every title on the way.
        enrichNext = show
        main.postDelayed(enrichSoon, ENRICH_MS)
    }

    private fun enrich(show: Show) {
        if (!enriching.add(show.id)) return
        Workers.browse.execute {
            val rich = runCatching { catalog.enrich(show) }.getOrNull()
            runOnUiThread {
                enriching.remove(show.id)
                if (rich == null || !rich.detailed || isDestroyed) return@runOnUiThread
                enriched[show.id] = rich
                mediaPage.update(rich)
                searchPage.update(rich)
            }
        }
    }

    private fun rise(view: View) {
        view.visibility = View.VISIBLE
        view.translationY = Ui.px(26f)
        view.animate().alpha(1f).translationY(0f).setDuration(460).setStartDelay(60).setInterpolator(Motion.SETTLE)
            .withEndAction(null)
    }

    /** What Search shows before anything is typed: this week's trending shows and films, alternating. */
    private fun trendingMix(): List<Show> {
        val shows = home.trendingShows.take(10)
        val films = home.trendingMovies.take(10)
        return (0 until maxOf(shows.size, films.size)).flatMap { listOfNotNull(shows.getOrNull(it), films.getOrNull(it)) }
            .map { enriched[it.id] ?: it }
    }

    private fun search(query: String, done: (List<Show>?) -> Unit) {
        Workers.browse.execute {
            val results = runCatching { catalog.search(query) }.getOrNull()
            runOnUiThread { if (!isDestroyed) done(results?.map { enriched[it.id] ?: it }) }
        }
    }

    /** The search key, or the mic on Search: Search, with the keyboard in focus. */
    private fun openSearch() {
        menu.close()
        main.removeCallbacks(pageSettle)
        tabBar.focusOn(Page.SEARCH)
        showPage(Page.SEARCH)
        area = Area.PAGE
        tabBar.setBarFocused(false)
        searchPage.enter()
    }

    /** Letters, digits and Delete from a keyboard or a phone remote, typed straight into Search. */
    private fun typeOnSearch(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode == KeyEvent.KEYCODE_DEL) {
            searchPage.backspace()
            return true
        }
        val char = event.unicodeChar.toChar()
        if (event.unicodeChar == 0 || !(char.isLetterOrDigit() || char == ' ')) return false
        searchPage.type(char.lowercaseChar().toString())
        return true
    }

    private fun voiceIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_voice_prompt))

    /** The mic key: the box's speech recognizer listens, and what it hears becomes the search. */
    private fun listen() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(voiceIntent(), VOICE_REQUEST)
        } catch (_: ActivityNotFoundException) {
            showNote(getString(R.string.search_no_voice))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VOICE_REQUEST || resultCode != RESULT_OK) return
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        searchPage.setText(heard.lowercase())
    }

    /** The trailer plays full-screen right here; Back returns to whatever it was started from. */
    private fun playTrailer(show: Show) {
        if (show.trailers.isEmpty()) return
        startActivity(TrailerActivity.intent(this, show.title, show.trailers))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** Fresh content from TMDB in the background (cached copies are reused while fresh). */
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
        if (fresh == home) return
        home = fresh
        // Changes TMDB now reports (or that are long past) needn't be held on top any more.
        val now = SystemClock.uptimeMillis()
        pendingLists.entries.removeAll { (key, change) -> (key in fresh.lists) == change.first || now - change.second > PENDING_MS }
        pendingShows.keys.retainAll(pendingLists.keys)
        hero.setSlides(fresh.hero)
        hero.refreshList()
        bindRows(fresh)
        keepRowInView()
        if (page == Page.HOME) {
            if (area == Area.HOME) nav.refocus() else onMove(nav.row, nav.col, rowChanged = false)
        }
        if (isMedia(page)) {
            bindMedia(page, fresh = false)
            if (area == Area.PAGE) mediaPage.refocus()
        }
    }

    /** What OK would act on: the hero's show, or the show card in focus. Apps aren't shows. */
    private fun focusedShow(): Show? = when (area) {
        Area.HOME -> when (nav.row) {
            0 -> if (hero.buttonFocused) hero.current.show else null
            else -> rowShow(nav.row, nav.col)
        }
        Area.PAGE -> when {
            isMedia(page) -> mediaPage.focusedShow
            page == Page.SEARCH -> searchPage.focusedShow
            else -> null
        }
        else -> null
    }

    /** The hold-OK menu: list actions when signed in (or a way to sign in), and opening the show's app. */
    private fun openMenu(show: Show) {
        val movie = show.mediaType == MediaType.MOVIE
        val items = ArrayList<ContextMenu.Item>()
        if (show.tmdbId > 0 && account.signedIn) {
            val saved = inList(Lists.WATCHLIST, show)
            val savedLabel = when {
                movie && saved -> R.string.menu_remove_watchlist
                movie -> R.string.menu_add_watchlist
                saved -> R.string.menu_remove_my_shows
                else -> R.string.menu_add_my_shows
            }
            items += ContextMenu.Item(if (saved) R.drawable.ic_check else R.drawable.ic_plus, getString(savedLabel)) {
                setList(Lists.WATCHLIST, show, !saved)
            }
            val loved = inList(Lists.FAVORITE, show)
            items += ContextMenu.Item(
                if (loved) R.drawable.ic_heart_filled else R.drawable.ic_heart,
                getString(if (loved) R.string.menu_remove_favorite else R.string.menu_add_favorite),
            ) { setList(Lists.FAVORITE, show, !loved) }
        } else if (show.tmdbId > 0) {
            items += ContextMenu.Item(R.drawable.ic_library, getString(R.string.menu_sign_in)) { goSignIn() }
        }
        show.provider?.let { provider ->
            items += ContextMenu.Item(R.drawable.ic_play, getString(R.string.menu_open_in, provider.label)) {
                openShow(show, currentFocus ?: content)
            }
        }
        if (show.trailers.isNotEmpty()) {
            items += ContextMenu.Item(R.drawable.ic_film, getString(R.string.menu_trailer)) { playTrailer(show) }
        }
        if (items.isEmpty()) return
        val kind = getString(if (movie) R.string.menu_kind_movie else R.string.menu_kind_show)
        menuFrom = area
        area = Area.MENU
        main.removeCallbacks(toAmbient)
        // The hero holds still under the menu, so the show it names stays on screen.
        hero.setRunning(false)
        menu.open(show.title, if (show.service.isEmpty()) kind else "$kind · ${show.service}", items)
    }

    /** The app OK would act on: a tile in the Home row or the Apps grid (not Settings or All apps). */
    private fun focusedApp(): AppTile? {
        val tile = when {
            area == Area.HOME && nav.row == 1 -> appsAdapter.items.getOrNull(nav.col)
            area == Area.PAGE && page == Page.APPS ->
                appsGrid.focusedChild?.let { gridAdapter.items.getOrNull(appsGrid.getChildAdapterPosition(it)) }
            else -> null
        }
        return tile?.takeIf { it.kind == AppTile.Kind.APP }
    }

    /** Hold OK on an app: open it, add it to or take it off the Home row, move it there, or its info. */
    private fun openAppMenu(tile: AppTile) {
        val entry = tile.entry ?: return
        val pkg = entry.packageName
        val onRow = area == Area.HOME && nav.row == 1
        val col = nav.col
        val items = ArrayList<ContextMenu.Item>()
        items += ContextMenu.Item(R.drawable.ic_play, getString(R.string.app_open)) { launch(entry, currentFocus ?: content) }
        if (onRow) items += ContextMenu.Item(R.drawable.ic_move, getString(R.string.app_move)) { startMoving(col) }
        items += if (pkg in favorites) {
            ContextMenu.Item(R.drawable.ic_check, getString(R.string.app_remove_home)) { setFavorite(pkg, false) }
        } else {
            ContextMenu.Item(R.drawable.ic_plus, getString(R.string.app_add_home)) { setFavorite(pkg, true) }
        }
        items += ContextMenu.Item(R.drawable.ic_info, getString(R.string.app_info)) {
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")), currentFocus ?: content)
        }
        menuFrom = area
        area = Area.MENU
        main.removeCallbacks(toAmbient)
        hero.setRunning(false)
        menu.open(entry.label, getString(R.string.shelf_app), items)
    }

    private fun setFavorite(pkg: String, on: Boolean) {
        if (on) favorites.add(pkg) else favorites.remove(pkg)
        installed.saveFavorites(favorites)
        showApps()
        showNote(getString(if (on) R.string.note_added else R.string.note_removed, getString(R.string.home_row)))
    }

    /** Move mode on the Home row: left and right carry the app along, OK or Back puts it down. */
    private fun startMoving(col: Int) {
        if (col !in favorites.indices) return
        moving = col
        area = Area.MOVE
        note.text = getString(R.string.app_move_hint)
        note.fadeTo(1f, 200)
        main.removeCallbacks(hideNote)
        shake(true)
    }

    private fun moveKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK ->
                if (event.repeatCount == 0) stopMoving()
        }
    }

    private fun moveBy(delta: Int) {
        val to = moving + delta
        if (to !in favorites.indices) {
            (currentFocus as? FocusCard)?.bump(delta, 0)
            return
        }
        shake(false)
        favorites.add(to, favorites.removeAt(moving))
        appsAdapter.move(moving, to)
        moving = to
        // After the row's next layout: it keeps the focused card where it was, so slide it into place.
        appsRow.post {
            nav.go(1, to, direction = if (delta > 0) View.FOCUS_RIGHT else View.FOCUS_LEFT)
            if (moving >= 0) shake(true)
        }
    }

    private fun stopMoving() {
        if (moving < 0) return
        moving = -1
        shake(false)
        installed.saveFavorites(favorites)
        if (area == Area.MOVE) area = Area.HOME
        note.fadeTo(0f, 300)
    }

    /** The app being moved rocks gently, as on Apple TV. */
    private fun shake(on: Boolean) {
        jiggle?.cancel()
        jiggle = null
        val card = appsRow.findViewHolderForAdapterPosition(if (on) moving else nav.col)?.itemView ?: return
        if (!on) {
            card.rotation = 0f
            return
        }
        jiggle = ObjectAnimator.ofFloat(card, View.ROTATION, -1.4f, 1.4f).apply {
            duration = 130
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    /** The remote's Apps button (through [AppsKeyService]), or All apps: the Apps tab, its grid in focus. */
    private fun openApps() {
        menu.close()
        stopMoving()
        main.removeCallbacks(pageSettle)
        tabBar.focusOn(Page.APPS)
        showPage(Page.APPS)
        area = Area.PAGE
        tabBar.setBarFocused(false)
        appsGrid.post { focusGrid(gridFocus, View.FOCUS_DOWN) }
    }

    private fun toggleList(list: String, show: Show) {
        if (!account.signedIn) {
            goSignIn()
            return
        }
        setList(list, show, !inList(list, show))
    }

    /** On the user's list, counting changes still on their way to TMDB. */
    private fun inList(list: String, show: Show): Boolean {
        val key = show.listKey(list)
        pendingLists[key]?.let { (on, at) -> if (SystemClock.uptimeMillis() - at < PENDING_MS) return on }
        return key in home.lists
    }

    /** Shows the change at once, sends it to TMDB, then reloads the lists (or puts it back on failure). */
    private fun setList(list: String, show: Show, on: Boolean) {
        val key = show.listKey(list)
        pendingLists[key] = on to SystemClock.uptimeMillis()
        pendingShows[key] = show
        showListChange()
        val name = getString(
            when {
                list == Lists.FAVORITE -> R.string.list_favorites
                show.mediaType == MediaType.MOVIE -> R.string.list_watchlist
                else -> R.string.list_my_shows
            },
        )
        showNote(getString(if (on) R.string.note_added else R.string.note_removed, name))
        Workers.content.execute {
            val sent = runCatching { lists.set(list, show, on) }.isSuccess
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (sent) {
                    refreshContent(force = true)
                } else {
                    pendingLists.remove(key)
                    pendingShows.remove(key)
                    showListChange()
                    showNote(getString(R.string.note_failed))
                }
            }
        }
    }

    /** A list change (or its undoing) shown on the hero button, the home rows and the open tab. */
    private fun showListChange() {
        hero.refreshList()
        bindRows(home)
        keepRowInView()
        if (page == Page.HOME && area == Area.HOME) nav.refocus()
        if (isMedia(page)) {
            bindMedia(page, fresh = false)
            if (area == Area.PAGE) mediaPage.refocus()
        }
    }

    private fun showNote(text: String) {
        note.text = text
        note.fadeTo(1f, 200)
        main.removeCallbacks(hideNote)
        main.postDelayed(hideNote, NOTE_MS)
    }

    /** Signed out: the Library tab's QR sign-in. */
    private fun goSignIn() {
        main.removeCallbacks(pageSettle)
        enterTabs()
        tabBar.focusOn(Page.LIBRARY)
        showPage(Page.LIBRARY)
    }

    /**
     * The home rows: Up Next, the user's My Shows and Favorites (with changes still on their way
     * to TMDB), then the suggestions, which stay whatever the user saves. Empty rows are hidden.
     */
    private fun bindRows(c: HomeContent) {
        upNextAdapter.update(c.upNext.items)
        myShowsAdapter.update(withPending(Lists.WATCHLIST, MediaType.TV, c.myShows.items))
        favoritesAdapter.update(withPending(Lists.FAVORITE, MediaType.TV, c.favorites.items))
        popularAdapter.update(c.popular.items)
        acclaimedAdapter.update(c.acclaimed.items)
        section(R.id.section_up_next, R.id.title_up_next, R.id.sub_up_next, c.upNext, upNextAdapter.itemCount)
        section(R.id.section_my_shows, R.id.title_my_shows, R.id.sub_my_shows, c.myShows, myShowsAdapter.itemCount)
        section(R.id.section_favorites, R.id.title_favorites, R.id.sub_favorites, c.favorites, favoritesAdapter.itemCount)
        section(R.id.section_popular, R.id.title_popular, R.id.sub_popular, c.popular, popularAdapter.itemCount)
        section(R.id.section_acclaimed, R.id.title_acclaimed, R.id.sub_acclaimed, c.acclaimed, acclaimedAdapter.itemCount)
    }

    private fun section(id: Int, titleId: Int, subId: Int, row: Row<*>, count: Int) {
        findViewById<TextView>(titleId).text = row.title
        findViewById<TextView>(subId).text = row.subtitle
        findViewById<View>(id).visibility = if (count == 0) View.GONE else View.VISIBLE
    }

    /** Rows above may have come or gone: once laid out again, slide the focused row back into place. */
    private fun keepRowInView() {
        if (page != Page.HOME || nav.row < 2) return
        sections.post { scroll.animateToFinalPosition(rowOffset(nav.row)) }
    }

    /** The show at [col] in home row [row] (2 and down). */
    private fun rowShow(row: Int, col: Int): Show? = when (row) {
        2 -> upNextAdapter.items.getOrNull(col)?.show
        3 -> myShowsAdapter.items.getOrNull(col)
        4 -> favoritesAdapter.items.getOrNull(col)
        5 -> popularAdapter.items.getOrNull(col)
        6 -> acclaimedAdapter.items.getOrNull(col)
        else -> null
    }

    /**
     * How far the content slides up for [row]: its section's title near the top of the screen, but
     * never so far that the last section lifts off the bottom. The hero and app icons share the top.
     */
    private fun rowOffset(row: Int): Float {
        if (row < 2) return 0f
        val section = sections.getChildAt(row - 2)
        val top = sections.top + section.top
        val end = sections.top + sections.height
        return -minOf(top - Ui.px(SECTION_TOP), end - Ui.px(CONTENT_END)).coerceAtLeast(0).toFloat()
    }

    /** Signed in on the phone: load the user's own lists, and the Library turns into them. */
    private fun onSignedIn(@Suppress("UNUSED_PARAMETER") username: String) {
        refreshContent(force = true)
        main.postDelayed({
            if (page == Page.LIBRARY) {
                signIn.stop()
                // Shown again from scratch: it is a lists page now, not the sign-in page.
                page = Page.NONE
                showPage(Page.LIBRARY)
            }
        }, 1_800)
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
                val featured = tile.provider?.let { home.shelves[it] }
                shelf.bind(tile, featured, apps.size, animate = !rowChanged)
                backdrop.show((featured?.show ?: hero.current.show).backdrop, SETTLE_MS)
                restDim = if (featured != null) 0.06f else 0.6f
                previewDim = 0.06f
                featured?.show
            }
            2 -> behind(rowShow(row, col) ?: return, 0.72f, preview = 0.5f)
            else -> behind(rowShow(row, col) ?: return, 0.7f, preview = 0.42f)
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
        scroll.animateToFinalPosition(rowOffset(row))
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

    /**
     * The Home row shows the user's favorite apps (then Settings and All apps); the Apps tab shows
     * them all. The first time, the row starts from the apps it always showed first.
     */
    private fun showApps() {
        val saved = installed.favorites()
        favorites = (saved ?: apps.take(FIRST_FAVORITES).map { it.packageName }).toMutableList()
        if (apps.isNotEmpty()) {
            // Uninstalled apps drop off, so the row and the list stay in step.
            val pruned = favorites.retainAll(apps.map { it.packageName }.toSet())
            if (pruned || saved == null) installed.saveFavorites(favorites)
        }
        val settings = getString(R.string.tile_settings)
        val allApps = getString(R.string.tile_all_apps)
        appsAdapter.update(InstalledApps.homeTiles(apps, favorites, settings, allApps))
        gridAdapter.update(InstalledApps.tiles(apps, settings, allApps).filter { it.kind != AppTile.Kind.ALL_APPS })
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

    /** Opens the show's service. Deep links to the episode come next; for now it opens the app. */
    private fun openShow(show: Show, from: View) {
        val provider = show.provider
        if (provider == null) {
            Toast.makeText(this, if (show.service.isEmpty()) getString(R.string.not_streaming) else show.service, Toast.LENGTH_SHORT).show()
            return
        }
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
        /** Where a focused row's title lands, and the lowest the last row's end may rise to (mockup px). */
        const val SECTION_TOP = 296
        const val CONTENT_END = 940

        /** Lets focus rest on a card briefly before its backdrop decodes. */
        const val SETTLE_MS = 150L

        /** On Shows and Movies the whole screen is the title's art, so it waits a little longer to change. */
        const val MEDIA_SETTLE_MS = 320L

        /** How long focus rests before a preview starts: longer on the hero, as in the mockup. */
        const val HERO_PREVIEW_MS = 1500L
        const val ROW_PREVIEW_MS = 1200L

        /** Quiet time with a hero preview playing before the UI steps back. */
        const val AMBIENT_MS = 2800L

        const val TAB_SETTLE_MS = 260L

        /** Content refreshes at most this often on resume; TMDB caching does the rest. */
        const val REFRESH_MS = 60 * 1000L
        const val GRID_COLUMNS = 5

        /** How dark the frosted backdrop sits behind the other tabs' pages, and behind Shows and Movies. */
        const val PAGE_DIM = 0.8f
        const val SEARCH_DIM = 0.55f

        const val VOICE_REQUEST = 7

        /** How many apps the Home row starts with, before the user picks their own. */
        const val FIRST_FAVORITES = 6
        const val MEDIA_DIM = 0f

        /** How long focus rests on a title before its full record is fetched; how often shelves reload. */
        const val ENRICH_MS = 280L
        const val BROWSE_MS = 30 * 60 * 1000L

        /** How long OK is held before a show's menu opens. */
        const val HOLD_MS = 550L

        /** How long a list change sent to TMDB overrides the lists we fetch. */
        const val PENDING_MS = 2 * 60 * 1000L
        const val NOTE_MS = 2_200L

        const val REPEAT_MS = 90L
    }
}

/** The tab bar's items, in order. */
private object Page {
    const val SEARCH = 0
    const val HOME = 1
    const val LIBRARY = 2
    const val SHOWS = 3
    const val MOVIES = 4
    const val APPS = 5

    /** No page yet: makes [HomeActivity.showPage] build one afresh. */
    const val NONE = -1
}
