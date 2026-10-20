package com.wasimaster.wmkeyboard.app.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.MainActivity
import com.wasimaster.wmkeyboard.core.notify.NotificationIds
import com.wasimaster.wmkeyboard.core.notify.NotificationKind
import com.wasimaster.wmkeyboard.core.notify.WmNotifications

/**
 * Says that the app has been updated, once, in the shade.
 *
 * The card on the settings home screen ([UpdatedCard]) says the same thing,
 * and only to someone who opens the settings app — which almost nobody does
 * after an update they never saw happen. That is the case this exists for: a
 * quiet self-install (see [AppUpdater]) replaces the app with no screen of its
 * own, and Play's own updates are quieter still. A keyboard whose behaviour
 * changed overnight, with nothing anywhere saying why, is how a fixed bug gets
 * reported as a new one.
 *
 * Fires on every channel, because every channel has the same problem.
 * `MY_PACKAGE_REPLACED` is delivered to this app only, and only for its own
 * replacement, so there is nothing to filter.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        WmNotifications.post(app, NotificationKind.UPDATES, NotificationIds.UPDATED) {
            setContentTitle(
                app.getString(R.string.update_notify_updated_title, BuildConfig.VERSION_NAME),
            )
            setContentText(app.getString(R.string.update_notify_updated_body))
            setAutoCancel(true)
            // Straight to the notes the body promises. The launcher intent
            // this used to send opened the home screen and nothing else, and
            // the "Updated to" card there only appears after an update this
            // app installed itself, so on Play and F-Droid the tap showed
            // nothing about what changed.
            val launch = Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SHOW_RELEASE_NOTES, true)
            setContentIntent(
                PendingIntent.getActivity(
                    app,
                    0,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }
}
