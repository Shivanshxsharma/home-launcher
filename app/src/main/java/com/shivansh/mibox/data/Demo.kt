package com.shivansh.mibox.data

/** How a show's title is set: the mockup gives each show one of three type treatments. */
enum class TitleStyle(val scale: Float) {
    SERIF(1f),
    CONDENSED(0.92f),
    PLAIN(0.75f),
}

/** Streaming services, with the TV app packages that belong to each. */
enum class Provider(val label: String, val color: Int, private val packages: List<String>) {
    NETFLIX("Netflix", 0xFFE5363D.toInt(), listOf("com.netflix.ninja", "com.netflix.mediaclient")),
    PRIME("Prime Video", 0xFF2EA3F2.toInt(), listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")),
    HOTSTAR("JioHotstar", 0xFF6B7BFF.toInt(), listOf("in.startv.hotstar")),
    APPLE_TV("Apple TV", 0xFFD9D9DE.toInt(), listOf("com.apple.atve.androidtv.appletv")),
    SONYLIV("SonyLIV", 0xFFA595FF.toInt(), listOf("com.sonyliv")),
    ZEE5("ZEE5", 0xFFC77DFF.toInt(), listOf("com.graymatrix.did"));

    fun owns(pkg: String) = packages.any { pkg == it || pkg.startsWith("$it.") }

    companion object {
        fun forPackage(pkg: String): Provider? = entries.firstOrNull { it.owns(pkg) }
    }
}

data class Show(
    val id: String,
    val title: String,
    val style: TitleStyle,
    val provider: Provider,
    /** The show's accent: glows and focus light take this color. */
    val tint: Int,
    /** Asset paths. */
    val backdrop: String,
    val poster: String?,
    val still: String,
    /** A short muted preview clip, if the show has one. */
    val clip: String? = null,
)

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

data class UpNext(val show: Show, val next: String, val badge: String, val watched: Int, val total: Int)

/** What the top shelf says when a provider's app is focused. */
data class ProviderShelf(val show: Show, val line: String)

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
}
