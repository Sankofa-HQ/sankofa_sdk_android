package dev.sankofa.sdk.pulse

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Persistent queue for offline survey submissions. Single JSON file
 * in the SDK's files dir; not Room-backed because survey volumes are
 * low (a handful of completions per session) — the analytics event
 * queue uses Room, this lighter shape is enough for Pulse.
 *
 * Thread-safety: every public method synchronises on `lock`. The
 * underlying file is rewritten atomically on every persist so a
 * mid-write crash leaves either the prior or the next state, never
 * a torn file.
 */
internal class PulseQueue(context: Context) {

    private val storeFile: File = File(
        context.filesDir.resolve("sankofa").also { it.mkdirs() },
        "pulse-queue.json",
    )
    private val gson = Gson()
    private val type = object : TypeToken<List<PulseSubmitPayload>>() {}.type
    private val lock = Any()
    private val pending: MutableList<PulseSubmitPayload> = loadFromDisk()

    fun count(): Int = synchronized(lock) { pending.size }

    fun enqueue(payload: PulseSubmitPayload) {
        synchronized(lock) {
            pending.add(payload)
            persist()
        }
    }

    /**
     * Drain attempts to flush every pending payload through the
     * supplied submit function. Successes are removed; failures
     * stay in the queue for the next drain.
     */
    fun drain(submit: (PulseSubmitPayload) -> PulseSubmitResponse): DrainResult {
        val snapshot: List<PulseSubmitPayload> = synchronized(lock) { pending.toList() }
        var sent = 0
        var failed = 0
        val remaining = mutableListOf<PulseSubmitPayload>()
        for (p in snapshot) {
            try {
                submit(p)
                sent += 1
            } catch (_: Throwable) {
                failed += 1
                remaining.add(p)
            }
        }
        synchronized(lock) {
            pending.clear()
            pending.addAll(remaining)
            persist()
        }
        return DrainResult(sent = sent, failed = failed)
    }

    fun clear() {
        synchronized(lock) {
            pending.clear()
            persist()
        }
    }

    private fun loadFromDisk(): MutableList<PulseSubmitPayload> {
        if (!storeFile.exists()) return mutableListOf()
        return try {
            val txt = storeFile.readText()
            if (txt.isBlank()) mutableListOf()
            else (gson.fromJson<List<PulseSubmitPayload>>(txt, type) ?: emptyList()).toMutableList()
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    private fun persist() {
        try {
            val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
            tmp.writeText(gson.toJson(pending))
            tmp.renameTo(storeFile)
        } catch (_: Throwable) {
            // Disk-full or sandbox failure — keep the in-memory copy
            // so the next attempt this session has a chance, accept
            // worst-case loss on app death.
        }
    }

    data class DrainResult(val sent: Int, val failed: Int)
}
