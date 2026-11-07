package dev.swapwise.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.swapwise.data.work.WorkScheduler
import javax.inject.Inject

/**
 * WorkManager is initialized on demand with Hilt's factory, so workers can receive injected repositories.
 * (The default initializer is removed in the manifest.)
 */
@HiltAndroidApp
class SwapwiseApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var scheduler: WorkScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        scheduler.scheduleFactorSync()
        scheduler.syncFactorsNow()
    }
}
