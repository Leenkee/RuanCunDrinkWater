package com.young.waterremote.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AutoUpdateScheduler.ACTION_CHECK,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> AutoUpdateScheduler(context).reconcile()
        }
    }
}
