package dev.sankofa.sdk.network

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sankofa.sdk.data.EventQueueManager
import dev.sankofa.sdk.util.SankofaLogger

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
            // Prefer the live queue manager set by Sankofa.init in this process.
            // If WorkManager re-ran us in a fresh process after the app was
            // killed (the exact case this worker exists for), that static ref
            // is null — so we reconstruct a queue manager from the config
            // persisted at init and flush the same on-disk Room queue.
            val queueManager = queueManagerRef ?: reconstruct(applicationContext)
            if (queueManager == null) {
                // No live SDK and nothing persisted to reconstruct from →
                // there's nothing to flush. Don't retry; it would never succeed.
                return Result.success()
            }

            queueManager.flush()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        /** SharedPreferences file shared with [dev.sankofa.sdk.Sankofa]'s internal state. */
        private const val PREFS_NAME = "sankofa_internal"
        private const val KEY_SYNC_API_KEY = "sync_api_key"
        private const val KEY_SYNC_ENDPOINT = "sync_endpoint"

        /**
         * Set by [dev.sankofa.sdk.Sankofa.init] so the worker can reach the live
         * queue without a DI framework while the process is alive. May be null
         * after process death — see [reconstruct].
         */
        internal var queueManagerRef: EventQueueManager? = null

        /**
         * Persists the minimum config the worker needs to rebuild a queue
         * manager in a fresh process. Called from [dev.sankofa.sdk.Sankofa.init].
         */
        fun persistConfig(context: Context, apiKey: String, baseEndpoint: String) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SYNC_API_KEY, apiKey)
                .putString(KEY_SYNC_ENDPOINT, baseEndpoint)
                .apply()
        }

        /**
         * Rebuilds an [EventQueueManager] from persisted config. Returns null
         * when the SDK was never initialised on this device (nothing to flush).
         * The Room DB is a process-wide singleton, so the rebuilt manager
         * drains exactly the same queue the live one would have.
         */
        private fun reconstruct(context: Context): EventQueueManager? {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val apiKey = prefs.getString(KEY_SYNC_API_KEY, null) ?: return null
            val endpoint = prefs.getString(KEY_SYNC_ENDPOINT, null) ?: return null

            val logger = SankofaLogger(debug = false)
            val httpClient = SankofaHttpClient(
                apiKey = apiKey,
                batchEndpoint = "$endpoint/api/v1/batch",
                logger = logger,
            )
            return EventQueueManager(
                context = context.applicationContext,
                httpClient = httpClient,
                logger = logger,
            )
        }

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
