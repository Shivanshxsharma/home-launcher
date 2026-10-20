package com.shivansh.mibox.data.tmdb

import androidx.annotation.WorkerThread
import org.json.JSONObject

/**
 * TMDB sign-in without typing on the TV: get a request token, show its approval page as a QR
 * code, and keep asking for a session until the user approves it on their phone.
 */
class Auth(private val tmdb: Tmdb, private val account: Account) {
    @WorkerThread
    fun newToken(): String = tmdb.send("GET", "/authentication/token/new").getString("request_token")

    fun approvalUrl(token: String) = "https://www.themoviedb.org/authenticate/$token"

    /** The session id once the token is approved; null while it is still waiting. */
    @WorkerThread
    fun trySession(token: String): String? = try {
        tmdb.send("POST", "/authentication/session/new", body = JSONObject().put("request_token", token))
            .getString("session_id")
    } catch (e: Tmdb.Failure) {
        // 401 / 17: not approved yet. Anything else (expired token, network) is a real failure.
        if (e.http == 401 && e.code == 17) null else throw e
    }

    /** Looks up the account behind [session] and keeps both. Returns the username. */
    @WorkerThread
    fun finish(session: String): String {
        val me = tmdb.send("GET", "/account", mapOf("session_id" to session))
        val name = me.str("username") ?: me.str("name").orEmpty()
        account.save(session, me.getLong("id"), name)
        tmdb.forget("account-")
        return name
    }
}
