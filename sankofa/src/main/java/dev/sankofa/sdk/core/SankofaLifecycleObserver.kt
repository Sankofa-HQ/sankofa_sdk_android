package dev.sankofa.sdk.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.sankofa.sdk.Sankofa
import dev.sankofa.sdk.data.EventQueueManager
import dev.sankofa.sdk.network.SyncWorker
import dev.sankofa.sdk.replay.ReplayRecorder
import dev.sankofa.sdk.util.SankofaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto-registers into the [Application] to observe:
 * 1. Per-Activity lifecycle (via [Application.ActivityLifecycleCallbacks]) → attaches/detaches
 *    the [ReplayRecorder] to the active window.
 * 2. Process-level lifecycle (via [ProcessLifecycleOwner]) → tracks app foreground/background
 *    events and drives the 30-second periodic flush loop.
 */
internal class SankofaLifecycleObserver(
    private val application: Application,
    private val logger: SankofaLogger,
    private val sessionManager: SankofaSessionManager,
    private val queueManager: EventQueueManager,
    private val replayRecorder: ReplayRecorder,
    private val trackLifecycleEvents: Boolean,
    private val recordSessions: Boolean,
    private val flushIntervalSeconds: Int,
    private val onTrack: (String) -> Unit,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var flushJob: Job? = null

    /**
     * Call once during [Sankofa.init] to wire up all observers.
     *
     * `ProcessLifecycleOwner.addObserver` is required by androidx.lifecycle to
     * be called on the main thread. We dispatch to the main looper if needed
     * so that callers from background threads (e.g. the React Native bridge,
     * which runs on the JS thread) don't crash.
     *
     * ## Late-init bootstrap
     * If the SDK is initialized AFTER an Activity has already been resumed
     * (the typical RN/Flutter case where init happens from JS, not from
     * `Application.onCreate`), `registerActivityLifecycleCallbacks` will NOT
     * retroactively fire `onActivityResumed`, so the replay recorder would
     * never attach. We detect the currently-resumed activity via reflection
     * on `ActivityThread` and bootstrap the recorder against it.
     */
    fun register() {
        // 1. Per-activity observation for session replay (thread-safe)
        application.registerActivityLifecycleCallbacks(activityCallbacks)

        // 2. Process-level observation + late-init bootstrap on the main thread
        runOnMain {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)

            // Late-init bootstrap: if an activity is already resumed, manually
            // run the resume path so the replay recorder + screen tagger attach.
            val current = currentResumedActivity()
            if (current != null) {
                logger.debug("🔁 Late init detected – bootstrapping recorder against ${current.localClassName}")
                activityCallbacks.onActivityResumed(current)
            } else {
                logger.debug("ℹ️ No resumed activity at init time – will attach on next resume")
            }
        }
    }

    /**
     * Walks `ActivityThread.mActivities` to find the currently-resumed Activity.
     * Returns null if reflection fails (e.g. on a future Android version that
     * removes the field). This is the same approach used by Sentry, PostHog,
     * and Datadog SDKs to handle late init from non-Application contexts.
     */
    private fun currentResumedActivity(): Activity? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val activities = activitiesField.get(currentActivityThread) as? Map<Any, Any> ?: return null

            for (record in activities.values) {
                val recordClass = record.javaClass
                val pausedField = recordClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                val paused = pausedField.getBoolean(record)
                if (!paused) {
                    val activityField = recordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    return activityField.get(record) as? Activity
                }
            }
            null
        } catch (t: Throwable) {
            logger.warn("⚠️ currentResumedActivity reflection failed: ${t.message}")
            null
        }
    }

    /** Tear down all observers (called on [Sankofa.shutdown]). */
    fun unregister() {
        application.unregisterActivityLifecycleCallbacks(activityCallbacks)
        runOnMain {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        }
        flushJob?.cancel()
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post { block() }
    }

    // --- ProcessLifecycleOwner callbacks ---

    override fun onStart(owner: LifecycleOwner) {
        // App came to foreground
        scope.launch {
            // 🚀 Check for session rotation (30m rule)
            if (sessionManager.checkSessionRotationOnResume()) {
                onTrack("\$session_start")
            }
            if (trackLifecycleEvents) onTrack("\$app_foregrounded")
        }

        // Start the 30-second periodic flush loop
        flushJob?.cancel()
        flushJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(flushIntervalSeconds * 1000L)
                logger.debug("⏱ Periodic flush triggered")
                queueManager.flush()
            }
        }
        logger.debug("▶️ App foregrounded – flush loop started")
    }

    override fun onStop(owner: LifecycleOwner) {
        // App moved to background
        
        // 🚀 Capture background time for rotation
        sessionManager.setLastBackgroundTime()
        
        flushJob?.cancel()
        if (trackLifecycleEvents) onTrack("\$app_backgrounded")
        replayRecorder.stopRecording()

        // Fire a WorkManager one-time job to flush whatever is left
        SyncWorker.scheduleOneTime(application)
        logger.debug("⏸ App backgrounded – sync job scheduled")
    }

    // --- Per-Activity callbacks for session replay ---

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            // 🚀 Automatic Screen Tagging Fallback
            Sankofa.onActivityResumed(activity)

            if (recordSessions) {
                replayRecorder.startRecording(activity)
                logger.debug("🎥 Replay attached to ${activity.localClassName}")
            }
        }

        override fun onActivityPaused(activity: Activity) {
            replayRecorder.stopRecording()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            Sankofa.onActivityCreated(activity)
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
