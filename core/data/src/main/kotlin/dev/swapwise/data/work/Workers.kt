package dev.swapwise.data.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.swapwise.data.EventUploader
import dev.swapwise.data.FactorRepository
import dev.swapwise.data.ReceiptRepository
import dev.swapwise.data.SwapRepository
import retrofit2.HttpException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a failed call is worth retrying: network problems, 429 (quota, Retry-After) and 5xx, including the
 * backend's 503 when the LLM is slow. Other 4xx mean the request itself is wrong; retrying cannot fix it.
 */
internal fun Throwable.isRetryable(): Boolean = when (this) {
    is IOException -> true
    is HttpException -> code() == 429 || code() >= 500
    else -> false
}

@HiltWorker
class ParseReceiptWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val receipts: ReceiptRepository,
    private val swaps: SwapRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val receiptId = inputData.getString(KEY_RECEIPT_ID) ?: return Result.failure()
        return try {
            receipts.parse(receiptId)
            // A new receipt is new evidence for cards accepted earlier. Local-only work; a failure here must not
            // turn a successful parse into a retry.
            try {
                swaps.verifyAccepted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Follow-through check failed", e)
            }
            Result.success()
        } catch (e: Exception) {
            if (e.isRetryable() && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Log.w(TAG, "Giving up parsing after ${runAttemptCount + 1} attempts: ${e.javaClass.simpleName}")
                receipts.markParseFailed(receiptId)
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_RECEIPT_ID = "receiptId"
        private const val MAX_ATTEMPTS = 8
        private const val TAG = "ParseReceiptWorker"
    }
}

@HiltWorker
class UploadEventsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploader: EventUploader,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        uploader.uploadAll()
        Result.success()
    } catch (e: Exception) {
        if (e.isRetryable()) Result.retry() else Result.failure()
    }
}

@HiltWorker
class FactorSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val factors: FactorRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        factors.sync()
        Result.success()
    } catch (e: Exception) {
        if (e.isRetryable()) Result.retry() else Result.failure()
    }
}

/** All background work goes through here, so names, constraints and backoff are defined once. */
@Singleton
class WorkScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** One parse job per receipt; scheduling it twice keeps the existing one. */
    fun enqueueParse(receiptId: String) {
        val request = OneTimeWorkRequestBuilder<ParseReceiptWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .setInputData(workDataOf(ParseReceiptWorker.KEY_RECEIPT_ID to receiptId))
            .build()
        workManager.enqueueUniqueWork("parse-$receiptId", ExistingWorkPolicy.KEEP, request)
    }

    /** APPEND_OR_REPLACE: a new event while an upload runs gets its own run afterwards instead of being missed. */
    fun enqueueEventUpload() {
        val request = OneTimeWorkRequestBuilder<UploadEventsWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
            .build()
        workManager.enqueueUniqueWork("upload-events", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun scheduleFactorSync() {
        val periodic = PeriodicWorkRequestBuilder<FactorSyncWorker>(Duration.ofHours(12))
            .setConstraints(online)
            .build()
        workManager.enqueueUniquePeriodicWork("factor-sync", ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    /** Runs a sync now too, so a fresh install gets factors right away instead of in up to 12 hours. */
    fun syncFactorsNow() {
        val request = OneTimeWorkRequestBuilder<FactorSyncWorker>().setConstraints(online).build()
        workManager.enqueueUniqueWork("factor-sync-now", ExistingWorkPolicy.KEEP, request)
    }
}
