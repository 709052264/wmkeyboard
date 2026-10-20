package com.wasimaster.wmkeyboard.core.util

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * How many of this app's activities are started (visible, or about to be) in
 * this process.
 *
 * The keyboard and the settings app share one process, so the keyboard has to
 * ask before doing anything that ends it: recycling the process to get back
 * memory a native library will not return (the GPU model runtime) is fine
 * while the user is typing elsewhere, and not while they are looking at the
 * AI chat screen. Installed from `Application.onCreate`, so it sees every
 * activity the process ever starts.
 */
object StartedActivities : Application.ActivityLifecycleCallbacks {

    @Volatile
    var count = 0
        private set

    private var installed = false

    @Synchronized
    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        count++
    }

    override fun onActivityStopped(activity: Activity) {
        count = (count - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
