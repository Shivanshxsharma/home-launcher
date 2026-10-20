package com.shivansh.mibox.data.tmdb

import android.content.Context
import com.shivansh.mibox.BuildConfig

/**
 * The TMDB key the app talks to TMDB with: the one connected from a phone on the Connect page,
 * else one built into the app (developer builds only; public builds ship without a key).
 * Either a v3 API key or a v4 read access token works; TMDB's v3 API takes the token as a bearer.
 */
class ApiKey(context: Context) {
    private val prefs = context.getSharedPreferences("tmdb_key", Context.MODE_PRIVATE)

    /** A key TMDB has turned down; it isn't used again until a new one is connected. */
    @Volatile
    private var refused: String? = null

    val value: String?
        get() = (prefs.getString(KEY, null) ?: BuildConfig.TMDB_API_KEY).takeIf { it.isNotBlank() && it != refused }

    val present: Boolean get() = value != null

    /** True when the key came from the user's phone rather than the build. */
    val connected: Boolean get() = prefs.getString(KEY, null) != null

    fun save(key: String) {
        prefs.edit().putString(KEY, key.trim()).apply()
        refused = null
    }

    /** TMDB says [key] is invalid: stop using it, and forget it if it was the connected one. */
    fun refuse(key: String) {
        refused = key
        if (prefs.getString(KEY, null) == key) prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "key"

        /** v4 read access tokens are JWTs; v3 keys are 32 hex characters. */
        fun isToken(key: String) = key.startsWith("eyJ")
    }
}
