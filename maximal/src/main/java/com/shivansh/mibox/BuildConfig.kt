package com.shivansh.mibox

/**
 * The shared data code (from the Home launcher) reads its baked-in TMDB key from
 * `com.shivansh.mibox.BuildConfig`; in Maximal that is this app's own generated value.
 */
object BuildConfig {
    const val TMDB_API_KEY: String = com.shivansh.mibox.maximal.BuildConfig.TMDB_API_KEY
}
