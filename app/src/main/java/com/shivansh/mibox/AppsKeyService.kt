package com.shivansh.mibox

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Opens the launcher on its Apps tab. */
const val ACTION_SHOW_APPS = "com.shivansh.mibox.SHOW_APPS"

/**
 * Makes the remote's Apps button open the launcher's Apps tab. Android TV hands that button only to
 * the built-in launcher (switched off once this one is Home), so no app ever receives it; an
 * accessibility service is the one place that can. It filters that single key and nothing else,
 * and reads nothing on screen. The user turns it on once in the TV's accessibility settings.
 */
class AppsKeyService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ALL_APPS) return false
        if (event.action == KeyEvent.ACTION_UP) {
            startActivity(Intent(this, HomeActivity::class.java).setAction(ACTION_SHOW_APPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
