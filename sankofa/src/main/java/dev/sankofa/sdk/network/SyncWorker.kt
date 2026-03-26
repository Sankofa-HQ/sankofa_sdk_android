package dev.sankofa.sdk.network

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sankofa.sdk.data.EventQueueManager

/**
 * A [CoroutineWorker] that flushes the event queue.
 * Triggered:
 *  - When the app goes to background (one-time job).
 *  - On a periodic coroutine loop every 30 seconds while the app is alive.
 *
 * WorkManager only runs this when there's network connectivity.
 */
internal class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // EventQueueManager is accessed via the Sankofa singleton's internal reference.
            // We retrieve it via the companion object injected before the worker was scheduled.
            val queueManager = queueManagerRef
                ?: return Result.failure()

            queueManager.flush()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        /**
         * Set by [Sankofa.init] so the worker can reach the queue without a DI framework.
         * This is safe because WorkManager only runs the worker while the process is alive.
         */
        internal var queueManagerRef: EventQueueManager? = null

        /** Enqueues a one-time background sync when the app is going to the background. */
        fun scheduleOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
