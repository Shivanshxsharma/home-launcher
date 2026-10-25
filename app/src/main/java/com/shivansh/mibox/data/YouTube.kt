package com.shivansh.mibox.data

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/**
 * Trailers open in the box's own YouTube app, which plays them smoothly with its native player.
 * If that app is missing, any other app that takes YouTube links (SmartTube, a browser) gets the
 * link instead. False when nothing on the box can play it.
 */
object YouTube {
    private const val TV_APP = "com.google.android.youtube.tv"

    fun play(activity: Activity, videoKey: String): Boolean {
        val link = Uri.parse("https://www.youtube.com/watch?v=$videoKey")
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, link).setPackage(TV_APP),
            Intent(Intent.ACTION_VIEW, link),
        )
        for (intent in tries) {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // Next one.
            } catch (_: SecurityException) {
                // Next one.
            }
        }
        return false
    }
}
