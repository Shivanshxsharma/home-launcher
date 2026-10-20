package com.shivansh.mibox.data.tmdb

import android.content.Context

/**
 * The signed-in TMDB account: a session id from the QR sign-in, plus the account id and name.
 * Kept in app-private preferences on the box.
 */
class Account(context: Context) {
    private val prefs = context.getSharedPreferences("tmdb_account", Context.MODE_PRIVATE)

    val sessionId: String? get() = prefs.getString(SESSION, null)
    val accountId: Long get() = prefs.getLong(ID, 0)
    val username: String get() = prefs.getString(NAME, null).orEmpty()
    val signedIn: Boolean get() = sessionId != null && accountId != 0L

    fun save(sessionId: String, accountId: Long, username: String) {
        prefs.edit().putString(SESSION, sessionId).putLong(ID, accountId).putString(NAME, username).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val SESSION = "session"
        const val ID = "account_id"
        const val NAME = "username"
    }
}
