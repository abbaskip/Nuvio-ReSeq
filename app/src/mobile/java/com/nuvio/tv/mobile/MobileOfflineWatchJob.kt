package com.nuvio.tv.mobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.util.hasPendingJob
import com.nuvio.tv.core.util.scheduleSafely
import com.nuvio.tv.data.repository.OfflineWatchQueue
import com.nuvio.tv.data.repository.OfflineWatchScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class MobileOfflineWatchScheduler @Inject constructor(@ApplicationContext private val context: Context) : OfflineWatchScheduler {
    override fun schedule(profile: Int) {
        val jobs = context.getSystemService(JobScheduler::class.java)
        val id = 12_400 + profile
        if (jobs.hasPendingJob(id)) return
        jobs.scheduleSafely(JobInfo.Builder(id, ComponentName(context, MobileOfflineWatchJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply { putInt("profile", profile) })
            .setPersisted(true).build())
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MobileOfflineWatchModule {
    @Binds abstract fun scheduler(impl: MobileOfflineWatchScheduler): OfflineWatchScheduler
}

@AndroidEntryPoint
class MobileOfflineWatchJob : JobService() {
    @Inject lateinit var profiles: ProfileManager
    @Inject lateinit var offlineWatches: OfflineWatchQueue
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val profile = params.extras.getInt("profile")
        running[params.jobId] = scope.launch {
            val retry = try {
                // An inactive profile is picked up by the foreground observer on its next activation.
                if (profiles.resolvedActiveProfileId.first() != profile) false else {
                    offlineWatches.deliver(profile)
                    offlineWatches.pending(profile).first().isNotEmpty()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { true }
            running.remove(params.jobId)
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
