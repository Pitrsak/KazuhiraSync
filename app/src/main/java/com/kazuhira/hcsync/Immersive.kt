package com.kazuhira.hcsync

import android.app.Dialog
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides the status and navigation bars; a swipe from the screen edge brings them back briefly.
 * Every window (activity and each dialog) has to request this itself, otherwise the bars
 * reappear while that window has focus.
 */
fun Window.hideSystemBars() {
    WindowCompat.getInsetsController(this, decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** [Dialog.show] that keeps the HUD full screen while the dialog is open. */
fun Dialog.showImmersive() {
    show()
    window?.hideSystemBars()
}
