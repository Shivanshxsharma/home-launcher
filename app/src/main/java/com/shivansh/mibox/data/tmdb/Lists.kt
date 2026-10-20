package com.shivansh.mibox.data.tmdb

import androidx.annotation.WorkerThread
import com.shivansh.mibox.data.Show
import org.json.JSONObject

/** Changes to the signed-in user's TMDB lists: the watchlist (My Shows) and favorites. */
class Lists(private val tmdb: Tmdb, private val account: Account) {
    /** Puts [show] on [list] ("watchlist" or "favorite") or takes it off. Throws when TMDB says no. */
    @WorkerThread
    fun set(list: String, show: Show, on: Boolean) {
        val session = account.sessionId ?: throw IllegalStateException("Not signed in")
        val body = JSONObject()
            .put("media_type", show.mediaType.api)
            .put("media_id", show.tmdbId)
            .put(list, on)
        tmdb.send("POST", "/account/${account.accountId}/$list", mapOf("session_id" to session), body)
        // The cached lists are out of date now; the next load fetches them again.
        tmdb.forget("account-")
    }

    companion object {
        const val WATCHLIST = "watchlist"
        const val FAVORITE = "favorite"
    }
}
