package dev.sankofa.sdk.catchmod

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Main-thread ANR (Application Not Responding) detector.
 *
 * Android triggers its system ANR dialog when the main thread doesn't
 * drain its message queue for ~5 seconds. We beat the OS by running
 * a background watchdog that:
 *
 *   1. Posts a lightweight "heartbeat" onto the main looper every
 *      `probeIntervalMs` milliseconds.
 *   2. On the watchdog thread, checks whether the last heartbeat
 *      completed within `anrThresholdMs` — if not, the main thread is
 *      stalled long enough to count as an ANR.
 *   3. When stalled, captures the main thread's stack trace via
 *      `Looper.getMainLooper().thread.stackTrace` and emits a
 *      CatchEvent with level=error, mechanism={type=anr, handled=false}.
 *
 * Why watchdog-thread instead of the Android-11 `getHistoricalProcessExitReasons`
 * API:
 *
 *   - Works on API < 30 (historical exit reasons are API 30+).
 *   - Captures the stack at ANR time, not at process death. The stack
 *     is what debuggers actually need.
 *   - No permission required.
 *
 * Memory footprint: two threads, one AtomicLong, one AtomicBoolean,
 * no ring buffers — safe for 1-GB handsets.
 */
internal class CatchAnrWatcher(
    private val capture: (CatchException) -> Unit,
    /** Heartbeat post interval. Short enough to catch sub-ANR stalls,
     *  long enough that the watchdog itself is negligible CPU. */
    private val probeIntervalMs: Long = 1_000L,
    /** Threshold at which we consider the main thread stuck. Android's
     *  own ANR kicks in around 5s; we report at 4s so the event lands
     *  *before* the OS ANR dialog, not after. */
    private val anrThresholdMs: Long = 4_000L,
    /** Minimum gap between ANR reports. Prevents a wedged app from
     *  emitting 100 events per minute. */
    private val cooldownMs: Long = 30_000L,
) {

    @Volatile private var running = false
    @Volatile private var lastProbeCompletedAt = System.currentTimeMillis()
    @Volatile private var lastProbeStartedAt = System.currentTimeMillis()
    @Volatile private var lastAnrReportedAt = 0L

    private var mainHandler: Handler? = null
    private var watcherThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        mainHandler = Handler(Looper.getMainLooper())
        watcherThread = Thread(::runLoop, "sankofa-catch-anr").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        watcherThread?.interrupt()
        watcherThread = null
        mainHandler = null
    }

    /**
     * The watchdog loop. Runs on a daemon thread off the main looper
     * so a wedged main thread can't hide an ANR from itself.
     */
    private fun runLoop() {
        while (running) {
            val now = System.currentTimeMillis()
            val mainStalledFor = now - lastProbeCompletedAt

            if (mainStalledFor >= anrThresholdMs && (now - lastAnrReportedAt) >= cooldownMs) {
                try {
                    reportAnr(mainStalledFor)
                    lastAnrReportedAt = now
                } catch (t: Throwable) {
                    Log.w("sankofa.catch", "ANR report failed", t)
                }
            }

            // Post a new heartbeat onto the main looper. If the main
            // thread is responsive, this runs within milliseconds and
            // updates lastProbeCompletedAt. If it's stalled, the post
            // sits in the queue and we notice next iteration.
            lastProbeStartedAt = now
            mainHandler?.post {
                lastProbeCompletedAt = System.currentTimeMillis()
            }

            try {
                Thread.sleep(probeIntervalMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * Build a synthetic CatchException from the main thread's stack
     * at the moment of the ANR. No throwable was actually thrown —
     * the process is still alive; we're just catching a stall.
     */
    private fun reportAnr(stalledMs: Long) {
        val mainThread = Looper.getMainLooper().thread
        val frames = mainThread.stackTrace.map { e ->
            CatchStackFrame(
                function = "${e.className}.${e.methodName}",
                filename = e.fileName,
                module = e.className,
                lineno = e.lineNumber.takeIf { it > 0 },
                inApp = isInApp(e.className),
            )
        }
        val exc = CatchException(
            type = "ANR",
            value = "Application Not Responding — main thread stalled ${stalledMs}ms",
            mechanism = CatchMechanism(type = "anr", handled = false),
            stacktrace = CatchStackTrace(frames = frames),
        )
        capture(exc)
    }

    /**
     * Heuristic in-app detection. Same rule the rest of the Android
     * Catch SDK uses: anything in the java/android/kotlin stdlibs is
     * library code, everything else is in-app.
     */
    private fun isInApp(className: String): Boolean {
        return !(
            className.startsWith("java.") ||
            className.startsWith("javax.") ||
            className.startsWith("android.") ||
            className.startsWith("androidx.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("kotlinx.") ||
            className.startsWith("com.google.") ||
            className.startsWith("com.android.")
        )
    }
}
