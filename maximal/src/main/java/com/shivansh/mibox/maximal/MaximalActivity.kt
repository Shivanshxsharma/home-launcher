package com.shivansh.mibox.maximal

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.shivansh.mibox.data.AppEntry
import com.shivansh.mibox.data.Demo
import com.shivansh.mibox.data.HeroSlide
import com.shivansh.mibox.data.HomeContent
import com.shivansh.mibox.data.InstalledApps
import com.shivansh.mibox.data.Provider
import com.shivansh.mibox.data.Show
import com.shivansh.mibox.data.YouTube
import com.shivansh.mibox.data.tmdb.Account
import com.shivansh.mibox.data.tmdb.ApiKey
import com.shivansh.mibox.data.tmdb.Catalog
import com.shivansh.mibox.data.tmdb.Lists
import com.shivansh.mibox.data.tmdb.Tmdb
import com.shivansh.mibox.maximal.ui.AppArtSource
import com.shivansh.mibox.maximal.ui.ArtSlot
import com.shivansh.mibox.maximal.ui.Backdrop
import com.shivansh.mibox.maximal.ui.Fonts
import com.shivansh.mibox.maximal.ui.Frost
import com.shivansh.mibox.maximal.ui.ImageFiles
import com.shivansh.mibox.maximal.ui.Keyboard
import com.shivansh.mibox.maximal.ui.Motion
import com.shivansh.mibox.maximal.ui.Palette
import com.shivansh.mibox.maximal.ui.Pills
import com.shivansh.mibox.maximal.ui.Previews
import com.shivansh.mibox.maximal.ui.Profile
import com.shivansh.mibox.maximal.ui.Rail
import com.shivansh.mibox.maximal.ui.RailItem
import com.shivansh.mibox.maximal.ui.Sidebar
import com.shivansh.mibox.maximal.ui.TitleBlock
import com.shivansh.mibox.maximal.ui.Ui
import com.shivansh.mibox.maximal.ui.Workers
import com.shivansh.mibox.maximal.ui.fadeTo
import com.shivansh.mibox.maximal.ui.scoreText

/**
 * Maximal: one title at a time, full screen. A sidebar of sections on the left, a rail of posters
 * on the right, the title over the art at the bottom left, its storyline at the bottom right and a
 * round play button in the middle. The remote moves across three zones (sidebar, centre, rail):
 * up and down inside a zone, left and right between them. Content and TMDB set-up are the Home
 * launcher's, shared.
 */
class MaximalActivity : Activity(), Previews.Listener, Profile.Listener {
    private enum class Zone { SIDEBAR, CENTRE, RAIL, PROFILE }

    private enum class Section(val item: Sidebar.Item) {
        HOME(Sidebar.Item.HOME),
        SHOWS(Sidebar.Item.SHOWS),
        MOVIES(Sidebar.Item.MOVIES),
        APPS(Sidebar.Item.APPS),
        SEARCH(Sidebar.Item.SEARCH),
    }

    private lateinit var backdrop: Backdrop
    private lateinit var previews: Previews
    private lateinit var sidebar: Sidebar
    private lateinit var rail: Rail
    private lateinit var words: TitleBlock
    private lateinit var profile: Profile
    private lateinit var keyboard: Keyboard
    private lateinit var actions: Pills
    private lateinit var appArt: AppArtSource

    private lateinit var sidebarView: View
    private lateinit var railView: View
    private lateinit var panels: List<View>
    private lateinit var play: View
    private lateinit var playIcon: ImageView
    private lateinit var actionsView: View
    private lateinit var centreColumn: View
    private lateinit var searchColumn: View
    private lateinit var searchQuery: TextView
    private lateinit var centreDim: View
    private lateinit var caption: TextView
    private lateinit var empty: TextView
    private lateinit var note: TextView

    private lateinit var apiKey: ApiKey
    private lateinit var tmdb: Tmdb
    private lateinit var account: Account
    private lateinit var catalog: Catalog
    private lateinit var lists: Lists
    private lateinit var installed: InstalledApps

    /** TMDB content (the cached copy first, then fresh), or the demo shows before TMDB is connected. */
    private var content: HomeContent = Demo.content()
    private var apps: List<AppEntry> = emptyList()
    private var lastRefresh = 0L

    private var zone = Zone.RAIL
    private var zoneBeforeProfile = Zone.SIDEBAR
    private var section = Section.HOME

    /** 0 is the play button; 1 and up are the actions under it. */
    private var centreFocus = 0

    private var query = ""
    private var asked = ""
    private var results: List<Show>? = null
    private var resultsFailed = false

    /** Full records fetched for titles that came into focus, by show id. */
    private val enriched = HashMap<String, Show>()

    /** Watchlist changes sent to TMDB that a fetched list may not show yet. */
    private val pending = HashMap<String, Boolean>()

    private var ambient = false
    private var resumed = false
    private val main = Handler(Looper.getMainLooper())
    private val toAmbient = Runnable { enterAmbient() }
    private val hideNote = Runnable { note.fadeTo(0f, 400) }
    private val sectionSettle = Runnable { sidebarSection()?.let { if (it != section) showSection(it) } }
    private val enrichSoon = Runnable { enrichFocused() }
    private val runSearch = Runnable { search() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Fonts.init(this)
        setContentView(R.layout.activity_maximal)
        ImageFiles.init(this)

        apiKey = ApiKey(this)
        tmdb = Tmdb(this, apiKey)
        tmdb.onKeyRefused = { runOnUiThread { if (!isDestroyed) onKeyRefused() } }
        account = Account(this)
        catalog = Catalog(tmdb, account)
        lists = Lists(tmdb, account)
        installed = InstalledApps(this)

        sidebarView = findViewById(R.id.sidebar)
        railView = findViewById(R.id.rail)
        panels = listOf(R.id.veil_left, R.id.veil_right, R.id.edge_left, R.id.edge_right).map { findViewById<View>(it) }
        play = findViewById(R.id.play)
        playIcon = findViewById(R.id.play_icon)
        actionsView = findViewById(R.id.actions)
        centreColumn = findViewById(R.id.centre_column)
        searchColumn = findViewById(R.id.search_column)
        searchQuery = findViewById(R.id.search_query)
        centreDim = findViewById(R.id.centre_dim)
        caption = findViewById(R.id.rail_caption)
        empty = findViewById(R.id.rail_empty)
        note = findViewById(R.id.note)
        note.background = GradientDrawable().apply {
            cornerRadius = Ui.px(30f)
            setColor(Palette.alpha(Palette.NIGHT, 0.85f))
            setStroke(Ui.px(2), Palette.alpha(0xFFFFFF, 0.16f))
        }

        backdrop = Backdrop(findViewById(R.id.backdrop), findViewById<ArtSlot>(R.id.slot_lower), findViewById(R.id.slot_upper), assets) {
            // The art covers the screen from here on; the window background would be pure overdraw.
            window.setBackgroundDrawable(null)
        }
        backdrop.setPanels(resources.getDimensionPixelSize(R.dimen.sidebar_width), resources.getDimensionPixelSize(R.dimen.rail_width))
        previews = Previews(findViewById<SurfaceView>(R.id.preview_surface), assets, this)
        sidebar = Sidebar(findViewById(R.id.sidebar))
        appArt = AppArtSource(this, Ui.px(Rail.TILE_W), Ui.px(Rail.TILE_H))
        rail = Rail(findViewById<RecyclerView>(R.id.rail_list), findViewById(R.id.rail_track), findViewById(R.id.rail_thumb), assets, appArt)
        words = TitleBlock(findViewById(R.id.title_block), findViewById(R.id.storyline))
        actions = Pills(findViewById<LinearLayout>(R.id.actions))
        keyboard = findViewById(R.id.keyboard)
        keyboard.voice = packageManager.resolveActivity(voiceIntent(), 0) != null
        keyboard.onKey = ::onKeyboard
        profile = Profile(findViewById(R.id.profile_panel), tmdb, apiKey, account, this)

        content = catalog.load(offline = true) ?: Demo.content()
        apps = installed.cached()
        showSection(Section.HOME)
        setZone(Zone.RAIL)
        if (!apiKey.present) main.postDelayed({ showNote(getString(R.string.empty_movies)) }, 1_500)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshContent()
        refreshApps()    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(toAmbient)
        exitAmbient()
        previews.release()
        if (profile.isOpen) closeProfile()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: back to the start.
        if (hasWindowFocus() && intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (profile.isOpen) closeProfile()
            showSection(Section.HOME)
            setZone(Zone.RAIL)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) rail.trim()
    }

    // ---- The remote ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        // While a preview plays with the panels away, the first press only brings them back.
        if (ambient && event.keyCode != KeyEvent.KEYCODE_HOME) {
            if (down && event.repeatCount == 0) {
                exitAmbient()
                scheduleAmbient()
            }
            return true
        }
        if (zone == Zone.PROFILE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (down && event.repeatCount == 0) closeProfile()
            } else {
                profile.key(event)
            }
            return true
        }
        if (section == Section.SEARCH && typeIntoSearch(event)) return true
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        val move = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> 0 to -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0 to 1
            KeyEvent.KEYCODE_DPAD_UP -> -1 to 0
            KeyEvent.KEYCODE_DPAD_DOWN -> 1 to 0
            else -> null
        }
        when {
            move != null -> {
                if (down) {
                    move(move.first, move.second)
                    scheduleAmbient()
                }
                return true
            }
            ok -> {
                if (down) ok(event.repeatCount)
                return true
            }
            event.keyCode == KeyEvent.KEYCODE_BACK -> {
                if (down && event.repeatCount == 0) {
                    if (back()) return true
                } else {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun move(dRow: Int, dCol: Int) {
        when (zone) {
            Zone.SIDEBAR -> when {
                dRow != 0 -> if (sidebar.move(dRow)) {
                    main.removeCallbacks(sectionSettle)
                    if (sidebarSection() != null) main.postDelayed(sectionSettle, SECTION_SETTLE_MS)
                }
                dCol > 0 -> setZone(Zone.CENTRE)
            }
            Zone.CENTRE -> if (section == Section.SEARCH) {
                if (!keyboard.move(dRow, dCol)) {
                    if (dCol < 0) setZone(Zone.SIDEBAR)
                    if (dCol > 0 && rail.items.isNotEmpty()) setZone(Zone.RAIL)
                }
            } else {
                when {
                    dRow > 0 && centreFocus < actions.size -> setCentreFocus(centreFocus + 1)
                    dRow < 0 && centreFocus > 0 -> setCentreFocus(centreFocus - 1)
                    dCol < 0 -> setZone(Zone.SIDEBAR)
                    dCol > 0 && rail.items.isNotEmpty() -> setZone(Zone.RAIL)
                }
            }
            Zone.RAIL -> when {
                dRow != 0 -> if (rail.move(dRow)) onRailFocus()
                dCol < 0 -> setZone(Zone.CENTRE)
            }
            Zone.PROFILE -> Unit
        }
    }

    private fun ok(repeat: Int) {
        when (zone) {
            Zone.SIDEBAR -> if (repeat == 0) when (sidebar.focusedItem) {
                Sidebar.Item.PROFILE -> openProfile()
                Sidebar.Item.SETTINGS -> start(Intent(Settings.ACTION_SETTINGS), sidebarView)
                else -> {
                    main.removeCallbacks(sectionSettle)
                    sidebarSection()?.let { if (it != section) showSection(it) }
                    setZone(if (section == Section.SEARCH) Zone.CENTRE else if (rail.items.isNotEmpty()) Zone.RAIL else Zone.CENTRE)
                }
            }
            Zone.CENTRE -> when {
                section == Section.SEARCH -> {
                    // Held on Delete, it keeps deleting.
                    if (repeat == 0 || keyboard.current.action == Keyboard.Action.DELETE) keyboard.press()
                }
                repeat > 0 -> Unit
                centreFocus == 0 -> pressPlay()
                else -> actions.press()
            }
            Zone.RAIL -> if (repeat == 0) when (val item = rail.items.getOrNull(rail.focused)) {
                // A title's "page" is this screen: its art, words and actions, with Play in focus.
                is RailItem.Title -> {
                    centreFocus = 0
                    setZone(Zone.CENTRE)
                }
                is RailItem.App -> launch(item.entry, railView)
                null -> Unit
            }
            Zone.PROFILE -> Unit
        }
    }

    /** Back climbs: the rail or centre to the sidebar, another section to Home. True when used. */
    private fun back(): Boolean = when {
        zone == Zone.RAIL || zone == Zone.CENTRE -> {
            setZone(Zone.SIDEBAR)
            true
        }
        section != Section.HOME -> {
            showSection(Section.HOME)
            true
        }
        // As the Home app, Back has nowhere to go; opened as a regular app it exits.
        else -> isDefaultHome()
    }

    private fun setZone(next: Zone) {
        zone = next
        sidebar.setLit(next == Zone.SIDEBAR)
        rail.setLit(next == Zone.RAIL)
        if (next == Zone.CENTRE && section != Section.SEARCH) centreFocus = centreFocus.coerceIn(0, actions.size)
        paintCentre()
    }

    private fun setCentreFocus(index: Int) {
        centreFocus = index
        paintCentre()
    }

    /** The play button is cream while it has the remote; the actions under it show only then. */
    private fun paintCentre() {
        val inCentre = zone == Zone.CENTRE && section != Section.SEARCH
        val playLit = inCentre && centreFocus == 0
        play.background = Frost.circle(playLit)
        playIcon.setColorFilter(if (playLit) Palette.INK_DARK else Palette.INK)
        val scale = if (playLit) 1.1f else 1f
        play.animate().scaleX(scale).scaleY(scale).setDuration(300).setStartDelay(0).setInterpolator(Motion.LIFT)
        actions.focus(if (inCentre) centreFocus - 1 else -1)
        actionsView.fadeTo(if (inCentre && actions.size > 0) 1f else 0f, 220)
        keyboard.setLit(zone == Zone.CENTRE && section == Section.SEARCH)
    }

    // ---- Sections and the rail ----

    private fun sidebarSection(): Section? = Section.entries.firstOrNull { it.item == sidebar.focusedItem }

    private fun showSection(next: Section) {
        val from = section
        section = next
        sidebar.select(next.item)
        val searching = next == Section.SEARCH
        centreColumn.fadeTo(if (searching) 0f else 1f, 240)
        searchColumn.fadeTo(if (searching) 1f else 0f, 240)
        centreDim.fadeTo(if (searching) 1f else 0f, 300)
        words.setShown(title = !searching, storyline = !searching)
        if (searching && from != Section.SEARCH) showQuery()
        fillRail(keep = false)
        paintCentre()
    }

    /** Lists the section in the rail, with its caption, or a line when there's nothing to list. */
    private fun fillRail(keep: Boolean) {
        val items: List<RailItem> = when (section) {
            Section.HOME -> myShows().map { RailItem.Title(it) }
            Section.SHOWS -> content.trendingShows.map { RailItem.Title(it) }
            Section.MOVIES -> content.trendingMovies.map { RailItem.Title(it) }
            Section.APPS -> apps.map { RailItem.App(it) }
            Section.SEARCH -> (results ?: trendingMix()).map { RailItem.Title(it) }
        }.map { if (it is RailItem.Title) RailItem.Title(enriched[it.show.id] ?: it.show) else it }
        val focusedBefore = rail.items.getOrNull(rail.focused)
        rail.set(items, keep)
        caption.setText(
            when (section) {
                Section.HOME -> if (content.myShows.items.isNotEmpty()) R.string.caption_my_shows else R.string.caption_on_your_apps
                Section.SHOWS -> R.string.caption_shows
                Section.MOVIES -> R.string.caption_movies
                Section.APPS -> R.string.caption_apps
                Section.SEARCH -> if (results != null) R.string.caption_results else R.string.caption_trending
            },
        )
        val line = when {
            items.isNotEmpty() -> null
            section == Section.SEARCH && resultsFailed -> getString(R.string.empty_offline)
            section == Section.SEARCH -> getString(R.string.empty_search, asked)
            !apiKey.present || section == Section.MOVIES -> getString(R.string.empty_movies)
            else -> getString(R.string.empty_offline)
        }
        empty.text = line
        empty.visibility = if (line == null) View.GONE else View.VISIBLE
        if (items.isEmpty() && zone == Zone.RAIL) setZone(Zone.SIDEBAR)
        // A refresh that keeps the same title in focus leaves the screen alone.
        if (!keep || rail.items.getOrNull(rail.focused) != focusedBefore) onRailFocus()
    }

    /** The user's shows (their TMDB watchlist), or what's on their apps when the list is empty. */
    private fun myShows(): List<Show> = content.myShows.items.ifEmpty { content.hero.map { it.show } }

    private fun trendingMix(): List<Show> {
        val shows = content.trendingShows.take(10)
        val films = content.trendingMovies.take(10)
        return (0 until maxOf(shows.size, films.size)).flatMap { listOfNotNull(shows.getOrNull(it), films.getOrNull(it)) }
    }

    /** A new item came into focus on the rail: its art, its words, its actions, and soon a preview. */
    private fun onRailFocus() {
        main.removeCallbacks(enrichSoon)
        when (val item = rail.items.getOrNull(rail.focused)) {
            is RailItem.Title -> {
                val show = item.show
                backdrop.show(show.backdrop, ART_SETTLE_MS)
                words.show(wordsFor(show), fresh = true)
                setActions(show)
                previews.cue(show.clip, PREVIEW_MS)
                if (!show.detailed && show.tmdbId > 0) main.postDelayed(enrichSoon, ENRICH_MS)
            }
            is RailItem.App -> {
                val featured = Provider.forPackage(item.entry.packageName)?.let { content.shelves[it] }
                featured?.show?.let { backdrop.show(it.backdrop, ART_SETTLE_MS) }
                words.show(
                    TitleBlock.Words(
                        getString(R.string.app_tag),
                        item.entry.label,
                        getString(R.string.app_open),
                        featured?.line?.replace("     ", " · ").orEmpty(),
                    ),
                    fresh = true,
                )
                actions.set(emptyList())
                previews.cue(featured?.show?.clip, PREVIEW_MS)
            }
            null -> {
                actions.set(emptyList())
                previews.cue(null, 0)
                if (section != Section.SEARCH) words.show(TitleBlock.Words("", getString(navLabel(section)), "", ""), fresh = true)
            }
        }
        if (zone == Zone.CENTRE) setCentreFocus(centreFocus.coerceIn(0, actions.size))
    }

    private fun navLabel(s: Section) = when (s) {
        Section.HOME -> R.string.nav_home
        Section.SHOWS -> R.string.nav_shows
        Section.MOVIES -> R.string.nav_movies
        Section.APPS -> R.string.nav_apps
        Section.SEARCH -> R.string.nav_search
    }

    /** The demo shows keep their words in the hero slides; TMDB titles carry their own. */
    private fun slideFor(show: Show): HeroSlide? = content.hero.firstOrNull { it.show.id == show.id }

    private fun wordsFor(show: Show): TitleBlock.Words {
        val slide = slideFor(show)
        val tags = show.genres.ifEmpty { slide?.genre.orEmpty() }.uppercase().replace(" · ", "  ·  ")
        val runtime = SpannableStringBuilder().apply {
            for (part in listOf(scoreText(show), show.year.ifEmpty { slide?.year.orEmpty() }, runtimeOf(show, slide), show.service)) {
                if (part.isEmpty()) continue
                if (isNotEmpty()) append("  ·  ")
                append(part)
            }
        }
        return TitleBlock.Words(tags, show.title, runtime, show.overview.ifEmpty { slide?.blurb.orEmpty() })
    }

    /** "2h 21m" from TMDB reads "2h 21 mins", as in the reference; series keep "3 seasons". */
    private fun runtimeOf(show: Show, slide: HeroSlide?): String {
        val length = show.length
        Regex("""^(\d+)h (\d+)m$""").find(length)?.let { m ->
            return getString(R.string.hours_minutes, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        Regex("""^(\d+)h$""").find(length)?.let { return getString(R.string.hours, it.groupValues[1].toInt()) }
        Regex("""^(\d+)m$""").find(length)?.let { return getString(R.string.minutes, it.groupValues[1].toInt()) }
        return length.ifEmpty { slide?.next.orEmpty() }
    }

    /** The actions under Play: the trailer, and the user's list. */
    private fun setActions(show: Show) {
        val list = ArrayList<Pills.Pill>()
        if (show.trailers.isNotEmpty()) {
            list += Pills.Pill(R.drawable.ic_film, getString(R.string.trailer)) {
                // In the YouTube app when there is one; its player is smoother than the embedded one.
                if (!YouTube.play(this, show.trailers.first())) {
                    startActivity(TrailerActivity.intent(this, show.title, show.trailers))
                    @Suppress("DEPRECATION")
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
            }
        }
        if (show.tmdbId > 0) {
            val on = inList(show)
            list += Pills.Pill(if (on) R.drawable.ic_check else R.drawable.ic_plus, getString(if (on) R.string.in_list else R.string.add_to_list)) {
                toggleList(show)
            }
        }
        actions.set(list)
    }

    private fun enrichFocused() {
        val index = rail.focused
        val show = (rail.items.getOrNull(index) as? RailItem.Title)?.show ?: return
        Workers.content.execute {
            val rich = runCatching { catalog.enrich(show) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || rich == null || !rich.detailed) return@runOnUiThread
                enriched[show.id] = rich
                if ((rail.items.getOrNull(index) as? RailItem.Title)?.show?.id != show.id) return@runOnUiThread
                rail.update(index, RailItem.Title(rich))
                if (rail.focused == index) {
                    words.show(wordsFor(rich), fresh = false)
                    setActions(rich)
                    paintCentre()
                }
            }
        }
    }

    private fun pressPlay() {
        when (val item = rail.items.getOrNull(rail.focused)) {
            is RailItem.Title -> openShow(item.show, play)
            is RailItem.App -> launch(item.entry, play)
            null -> Unit
        }
    }

    // ---- Lists ----

    private fun inList(show: Show): Boolean {
        val key = show.listKey(Lists.WATCHLIST)
        return pending[key] ?: (key in content.lists)
    }

    private fun toggleList(show: Show) {
        if (!account.signedIn) {
            openProfile()
            return
        }
        val key = show.listKey(Lists.WATCHLIST)
        val on = !inList(show)
        pending[key] = on
        setActions(show)
        paintCentre()
        showNote(getString(if (on) R.string.note_added else R.string.note_removed))
        Workers.content.execute {
            val sent = runCatching { lists.set(Lists.WATCHLIST, show, on) }.isSuccess
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (sent) {
                    refreshContent(force = true)
                } else {
                    pending.remove(key)
                    showNote(getString(R.string.note_failed))
                }
                (rail.items.getOrNull(rail.focused) as? RailItem.Title)?.show?.let {
                    setActions(it)
                    paintCentre()
                }
            }
        }
    }

    // ---- Search ----

    private fun onKeyboard(key: Keyboard.Key) {
        when (key.action) {
            Keyboard.Action.CHAR -> setQuery(query + key.label)
            Keyboard.Action.SPACE -> if (query.isNotEmpty() && !query.endsWith(" ")) setQuery("$query ")
            Keyboard.Action.DELETE -> if (query.isNotEmpty()) setQuery(query.dropLast(1))
            Keyboard.Action.CLEAR -> setQuery("")
            Keyboard.Action.VOICE -> listen()
        }
    }

    private fun setQuery(text: String) {
        query = text.take(MAX_QUERY)
        showQuery()
        main.removeCallbacks(runSearch)
        if (query.isBlank()) {
            asked = ""
            results = null
            resultsFailed = false
            fillRail(keep = false)
        } else {
            main.postDelayed(runSearch, SEARCH_PAUSE_MS)
        }
    }

    private fun showQuery() {
        if (query.isEmpty()) {
            searchQuery.text = getString(R.string.search_placeholder)
            searchQuery.setTextColor(Palette.QUIET)
        } else {
            searchQuery.text = query
            searchQuery.setTextColor(Palette.INK)
        }
    }

    private fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        asked = q
        Workers.content.execute {
            val found = runCatching { catalog.search(q) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || q != asked) return@runOnUiThread
                results = found.orEmpty()
                resultsFailed = found == null
                if (section == Section.SEARCH) fillRail(keep = false)
            }
        }
    }

    /** Letters, digits and Delete from a keyboard or a phone remote, typed straight into Search. */
    private fun typeIntoSearch(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode == KeyEvent.KEYCODE_DEL) {
            if (query.isNotEmpty()) setQuery(query.dropLast(1))
            return true
        }
        val char = event.unicodeChar.toChar()
        if (event.unicodeChar == 0 || !(char.isLetterOrDigit() || char == ' ')) return false
        setQuery(query + char.lowercaseChar())
        return true
    }

    private fun voiceIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_voice_prompt))

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
        setQuery(heard.lowercase())
    }

    // ---- Profile ----

    private fun openProfile() {
        zoneBeforeProfile = if (zone == Zone.PROFILE) zoneBeforeProfile else zone
        zone = Zone.PROFILE
        sidebar.setLit(false)
        rail.setLit(false)
        paintCentre()
        previews.cue(null, 0)
        centreColumn.fadeTo(0f, 200)
        searchColumn.fadeTo(0f, 200)
        centreDim.fadeTo(1f, 260)
        words.setShown(title = false, storyline = false)
        profile.open()
    }

    private fun closeProfile() {
        profile.close()
        val searching = section == Section.SEARCH
        centreColumn.fadeTo(if (searching) 0f else 1f, 240)
        searchColumn.fadeTo(if (searching) 1f else 0f, 240)
        centreDim.fadeTo(if (searching) 1f else 0f, 260)
        words.setShown(title = !searching, storyline = !searching)
        setZone(if (zoneBeforeProfile == Zone.PROFILE) Zone.SIDEBAR else zoneBeforeProfile)
    }

    override fun onKeyConnected(key: String) {
        apiKey.save(key)
        enriched.clear()
        showNote(getString(R.string.note_connected))
        refreshContent(force = true)
    }

    override fun onSignedIn(name: String) {
        showNote(getString(R.string.note_signed_in, name))
        refreshContent(force = true)
    }

    override fun onSignedOut() {
        pending.clear()
        showNote(getString(R.string.note_signed_out))
        refreshContent(force = true)
    }

    private fun onKeyRefused() = showNote(getString(R.string.note_key_refused))

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
        pending.entries.removeAll { (key, on) -> (key in fresh.lists) == on }
        if (section != Section.APPS) fillRail(keep = true)
    }

    private fun refreshApps() {
        Workers.misc.execute {
            val fresh = installed.query()
            runOnUiThread {
                if (isDestroyed || fresh == apps) return@runOnUiThread
                apps = fresh
                installed.save(fresh)
                if (section == Section.APPS) fillRail(keep = true)
            }
            appArt.prune(fresh)
        }
    }

    // ---- Previews and ambient mode ----

    override fun onPreviewShown() {
        backdrop.setArtShown(false)
        scheduleAmbient()
    }

    override fun onPreviewGone(finished: Boolean) {
        main.removeCallbacks(toAmbient)
        exitAmbient()
        backdrop.setArtShown(true)
    }

    private fun scheduleAmbient() {
        main.removeCallbacks(toAmbient)
        if (previews.isShowing && zone != Zone.PROFILE && section != Section.SEARCH) main.postDelayed(toAmbient, AMBIENT_MS)
    }

    /** The preview has the screen: both panels and the centre step away; the title stays. */
    private fun enterAmbient() {
        if (ambient || !previews.isShowing) return
        ambient = true
        for (view in panels + listOf(sidebarView, railView, centreColumn)) view.fadeTo(0f, 700)
        words.setShown(title = true, storyline = false)
    }

    private fun exitAmbient() {
        if (!ambient) return
        ambient = false
        for (view in panels + listOf(sidebarView, railView)) view.fadeTo(1f, 400)
        if (section != Section.SEARCH) centreColumn.fadeTo(1f, 400)
        words.setShown(title = section != Section.SEARCH, storyline = section != Section.SEARCH)
    }

    // ---- Opening things ----

    private fun showNote(text: String) {
        note.text = text
        note.fadeTo(1f, 200)
        main.removeCallbacks(hideNote)
        main.postDelayed(hideNote, NOTE_MS)
    }

    /** Opens the show's service. */
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

    private companion object {
        /** Resting on a section in the sidebar for this long shows it, as on tvOS. */
        const val SECTION_SETTLE_MS = 320L

        /** Focus rests this long before the art changes, its details load, and its preview starts. */
        const val ART_SETTLE_MS = 220L
        const val ENRICH_MS = 350L
        const val PREVIEW_MS = 1_500L

        /** Quiet time with a preview playing before the panels step away. */
        const val AMBIENT_MS = 3_000L
        const val REFRESH_MS = 60 * 1000L
        const val NOTE_MS = 2_400L
        const val SEARCH_PAUSE_MS = 380L
        const val MAX_QUERY = 40
        const val VOICE_REQUEST = 7
    }
}
