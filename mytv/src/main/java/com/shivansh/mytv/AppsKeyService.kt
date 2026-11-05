package com.shivansh.mytv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.io.File

/** Opens MY TV's app switcher. */
const val ACTION_SHOW_RECENT = "com.shivansh.mytv.SHOW_RECENT"

/** The package the remote's Prime button opens, once the user has chosen one. */
const val PRIME_BUTTON = "prime_button"

const val PRIME_VIDEO = "com.amazon.amazonvideo.livingroom"

/** Prime Video was opened on purpose, from MY TV, so it mustn't be swapped for the chosen app. */
object PrimeGate {
    private const val GRACE_MS = 8_000L
    private var until = 0L

    fun allow() {
        until = SystemClock.uptimeMillis() + GRACE_MS
    }

    fun allowed() = SystemClock.uptimeMillis() < until
}

/**
 * The remote's Apps and Prime buttons. The user turns this on once in the TV's accessibility
 * settings. It reads nothing on screen: only one key, and the names of apps as they open.
 *
 * Apps opens the switcher. Android TV sends that key only to the built-in launcher, and this is the
 * one place an app can catch it.
 *
 * Prime can't be caught at all: Xiaomi built it into the box's window manager, which starts Prime
 * Video before any key filter sees it. So when Prime Video appears and MY TV didn't open it, this
 * opens the chosen app over it and force stops it. Prime Video shows for a moment first. With
 * nothing chosen, the button works as it always did.
 */
class AppsKeyService : AccessibilityService() {
    private val prefs by lazy { getSharedPreferences("mytv", MODE_PRIVATE) }
    private val forceStop by lazy { ForceStop(File(filesDir, "adb")) }
    private var inPrime = false

    /** Set here as well as in the XML: Android keeps a service's old settings across an update. */
    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 0
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ALL_APPS) return false
        if (event.action == KeyEvent.ACTION_UP) {
            startActivity(Intent(this, MyTvActivity::class.java).setAction(ACTION_SHOW_RECENT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val name = event.packageName?.toString() ?: return
        if (name in OVERLAYS) return
        if (name != PRIME_VIDEO) {
            inPrime = false
            return
        }
        // Prime Video's own screens changing is not it opening.
        if (inPrime) return
        inPrime = true
        if (PrimeGate.allowed()) return
        val choice = prefs.getString(PRIME_BUTTON, null)?.takeIf { it != PRIME_VIDEO } ?: return
        val intent = packageManager.getLeanbackLaunchIntentForPackage(choice) ?: packageManager.getLaunchIntentForPackage(choice) ?: return
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        intent.component?.let { Recents.note(prefs, it.flattenToShortString()) }
        forceStop.stop(listOf(PRIME_VIDEO))
        inPrime = false
    }

    override fun onInterrupt() = Unit

    private companion object {
        /** Windows that float over an app without leaving it. */
        val OVERLAYS = setOf("android", "com.android.systemui", "com.google.android.inputmethod.latin")
    }
}
