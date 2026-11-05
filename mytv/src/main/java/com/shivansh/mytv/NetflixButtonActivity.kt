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
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            intent.component?.let { Recents.note(getSharedPreferences("mytv", MODE_PRIVATE), it.flattenToShortString()) }
        }
        finish()
    }

    private companion object {
        const val YOUTUBE = "com.google.android.youtube.tv"
    }
}
