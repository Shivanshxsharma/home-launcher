package com.shivansh.mytv

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The remote's Netflix button. The box turns that key into an nflx:// link before any app or
 * service can see it, so this answers the link instead, opens YouTube and is gone, drawing nothing.
 * It works while Netflix itself is disabled; with Netflix enabled, Android would ask which to use.
 */
class NetflixButtonActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = packageManager.getLeanbackLaunchIntentForPackage(YOUTUBE) ?: packageManager.getLaunchIntentForPackage(YOUTUBE)
        if (intent != null) {
            val prefs = getSharedPreferences("mytv", MODE_PRIVATE)
            startActivity(intent.addFlags(Recents.startFlags(prefs, YOUTUBE)))
            intent.component?.let { Recents.note(prefs, it.flattenToShortString()) }
        }
        finish()
    }

    private companion object {
        const val YOUTUBE = "com.google.android.youtube.tv"
    }
}
