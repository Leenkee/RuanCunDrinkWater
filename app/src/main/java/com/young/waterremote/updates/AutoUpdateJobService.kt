package com.young.waterremote.updates

import android.app.job.JobParameters
import android.app.job.JobService
import com.young.waterremote.BuildConfig
import com.young.waterremote.data.GitHubUpdateChecker
import com.young.waterremote.data.VersionComparator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

class AutoUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checkJob: Job? = null
    private var activeParams: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val store = AutoUpdateStore(this)
        val generation = params.extras.getLong("generation")
        if (!store.claimAttempt(generation, ZonedDateTime.now())) return false
        activeParams = params
        checkJob = scope.launch {
            try {
                val release = GitHubUpdateChecker().fetchLatestRelease(BuildConfig.GITHUB_REPOSITORY)
                ensureActive()
                if (VersionComparator.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
                    store.saveRelease(generation, release)
                }
            } catch (_: Exception) {
                ensureActive()
                // Background failures are silent; the next daily alarm remains scheduled.
            } finally {
                if (activeParams === params) {
                    activeParams = null
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeParams = null
        checkJob?.cancel()
        checkJob = null
        return false
    }

    override fun onDestroy() {
        activeParams = null
        scope.cancel()
        super.onDestroy()
    }
}
