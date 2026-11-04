package com.sosim.videocatcher.ui

import android.app.Activity
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi

/**
 * Android 13+ back handling. The callback is registered only while the app
 * wants to handle "back" (page history, fullscreen video), so the system's
 * predictive back animation still works when the app would just close.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class BackCallbackHolder(private val activity: Activity, onBack: () -> Unit) {

    private val callback = OnBackInvokedCallback { onBack() }
    private var registered = false

    fun setEnabled(enabled: Boolean) {
        if (enabled == registered) return
        val dispatcher = activity.onBackInvokedDispatcher
        if (enabled) {
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        } else {
            dispatcher.unregisterOnBackInvokedCallback(callback)
        }
        registered = enabled
    }
}
