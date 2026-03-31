package dev.sankofa.sdk.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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

    /** Call once during [Sankofa.init] to wire up all observers. */
    fun register() {
        // 1. Per-activity observation for session replay
        application.registerActivityLifecycleCallbacks(activityCallbacks)

        // 2. Process-level observation for app foreground/background
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** Tear down all observers (called on [Sankofa.shutdown]). */
    fun unregister() {
        application.unregisterActivityLifecycleCallbacks(activityCallbacks)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        flushJob?.cancel()
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

        // No-ops
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
