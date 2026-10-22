package com.shivansh.mibox.data.tmdb

import android.os.Process
import androidx.annotation.WorkerThread
import com.shivansh.mibox.data.HeroSlide
import com.shivansh.mibox.data.HomeContent
import com.shivansh.mibox.data.MediaType
import com.shivansh.mibox.data.Provider
import com.shivansh.mibox.data.ProviderShelf
import com.shivansh.mibox.data.Row
import com.shivansh.mibox.data.MediaShelf
import com.shivansh.mibox.data.Show
import com.shivansh.mibox.data.TitleStyle
import com.shivansh.mibox.data.UpNext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Builds the home screen from TMDB. Signed in, it is the user's own lists: the watchlist is
 * My Shows and the hero, favorites are Favorites, and Up Next is the newest or next episode of
 * those shows. Signed out (or for an empty list) it falls back to what's on the user's Indian
 * streaming services right now.
 */
class Catalog(private val tmdb: Tmdb, private val account: Account) {
    /** TMDB calls run at background priority, so parsing replies never competes with the UI. */
    private val pool = Executors.newFixedThreadPool(3) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "tmdb")
    }

    /** [offline] builds from the disk cache only: instant, for the first frame after a start. */
    @WorkerThread
    fun load(offline: Boolean): HomeContent? {
        val today = LocalDate.now()
        // This week's trending shows make a livelier hero than all-time popularity (old procedurals).
        val trending = tmdb.get("/trending/tv/week", mapOf("language" to "en-US"), maxAgeMs = 6 * Tmdb.HOUR, offline = offline, key = "trending")
            ?.optJSONArray("results").objects().take(20)
        val popular = discover("popular", mapOf("sort_by" to "popularity.desc", "with_type" to SCRIPTED), offline)
        val airing = discover(
            "airing",
            mapOf(
                "sort_by" to "popularity.desc",
                "with_type" to "0|$SCRIPTED",
                "air_date.gte" to today.minusDays(7).toString(),
                "air_date.lte" to today.plusDays(7).toString(),
            ),
            offline,
        )
        val acclaimed = discover(
            "acclaimed",
            mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "300", "with_type" to SCRIPTED),
            offline,
        )
        val trendingFilms = tmdb.get("/trending/movie/week", mapOf("language" to "en-US"), maxAgeMs = 6 * Tmdb.HOUR, offline = offline, key = "trending-movies")
            ?.optJSONArray("results").objects().take(20)
        val signedIn = account.signedIn
        val watchlist = if (signedIn) accountList("watchlist", "tv", "created_at.asc", offline) else emptyList()
        val favorites = if (signedIn) accountList("favorite", "tv", "created_at.desc", offline) else emptyList()
        val filmWatchlist = if (signedIn) accountList("watchlist", "movies", "created_at.desc", offline) else emptyList()
        val filmFavorites = if (signedIn) accountList("favorite", "movies", "created_at.desc", offline) else emptyList()
        // Ranked by how many people rated them: the well-known web series, not daily soaps.
        val indian = discover(
            "indian",
            mapOf(
                "sort_by" to "vote_count.desc",
                "with_origin_country" to "IN",
                "with_type" to SCRIPTED,
                "first_air_date.gte" to "2017-01-01",
            ),
            offline,
        )

        // Details (service, episodes, rating) for every show that will be on screen.
        val wanted = (watchlist.take(MAX_HERO) + trending + popular.take(MAX_HERO) + watchlist + favorites +
            airing.take(MAX_ROW) + indian.take(MAX_ROW) + acclaimed.take(MAX_ROW)).distinctBy { it.optInt("id") }
        val details = details(wanted.map { it.optInt("id") }, MediaType.TV, offline)
        fun show(item: JSONObject) = show(item, details[item.optInt("id")], MediaType.TV)
        val films = (filmWatchlist + filmFavorites + trendingFilms).distinctBy { it.optInt("id") }
        val filmDetails = details(films.map { it.optInt("id") }, MediaType.MOVIE, offline)
        fun film(item: JSONObject) = show(item, filmDetails[item.optInt("id")], MediaType.MOVIE)

        // The user's shows lead; trending shows on their apps fill the rest, so it never runs dry.
        val onApps = trending.filter { service(details[it.optInt("id")]).first != null }
        val listed = watchlist.map { it.optInt("id") }.toSet()
        val fill = onApps.ifEmpty { popular }
        val fillLabel = if (onApps.isNotEmpty()) "Trending this week" else "Popular on your apps"
        val hero = (watchlist + fill).distinctBy { it.optInt("id") }.mapNotNull { item ->
            val label = if (item.optInt("id") in listed) "In My Shows" else fillLabel
            show(item)?.let { slide(it, item, details[item.optInt("id")], label, today) }
        }.take(MAX_HERO)
        if (hero.isEmpty()) return null

        val myShowsList = watchlist.mapNotNull(::show)
        val favoriteList = favorites.mapNotNull(::show)
        val yourUpNext = (myShowsList + favoriteList).distinctBy { it.tmdbId }
            .mapNotNull { s -> details[s.tmdbId]?.let { upNext(s, it, today) } }
            .sortedBy { it.first }.map { it.second }.take(MAX_ROW)
        val airingUpNext = airing.mapNotNull { item -> show(item)?.let { s -> details[s.tmdbId]?.let { upNext(s, it, today) } } }
            .sortedBy { it.first }.map { it.second }.take(MAX_ROW)

        // Your shows' episodes lead; this week's new episodes on your apps follow, so the row never
        // shrinks to one card just because one show is saved.
        val upNextRow = if (yourUpNext.isNotEmpty()) {
            Row("Up Next", "Your shows first, then what's new this week", (yourUpNext + airingUpNext).distinctBy { it.show.tmdbId }.take(MAX_ROW))
        } else {
            Row("New This Week", "Fresh episodes on your apps", airingUpNext)
        }
        // The user's rows (empty ones are hidden) come in addition to the suggestions, never instead.
        val myShowsRow = Row("My Shows", "Your TMDB watchlist, in your order", myShowsList)
        val favoritesRow = Row("Favorites", "Your TMDB favorites", favoriteList)
        val saved = (myShowsList + favoriteList).map { it.tmdbId }.toSet()
        val popularRow = Row(
            "Popular Indian Shows",
            "Made in India, on your apps",
            indian.mapNotNull(::show).filter { it.poster != null && it.tmdbId !in saved }.take(MAX_ROW),
        )
        val acclaimedRow = Row(
            "Critically Acclaimed",
            "The best-rated shows on your apps",
            acclaimed.mapNotNull(::show).filter { it.tmdbId !in saved }.take(MAX_ROW),
        )

        val shelfSource = (myShowsList + hero.map { it.show } + airingUpNext.map { it.show }).distinctBy { it.tmdbId }
        val lists = HashSet<String>()
        for ((list, type, items) in listOf(
            Triple("watchlist", MediaType.TV, watchlist),
            Triple("favorite", MediaType.TV, favorites),
            Triple("watchlist", MediaType.MOVIE, filmWatchlist),
            Triple("favorite", MediaType.MOVIE, filmFavorites),
        )) {
            items.forEach { lists += "$list:${type.api}:${it.optInt("id")}" }
        }
        return HomeContent(
            hero, upNextRow, myShowsRow, favoritesRow, shelves(shelfSource, details, today), signedIn,
            popular = popularRow,
            acclaimed = acclaimedRow,
            trendingShows = trending.mapNotNull(::show).filter { it.poster != null },
            trendingMovies = trendingFilms.mapNotNull(::film).filter { it.poster != null },
            showWatchlist = myShowsList,
            movieWatchlist = filmWatchlist.mapNotNull(::film),
            showFavorites = favoriteList,
            movieFavorites = filmFavorites.mapNotNull(::film),
            lists = lists,
        )
    }

    private fun discover(name: String, params: Map<String, String>, offline: Boolean, type: MediaType = MediaType.TV): List<JSONObject> {
        val all = mapOf(
            "watch_region" to "IN",
            "with_watch_providers" to Provider.tmdbIds,
            "with_watch_monetization_types" to "flatrate",
            "include_adult" to "false",
            "language" to "en-US",
        ) + params
        // A stable cache name, so date windows that move daily still find yesterday's copy offline.
        val key = if (type == MediaType.TV) "discover-$name" else "discover-movie-$name"
        return tmdb.get("/discover/${type.api}", all, maxAgeMs = 6 * Tmdb.HOUR, offline = offline, key = key)
            ?.optJSONArray("results").objects()
    }

    /**
     * The Shows or Movies tab: this week's trending titles, then one shelf per genre. Each genre
     * leads with what is trending in it, then fills up with what's popular in it on the user's apps.
     * Full records are read from the disk cache only; the tab fetches the rest as titles get focus.
     */
    @WorkerThread
    fun browse(type: MediaType, offline: Boolean): List<MediaShelf> {
        val trendingJobs = (1..TRENDING_PAGES).map { page ->
            // Page one shares its cached copy with the home screen.
            val key = when {
                page > 1 -> "trending-${type.api}-$page"
                type == MediaType.TV -> "trending"
                else -> "trending-movies"
            }
            pool.submit<List<JSONObject>> {
                tmdb.get(
                    "/trending/${type.api}/week",
                    mapOf("language" to "en-US", "page" to "$page"),
                    maxAgeMs = 6 * Tmdb.HOUR,
                    offline = offline,
                    key = key,
                )?.optJSONArray("results").objects()
            }
        }
        val genres = if (type == MediaType.TV) TV_SHELVES else MOVIE_SHELVES
        // Popular, but only titles enough people have rated: it keeps out the daily soaps.
        val base = if (type == MediaType.TV) {
            mapOf("with_type" to "0|$SCRIPTED", "vote_count.gte" to "25", "sort_by" to "popularity.desc")
        } else {
            mapOf("vote_count.gte" to "60", "sort_by" to "popularity.desc")
        }
        val genreJobs = genres.map { (id, _) ->
            val params = if (id == INDIAN) mapOf("with_origin_country" to "IN") else mapOf("with_genres" to "$id")
            pool.submit<List<JSONObject>> { discover("shelf-$id", base + params, offline, type) }
        }
        val trending = trendingJobs.flatMap { runCatching { it.get(30, TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
            .distinctBy { it.optInt("id") }
        val popular = genreJobs.map { runCatching { it.get(30, TimeUnit.SECONDS) }.getOrDefault(emptyList()) }

        val picked = HashMap<Int, JSONObject>()
        val rows = ArrayList<Pair<Pair<String, String>, List<JSONObject>>>()
        rows += ("trending" to "Trending") to trending.take(SHELF_SIZE)
        genres.forEachIndexed { i, (id, label) ->
            val inTrend = if (id == INDIAN) {
                trending.filter { item -> item.optJSONArray("origin_country")?.let { a -> (0 until a.length()).any { a.optString(it) == "IN" } } == true }
            } else {
                trending.filter { item -> item.optJSONArray("genre_ids")?.ints()?.contains(id) == true }
            }
            val items = (inTrend + popular[i]).distinctBy { it.optInt("id") }.take(SHELF_SIZE)
            if (items.size >= MIN_SHELF) rows += ("g$id" to label) to items
        }
        rows.forEach { (_, items) -> items.forEach { picked[it.optInt("id")] = it } }
        val details = details(picked.keys.toList(), type, offline = true)
        return rows.map { (name, items) ->
            MediaShelf(name.first, name.second, items.mapNotNull { show(it, details[it.optInt("id")], type) }.filter { it.poster != null })
        }
    }

    /** A title with its full record: service, logo and length. Fetches it if it isn't cached. */
    @WorkerThread
    fun enrich(show: Show): Show {
        val d = details(listOf(show.tmdbId), show.mediaType, offline = false)[show.tmdbId] ?: return show
        val (provider, service) = service(d)
        return show.copy(
            provider = provider,
            service = service,
            logo = logo(d),
            length = length(d, show.mediaType),
            overview = show.overview.ifEmpty { d.str("overview").orEmpty() },
            detailed = true,
            trailers = trailers(d),
            score = if (show.score > 0f) show.score else score(d),
        )
    }

    /**
     * Shows and films matching [query]: titles that start with it first, then ones that contain it,
     * each by popularity (TMDB's own order puts old exact matches above the show you meant); then
     * what any matching people are known for. Null when TMDB can't be reached. Full records come
     * from the disk cache only, like [browse].
     */
    @WorkerThread
    fun search(query: String): List<Show>? {
        val json = tmdb.get(
            "/search/multi",
            mapOf("query" to query, "include_adult" to "false", "language" to "en-US"),
            maxAgeMs = Tmdb.HOUR,
        ) ?: return null
        val results = json.optJSONArray("results").objects()
        val q = query.trim().lowercase()
        fun fit(item: JSONObject): Int {
            val name = (item.str("name") ?: item.str("title")).orEmpty().lowercase()
            return when {
                name.startsWith(q) -> 0
                name.contains(q) -> 1
                else -> 2
            }
        }
        val titles = results.filter { it.str("media_type") == "tv" || it.str("media_type") == "movie" }
            .sortedWith(compareBy<JSONObject> { fit(it) }.thenByDescending { it.optDouble("popularity", 0.0) })
        val knownFor = results.filter { it.str("media_type") == "person" }.flatMap { it.optJSONArray("known_for").objects() }
        val items = (titles + knownFor).distinctBy { it.str("media_type") + it.optInt("id") }
        fun typeOf(item: JSONObject) = if (item.str("media_type") == "movie") MediaType.MOVIE else MediaType.TV
        val shows = details(items.filter { typeOf(it) == MediaType.TV }.map { it.optInt("id") }, MediaType.TV, offline = true)
        val films = details(items.filter { typeOf(it) == MediaType.MOVIE }.map { it.optInt("id") }, MediaType.MOVIE, offline = true)
        return items.mapNotNull { item ->
            val type = typeOf(item)
            show(item, (if (type == MediaType.TV) shows else films)[item.optInt("id")], type)
        }.filter { it.poster != null }
    }

    /** [kind] is TMDB's plural in these paths: "tv" or "movies". */
    private fun accountList(list: String, kind: String, sort: String, offline: Boolean): List<JSONObject> {
        val session = account.sessionId ?: return emptyList()
        val items = ArrayList<JSONObject>()
        for (page in 1..2) {
            val json = tmdb.get(
                "/account/${account.accountId}/$list/$kind",
                mapOf("session_id" to session, "sort_by" to sort, "language" to "en-US", "page" to "$page"),
                maxAgeMs = 60 * 1000L,
                offline = offline,
                key = "account-$list-$kind-$page",
            ) ?: break
            items += json.optJSONArray("results").objects()
            if (page >= json.optInt("total_pages", 1)) break
        }
        return items
    }

    private fun details(ids: List<Int>, type: MediaType, offline: Boolean): Map<Int, JSONObject> {
        val extras = if (type == MediaType.TV) "watch/providers,content_ratings,images,videos" else "watch/providers,images,videos"
        val jobs = ids.map { id ->
            id to pool.submit<JSONObject?> {
                tmdb.get(
                    "/${type.api}/$id",
                    mapOf(
                        "append_to_response" to extras,
                        "include_image_language" to "en",
                        // English trailers first, Hindi ones for Indian titles, then any without a language.
                        "include_video_language" to "en,hi,null",
                        "language" to "en-US",
                    ),
                    maxAgeMs = 12 * Tmdb.HOUR,
                    offline = offline,
                )
            }
        }
        return jobs.mapNotNull { (id, job) ->
            runCatching { job.get(20, TimeUnit.SECONDS) }.getOrNull()?.let { id to it }
        }.toMap()
    }

    private fun show(item: JSONObject, d: JSONObject?, type: MediaType): Show? {
        val id = item.optInt("id")
        val title = item.str("name") ?: item.str("title") ?: return null
        val backdrop = item.str("backdrop_path") ?: d?.str("backdrop_path")
        val poster = item.str("poster_path") ?: d?.str("poster_path")
        val art = backdrop ?: poster ?: return null
        val genres = item.optJSONArray("genre_ids")?.ints()
            ?: d?.optJSONArray("genres").objects().map { it.optInt("id") }
        val (provider, service) = service(d)
        return Show(
            id = "tmdb:$id",
            title = title,
            style = styleFor(genres.firstOrNull()),
            provider = provider,
            tint = TINTS[Math.floorMod(id, TINTS.size)],
            backdrop = image("w1280", art),
            poster = poster?.let { image("w342", it) },
            still = image("w780", art),
            service = service,
            tmdbId = id,
            mediaType = type,
            logo = logo(d),
            year = (item.str("first_air_date") ?: item.str("release_date") ?: d?.str("first_air_date") ?: d?.str("release_date"))
                .orEmpty().take(4),
            genres = genres.mapNotNull { GENRES[it] }.take(2).joinToString(" · "),
            overview = item.str("overview") ?: d?.str("overview").orEmpty(),
            length = d?.let { length(it, type) }.orEmpty(),
            detailed = d != null,
            trailers = trailers(d),
            score = score(item, d),
        )
    }

    /** The user score from the first record with enough votes to go by, or 0. */
    private fun score(vararg records: JSONObject?): Float =
        records.firstOrNull { it != null && it.optInt("vote_count") >= MIN_VOTES }
            ?.optDouble("vote_average", 0.0)?.toFloat() ?: 0f

    /**
     * YouTube trailers, best first: trailers over teasers, official over fan-made, newest first. A
     * few, so the player has others to try when YouTube won't play one here (some are US-only).
     */
    private fun trailers(d: JSONObject?): List<String> =
        d?.optJSONObject("videos")?.optJSONArray("results").objects()
            .filter { it.str("site") == "YouTube" && it.str("key") != null && (it.str("type") == "Trailer" || it.str("type") == "Teaser") }
            .sortedWith(
                compareBy<JSONObject>({ if (it.str("type") == "Trailer") 0 else 1 }, { if (it.optBoolean("official")) 0 else 1 })
                    .thenByDescending { it.str("published_at").orEmpty() },
            )
            .mapNotNull { it.str("key") }.take(MAX_TRAILERS)

    /** "2h 10m" for a film, "3 seasons" for a series. */
    private fun length(d: JSONObject, type: MediaType): String {
        if (type == MediaType.MOVIE) {
            val minutes = d.optInt("runtime")
            return when {
                minutes <= 0 -> ""
                minutes < 60 -> "${minutes}m"
                minutes % 60 == 0 -> "${minutes / 60}h"
                else -> "${minutes / 60}h ${minutes % 60}m"
            }
        }
        val seasons = d.optInt("number_of_seasons")
        return if (seasons > 0) "$seasons season${if (seasons > 1) "s" else ""}" else ""
    }

    /** The best-rated English title logo, as a PNG (TMDB also has SVGs, which Android can't decode). */
    private fun logo(d: JSONObject?): String? =
        d?.optJSONObject("images")?.optJSONArray("logos").objects()
            .filter { it.str("iso_639_1") == "en" && it.str("file_path")?.endsWith(".png") == true }
            .maxByOrNull { it.optDouble("vote_average", 0.0) }
            ?.str("file_path")?.let { image("w500", it) }

    /** The service a show streams on in India: one we have an app for first, else whatever TMDB names. */
    private fun service(d: JSONObject?): Pair<Provider?, String> {
        val flatrate = d?.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("IN")
            ?.optJSONArray("flatrate").objects()
        val known = flatrate.firstNotNullOfOrNull { Provider.forTmdb(it.optInt("provider_id")) }
        if (known != null) return known to known.label
        val named = flatrate.firstNotNullOfOrNull { it.str("provider_name") }
            ?: d?.optJSONArray("networks").objects().firstNotNullOfOrNull { it.str("name") }
        return null to named.orEmpty()
    }

    private fun slide(show: Show, item: JSONObject, d: JSONObject?, fallback: String, today: LocalDate): HeroSlide {
        val last = d?.optJSONObject("last_episode_to_air")
        val next = d?.optJSONObject("next_episode_to_air")
        val lastDays = last?.date()?.let { ChronoUnit.DAYS.between(it, today) }
        val nextDays = next?.date()?.let { ChronoUnit.DAYS.between(today, it) }
        val status = when {
            lastDays != null && lastDays in 0..7 && last.optInt("episode_number") == 1 && last.optInt("season_number") > 1 -> "New season"
            lastDays != null && lastDays in 0..7 -> "New episode"
            nextDays != null && nextDays in 0..7 -> "New episode " + next.date()!!.format(WEEKDAY)
            else -> fallback
        }
        val episode = last?.let { "S${it.optInt("season_number")} · E${it.optInt("episode_number")}" }
        val seasons = d?.optInt("number_of_seasons") ?: 0
        val genres = (d?.optJSONArray("genres").objects().mapNotNull { it.str("name") }
            .ifEmpty { item.optJSONArray("genre_ids")?.ints()?.mapNotNull { GENRES[it] } ?: emptyList() })
            .take(2).joinToString(" · ")
        return HeroSlide(
            show = show,
            status = status,
            next = episode ?: if (seasons > 0) "$seasons season${if (seasons > 1) "s" else ""}" else "",
            genre = genres,
            year = (item.str("first_air_date") ?: d?.str("first_air_date")).orEmpty().take(4),
            rating = rating(d),
            play = last?.let { "Play S${it.optInt("season_number")} E${it.optInt("episode_number")}" } ?: "Play",
            blurb = item.str("overview") ?: d?.str("overview").orEmpty(),
        )
    }

    /** The newest aired episode (up to 45 days old) or else the next one due (within 3 weeks). Sorted newest first. */
    private fun upNext(show: Show, d: JSONObject, today: LocalDate): Pair<Long, UpNext>? {
        val last = d.optJSONObject("last_episode_to_air")
        val next = d.optJSONObject("next_episode_to_air")
        val lastDays = last?.date()?.let { ChronoUnit.DAYS.between(it, today) }
        if (last != null && lastDays != null && lastDays in 0..45) {
            val season = last.optInt("season_number")
            val number = last.optInt("episode_number")
            val badge = when {
                lastDays > 7 -> ""
                number == 1 && season > 1 -> "NEW SEASON"
                else -> "NEW"
            }
            return lastDays to UpNext(show, "S$season · E$number", badge, number, episodesIn(d, season, number))
        }
        val nextDate = next?.date() ?: return null
        val nextDays = ChronoUnit.DAYS.between(today, nextDate)
        if (nextDays !in 0..21) return null
        val season = next.optInt("season_number")
        val number = next.optInt("episode_number")
        return (1000 + nextDays) to UpNext(show, "S$season · E$number", "", number - 1, episodesIn(d, season, number), "Out " + nextDate.format(DAY))
    }

    private fun episodesIn(d: JSONObject, season: Int, atLeast: Int): Int {
        val count = d.optJSONArray("seasons").objects().firstOrNull { it.optInt("season_number") == season }?.optInt("episode_count") ?: 0
        return maxOf(count, atLeast)
    }

    private fun rating(d: JSONObject?): String {
        val ratings = d?.optJSONObject("content_ratings")?.optJSONArray("results").objects()
        return (ratings.firstOrNull { it.str("iso_3166_1") == "IN" } ?: ratings.firstOrNull { it.str("iso_3166_1") == "US" })
            ?.str("rating").orEmpty()
    }

    private fun shelves(shows: List<Show>, details: Map<Int, JSONObject>, today: LocalDate): Map<Provider, ProviderShelf> =
        shows.filter { it.provider != null }.groupBy { it.provider!! }.mapValues { (_, list) ->
            val lead = list.first()
            val last = details[lead.tmdbId]?.optJSONObject("last_episode_to_air")
            val fresh = last?.date()?.let { ChronoUnit.DAYS.between(it, today) in 0..14 } == true
            val headline = when {
                last == null -> "On your list"
                fresh -> "New episode · S${last.optInt("season_number")} E${last.optInt("episode_number")}"
                else -> "Season ${last.optInt("season_number")}"
            }
            val others = list.drop(1).take(2).joinToString(", ") { it.title }
            ProviderShelf(lead, if (others.isEmpty()) headline else "$headline     Also here: $others")
        }

    private fun styleFor(genre: Int?): TitleStyle = when (genre) {
        10759, 80, 10765, 10768, 37 -> TitleStyle.CONDENSED
        18, 9648, 10766 -> TitleStyle.SERIF
        null -> TitleStyle.SERIF
        else -> TitleStyle.PLAIN
    }

    private fun image(size: String, path: String) = Tmdb.IMAGES + size + path

    private companion object {
        const val MAX_HERO = 8
        const val MAX_TRAILERS = 5

        /** Fewer votes than this and the score says little (a new show at 10.0 from three people). */
        const val MIN_VOTES = 25
        const val MAX_ROW = 12

        /** Trending is read three pages deep (60 titles), so most genres find some of their own. */
        const val TRENDING_PAGES = 3
        const val SHELF_SIZE = 20
        const val MIN_SHELF = 4

        /** Not a TMDB genre: the shelf of titles made in India. */
        const val INDIAN = -1

        /** The genre shelves, in order, by TMDB genre id. */
        val TV_SHELVES = listOf(
            INDIAN to "Indian", 18 to "Drama", 80 to "Crime", 35 to "Comedy", 10759 to "Action",
            9648 to "Mystery", 10765 to "Sci-Fi & Fantasy", 16 to "Animation", 99 to "Documentary",
        )
        val MOVIE_SHELVES = listOf(
            INDIAN to "Indian", 28 to "Action", 53 to "Thriller", 35 to "Comedy", 18 to "Drama",
            27 to "Horror", 10749 to "Romance", 878 to "Sci-Fi", 16 to "Animation", 10751 to "Family",
        )

        /** TMDB show types: 2 miniseries, 4 scripted (0 documentary is added where it fits). */
        const val SCRIPTED = "2|4"

        val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

        /** Accents for glows, picked per show until we read one from its art. */
        val TINTS = intArrayOf(
            0xFFFFB067.toInt(), 0xFF7FD8FF.toInt(), 0xFFFF6FB4.toInt(), 0xFF3DF5A8.toInt(),
            0xFFF3B89A.toInt(), 0xFFE8E39A.toInt(), 0xFF86A8FF.toInt(), 0xFFFF8A4B.toInt(),
        )

        val GENRES = mapOf(
            10759 to "Action & Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
            99 to "Documentary", 18 to "Drama", 10751 to "Family", 10762 to "Kids", 9648 to "Mystery",
            10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi & Fantasy", 10766 to "Soap",
            10767 to "Talk", 10768 to "War & Politics", 37 to "Western",
            // Film genres (the shared ids above mean the same for films).
            28 to "Action", 12 to "Adventure", 14 to "Fantasy", 36 to "History", 27 to "Horror",
            10402 to "Music", 10749 to "Romance", 878 to "Sci-Fi", 10770 to "TV Movie", 53 to "Thriller", 10752 to "War",
        )
    }
}

/** A string field, or null when missing, JSON null or blank (org.json would hand back "null"). */
internal fun JSONObject.str(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray.ints(): List<Int> = (0 until length()).map { optInt(it) }

private fun JSONObject.date(): LocalDate? = str("air_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
