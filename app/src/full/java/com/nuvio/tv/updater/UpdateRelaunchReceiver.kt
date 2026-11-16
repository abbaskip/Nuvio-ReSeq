package com.nuvio.tv.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "UpdateInstall"
private const val PREFS_NAME = "update_relaunch"
private const val KEY_REQUESTED_AT = "requested_at_ms"
private const val REQUEST_TTL_MS = 10 * 60_000L

/** One-shot relaunch for an install the user started. Android's background launch policy
 * remains authoritative; no permissions, overlays or security settings are changed. */
class UpdateRelaunchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!consumeReopen(context)) return
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        runCatching { context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { Log.i(TAG, "Requested app reopen after the update") }
            .onFailure { Log.w(TAG, "Could not reopen the app after the update", it) }
    }

    companion object {
        /** Asks the next version to open itself once installed. Stored synchronously, before the session commits. */
        fun requestReopen(context: Context) {
            prefs(context).edit().putLong(KEY_REQUESTED_AT, System.currentTimeMillis()).commit()
        }

        fun cancelReopen(context: Context) {
            prefs(context).edit().remove(KEY_REQUESTED_AT).apply()
        }

        // A request left by a cancelled or failed install expires, so a later update from elsewhere won't reopen the app.
        private fun consumeReopen(context: Context): Boolean {
            val prefs = prefs(context)
            val requestedAt = prefs.getLong(KEY_REQUESTED_AT, 0L)
            if (requestedAt == 0L) return false
            prefs.edit().remove(KEY_REQUESTED_AT).commit()
            return System.currentTimeMillis() - requestedAt in 0..REQUEST_TTL_MS
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
