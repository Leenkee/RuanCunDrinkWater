package com.young.waterremote.updates

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class AutoUpdateScheduler(context: Context) {
    private val context = context.applicationContext
    private val store = AutoUpdateStore(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val jobs = context.getSystemService(JobScheduler::class.java)

    fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled)
        reconcile()
    }

    fun reconcile() {
        if (!store.isEnabled()) {
            alarms.cancel(alarmIntent())
            jobs.cancel(JOB_ID)
            return
        }
        val now = ZonedDateTime.now()
        val next = UpdateSchedule.nextNoon(now)
        // Recompute local noon after every alarm, app start, reboot or timezone change.
        alarms.setWindow(
            AlarmManager.RTC_WAKEUP,
            next.toInstant().toEpochMilli(),
            TimeUnit.MINUTES.toMillis(10),
            alarmIntent()
        )
        if (UpdateSchedule.isDue(now, store.lastAttemptDate()) && jobs.getPendingJob(JOB_ID) == null) {
            val extras = PersistableBundle().apply { putLong("generation", store.generation()) }
            jobs.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, AutoUpdateJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setExtras(extras)
                    .build()
            )
        }
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        JOB_ID,
        Intent(context, AutoUpdateReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val JOB_ID = 121400
        const val ACTION_CHECK = "com.young.waterremote.CHECK_UPDATES_AT_NOON"
    }
}
