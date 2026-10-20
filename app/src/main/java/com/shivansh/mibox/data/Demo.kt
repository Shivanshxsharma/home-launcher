package com.shivansh.mibox.data

/** How a show's title is set: the mockup gives each show one of three type treatments. */
enum class TitleStyle(val scale: Float) {
    SERIF(1f),
    CONDENSED(0.92f),
    PLAIN(0.75f),
}

/** Streaming services, with their TV app packages and their TMDB provider ids. */
enum class Provider(val label: String, val color: Int, private val packages: List<String>, private val tmdbIds: List<Int>) {
    NETFLIX("Netflix", 0xFFE5363D.toInt(), listOf("com.netflix.ninja", "com.netflix.mediaclient"), listOf(8, 175)),
    PRIME("Prime Video", 0xFF2EA3F2.toInt(), listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"), listOf(119, 9)),
    HOTSTAR("JioHotstar", 0xFF6B7BFF.toInt(), listOf("in.startv.hotstar"), listOf(2336, 122)),
    APPLE_TV("Apple TV", 0xFFD9D9DE.toInt(), listOf("com.apple.atve.androidtv.appletv"), listOf(350, 2)),
    SONYLIV("SonyLIV", 0xFFA595FF.toInt(), listOf("com.sonyliv"), listOf(237)),
    ZEE5("ZEE5", 0xFFC77DFF.toInt(), listOf("com.graymatrix.did"), listOf(232));

    fun owns(pkg: String) = packages.any { pkg == it || pkg.startsWith("$it.") }

    companion object {
        fun forPackage(pkg: String): Provider? = entries.firstOrNull { it.owns(pkg) }

        fun forTmdb(id: Int): Provider? = entries.firstOrNull { id in it.tmdbIds }

        /** The services we filter TMDB's India listings by. */
        val tmdbIds: String = entries.joinToString("|") { it.tmdbIds.first().toString() }
    }
}

/** TMDB's two kinds of title, by the name its API paths use. */
enum class MediaType(val api: String) {
    TV("tv"),
    MOVIE("movie"),
}

data class Show(
    val id: String,
    val title: String,
    val style: TitleStyle,
    /** The service it streams on, when it is one we have an app for. */
    val provider: Provider?,
    /** The show's accent: glows and focus light take this color. */
    val tint: Int,
    /** Asset paths, or https image URLs for TMDB shows. */
    val backdrop: String,
    val poster: String?,
    val still: String,
    /** A short muted preview clip, if the show has one. */
    val clip: String? = null,
    /** Where it streams, named even when we have no app for it (e.g. "Crunchyroll", "HBO"). */
    val service: String = provider?.label ?: "",
    val tmdbId: Int = 0,
    val mediaType: MediaType = MediaType.TV,
    /** The title's own logo art (a transparent PNG), when TMDB has an English one. */
    val logo: String? = null,
    /** What the Shows and Movies tabs print above their posters. */
    val year: String = "",
    val genres: String = "",
    val overview: String = "",
    /** Running time for a film ("2h 10m"), seasons for a series ("3 seasons"). */
    val length: String = "",
    /** False until TMDB's full record (service, logo, length) has been read for it. */
    val detailed: Boolean = true,
    /** YouTube keys of its trailers, best first; the player moves on when one won't play here. */
    val trailers: List<String> = emptyList(),
) {
    val serviceColor: Int get() = provider?.color ?: 0xFF9A96A3.toInt()

    /** This title on a TMDB list, e.g. "watchlist:tv:1399". */
    fun listKey(list: String) = "$list:${mediaType.api}:$tmdbId"
}

/**
 * One strip of posters on the Shows, Movies or Library tab (a genre, or one of the user's lists);
 * [key] stays the same across reloads, and [empty] is what the tab says when it has no titles.
 */
data class MediaShelf(val key: String, val label: String, val items: List<Show>, val empty: String = "")

data class HeroSlide(
    val show: Show,
    val status: String,
    val next: String,
    val genre: String,
    val year: String,
    val rating: String,
    val play: String,
    val blurb: String,
)

/** [watched] of [total] episodes in the current season; [note] replaces the service in the caption (e.g. "Out Fri 24 Oct"). */
data class UpNext(val show: Show, val next: String, val badge: String, val watched: Int, val total: Int, val note: String? = null)

/** What the top shelf says when a provider's app is focused. */
data class ProviderShelf(val show: Show, val line: String)

/** One home row's words and cards. */
data class Row<T>(val title: String, val subtitle: String, val items: List<T>)

/** Everything the home screen shows, built from TMDB (or the demo shows offline). */
data class HomeContent(
    val hero: List<HeroSlide>,
    val upNext: Row<UpNext>,
    val myShows: Row<Show>,
    val favorites: Row<Show>,
    val shelves: Map<Provider, ProviderShelf>,
    val signedIn: Boolean,
    /** Suggestion rows, shown whatever is on the user's lists. */
    val popular: Row<Show> = Row("", "", emptyList()),
    val acclaimed: Row<Show> = Row("", "", emptyList()),
    /** This week's trending series and films, for the Shows and Movies tabs. */
    val trendingShows: List<Show> = emptyList(),
    val trendingMovies: List<Show> = emptyList(),
    /** The user's TMDB watchlists (empty when signed out). */
    val showWatchlist: List<Show> = emptyList(),
    val movieWatchlist: List<Show> = emptyList(),
    /** The user's TMDB favorites. */
    val showFavorites: List<Show> = emptyList(),
    val movieFavorites: List<Show> = emptyList(),
    /** Everything on the user's lists, as [Show.listKey]s. */
    val lists: Set<String> = emptySet(),
)

/**
 * Phase 1 content: the fictional shows painted for the design canvas.
 * Phase 2 replaces this with the user's TMDB watchlist and favorites.
 */
object Demo {
    private fun art(name: String) = "demo/$name.jpg"

    private fun show(
        id: String, title: String, style: TitleStyle, provider: Provider, tint: Long,
        backdrop: String, poster: String?, still: String, clip: String? = null,
    ) = Show(id, title, style, provider, tint.toInt(), art(backdrop), poster?.let(::art), art(still), clip?.let { "clips/clip-$it.mp4" })

    private val all = listOf(
        show("lowtide", "Low Tide", TitleStyle.SERIF, Provider.NETFLIX, 0xFFFFB067, "hero-low-tide", "poster-low-tide", "still-low-tide", "lowtide"),
        show("ridgeline", "Ridgeline", TitleStyle.CONDENSED, Provider.HOTSTAR, 0xFFF3B89A, "hero-ridgeline", "poster-ridgeline", "still-ridgeline", "ridgeline"),
        show("neon", "Neon Monsoon", TitleStyle.CONDENSED, Provider.PRIME, 0xFFFF3DB4, "hero-neon-monsoon", "poster-neon-monsoon", "still-neon-monsoon", "neon"),
        show("salt", "The Salt Road", TitleStyle.SERIF, Provider.APPLE_TV, 0xFFFF8A4B, "hero-salt-road", "poster-salt-road", "hero-salt-road", "salt"),
        show("orbit", "Far Orbit", TitleStyle.CONDENSED, Provider.NETFLIX, 0xFF7FD8FF, "hero-far-orbit", "poster-far-orbit", "hero-far-orbit", "orbit"),
        show("northern", "Northern Line", TitleStyle.SERIF, Provider.PRIME, 0xFF3DF5A8, "still-northern-line", "poster-northern-line", "still-northern-line", "northern"),
        show("canopy", "Canopy", TitleStyle.SERIF, Provider.HOTSTAR, 0xFFE8E39A, "still-canopy", "poster-canopy", "still-canopy", "canopy"),
        show("lantern", "Lantern Street", TitleStyle.SERIF, Provider.SONYLIV, 0xFFFF8A3D, "still-lantern-street", "poster-lantern-street", "still-lantern-street", "lantern"),
        show("bluehour", "Blue Hour", TitleStyle.CONDENSED, Provider.NETFLIX, 0xFF86A8FF, "still-blue-hour", null, "still-blue-hour"),
        show("saffron", "Saffron", TitleStyle.SERIF, Provider.ZEE5, 0xFFFF8A8A, "still-saffron", null, "still-saffron"),
        show("harbour", "Harbour Lights", TitleStyle.SERIF, Provider.APPLE_TV, 0xFFF4B46E, "still-harbour-lights", null, "still-harbour-lights"),
        show("deep", "Deep Field", TitleStyle.CONDENSED, Provider.PRIME, 0xFFE8C27A, "still-deep-field", null, "still-deep-field"),
    ).associateBy { it.id }

    private fun s(id: String) = all.getValue(id)

    /** The hero cycles the user's shows in their order. */
    val hero = listOf(
        HeroSlide(
            s("lowtide"), "New episode", "S2 · E6", "Drama · Mystery", "2024", "U/A 16+", "Play S2 E6",
            "A ferry captain finds out the tide tables of her island town have been wrong for thirty years — and that somebody needed them to be.",
        ),
        HeroSlide(
            s("ridgeline"), "New season", "S3 · E1", "Thriller", "2025", "U/A 13+", "Play S3 E1",
            "Two rangers, one missing hiker, and a mountain pass that closes for the winter in nine days.",
        ),
        HeroSlide(
            s("neon"), "Continue", "S1 · E4", "Crime · Noir", "2025", "A", "Play S1 E4",
            "The wettest week Mumbai has ever recorded. A night-shift cabbie becomes the only witness who will not stop talking.",
        ),
        HeroSlide(
            s("salt"), "Finale on Friday", "S1 · E7", "Drama", "2025", "U/A 16+", "Play S1 E7",
            "A caravan crosses the Rann of Kutch carrying something none of its riders will name out loud.",
        ),
        HeroSlide(
            s("orbit"), "Just added", "S1 · E1", "Sci-fi", "2026", "U/A 13+", "Play S1 E1",
            "The last crew on a failing station has one seat on the ride home, and six good reasons to take it.",
        ),
    )

    val upNext = listOf(
        UpNext(s("neon"), "S1 · E4", "", 3, 8),
        UpNext(s("lowtide"), "S2 · E6", "NEW", 5, 8),
        UpNext(s("northern"), "S1 · E8", "", 7, 10),
        UpNext(s("canopy"), "S1 · E5", "", 4, 6),
        UpNext(s("ridgeline"), "S3 · E1", "NEW SEASON", 0, 8),
        UpNext(s("lantern"), "S2 · E3", "", 2, 9),
    )

    val myShows = listOf("lowtide", "ridgeline", "neon", "salt", "orbit", "northern", "canopy", "lantern").map(::s)

    val favorites = listOf("bluehour", "saffron", "harbour", "deep").map(::s)

    val shelves = mapOf(
        Provider.NETFLIX to ProviderShelf(s("lowtide"), "New episode · S2 E6     Also here: Far Orbit, Blue Hour"),
        Provider.PRIME to ProviderShelf(s("neon"), "Continue · S1 E4     Also here: Northern Line, Deep Field"),
        Provider.HOTSTAR to ProviderShelf(s("ridgeline"), "New season · S3 E1     Also here: Canopy"),
        Provider.APPLE_TV to ProviderShelf(s("salt"), "Finale on Friday · S1 E7     Also here: Harbour Lights"),
        Provider.SONYLIV to ProviderShelf(s("lantern"), "Next · S2 E3"),
    )

    fun content() = HomeContent(
        hero = hero,
        upNext = Row("Up Next", "New episodes from your shows", upNext),
        myShows = Row("My Shows", "In your order", myShows),
        favorites = Row("Favorites", "Shows you keep coming back to", favorites),
        shelves = shelves,
        signedIn = false,
        trendingShows = myShows,
    )
}
