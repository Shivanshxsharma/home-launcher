package com.shivansh.mytv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Opens MY TV's app switcher. */
const val ACTION_SHOW_RECENT = "com.shivansh.mytv.SHOW_RECENT"

/**
 * Makes the remote's Apps button open the app switcher. Android TV sends that button only to the
 * built-in launcher, so no app ever receives it; an accessibility service is the one place that
 * can. It filters that single key and nothing else, and reads nothing on screen. The user turns it
 * on once in the TV's accessibility settings.
 */
class AppsKeyService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ALL_APPS) return false
        if (event.action == KeyEvent.ACTION_UP) {
            startActivity(Intent(this, MyTvActivity::class.java).setAction(ACTION_SHOW_RECENT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
