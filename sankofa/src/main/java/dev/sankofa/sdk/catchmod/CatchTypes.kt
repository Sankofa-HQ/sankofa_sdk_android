package dev.sankofa.sdk.catchmod

// Sankofa Catch wire contract — Kotlin mirror of
// server/engine/ee/catch/wire.go. Renames in this file would be a
// wire-contract break; every SDK serialises to the same JSON.

const val CATCH_WIRE_VERSION = 1

enum class CatchLevel(val wireName: String) {
    FATAL("fatal"),
    ERROR("error"),
    WARNING("warning"),
    INFO("info"),
    DEBUG("debug"),
}

data class CatchSDKInfo(val name: String, val version: String)

data class CatchMechanism(
    val type: String,
    val handled: Boolean,
    val description: String? = null,
)

data class CatchStackFrame(
    val filename: String? = null,
    val function: String? = null,
    val module: String? = null,
    val lineno: Int? = null,
    val colno: Int? = null,
    val absPath: String? = null,
    val inApp: Boolean? = null,
    val platform: String? = null,
    val instructionAddr: String? = null,
    val pkg: String? = null,
    val symbol: String? = null,
    val symbolAddr: String? = null,
    val addrMode: String? = null,
)

data class CatchStackTrace(val frames: List<CatchStackFrame>)

data class CatchException(
    val type: String,
    val value: String,
    val module: String? = null,
    val mechanism: CatchMechanism? = null,
    val stacktrace: CatchStackTrace? = null,
    val chained: List<CatchException>? = null,
)

data class CatchUserContext(
    val id: String? = null,
    val email: String? = null,
    val username: String? = null,
    val ipAddress: String? = null,
    val segment: String? = null,
    val data: Map<String, String>? = null,
)

data class CatchDeviceContext(
    val os: String? = null,
    val osVersion: String? = null,
    val model: String? = null,
    val arch: String? = null,
    val memoryMb: Long? = null,
    val locale: String? = null,
    val country: String? = null,
    val timezone: String? = null,
    val appVersion: String? = null,
    val online: Boolean? = null,
)

data class CatchBreadcrumb(
    val tsMs: Long = System.currentTimeMillis(),
    val type: String,
    val category: String? = null,
    val message: String? = null,
    val level: CatchLevel? = null,
    val data: Map<String, Any?>? = null,
)

// Debug metadata — consumed by the M5 symbolicator worker to match
// native NDK frames to their unstripped .so files under ASLR.
data class CatchDebugImage(
    val type: String,               // "elf" for Android NDK binaries
    val debugId: String,            // GNU build-id (lowercased hex) or truncated
    val codeId: String? = null,
    val codeFile: String? = null,
    val imageAddr: String,          // "0x" + hex load address
    val imageSize: Long? = null,
    val imageVmaddr: String? = null,
    val arch: String? = null,
)

data class CatchDebugSDKInfo(
    val sdkName: String? = null,
    val versionMajor: Int? = null,
    val versionMinor: Int? = null,
    val versionPatchlevel: Int? = null,
)

data class CatchDebugMeta(
    val images: List<CatchDebugImage>? = null,
    val sdkInfo: CatchDebugSDKInfo? = null,
)

/** The full event envelope. Serialised to the frozen V1 wire shape. */
data class CatchEvent(
    val eventId: String,
    val tsMs: Long,
    val environment: String,
    val level: CatchLevel,
    val type: String,
    val platform: String,
    val sdk: CatchSDKInfo,

    val exception: CatchException? = null,
    val message: String? = null,

    val distinctId: String? = null,
    val anonId: String? = null,
    val sessionId: String? = null,

    val tags: Map<String, String>? = null,
    val extra: Map<String, Any?>? = null,
    val user: CatchUserContext? = null,
    val device: CatchDeviceContext? = null,
    val release: String? = null,

    val breadcrumbs: List<CatchBreadcrumb>? = null,
    val fingerprint: List<String>? = null,

    val flagSnapshot: Map<String, String>? = null,
    val configSnapshot: Map<String, Any?>? = null,

    val traceId: String? = null,
    val spanId: String? = null,
    val replayChunkIndex: Int? = null,
    val debugMeta: CatchDebugMeta? = null,
)

data class CatchHandshakeConfig(
    val enabled: Boolean? = null,
    val wireVersion: Int? = null,
    val ingestUrl: String? = null,
    val errorSampleRate: Double? = null,
    val transactionSampleRate: Double? = null,
    val profilesSampleRate: Double? = null,
    val replayOnErrorEnabled: Boolean? = null,
    val replayBurstSeconds: Int? = null,
    val breadcrumbsMaxBuffer: Int? = null,
    val reason: String? = null,
) {
    companion object {
        fun fromWire(raw: Map<String, Any?>?): CatchHandshakeConfig {
            if (raw == null) return CatchHandshakeConfig()
            @Suppress("UNCHECKED_CAST")
            val sampling = raw["sampling"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val replay = raw["replay"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val bc = raw["breadcrumbs"] as? Map<String, Any?>
            return CatchHandshakeConfig(
                enabled = raw["enabled"] as? Boolean,
                wireVersion = (raw["wire_version"] as? Number)?.toInt(),
                ingestUrl = raw["ingest_url"] as? String,
                errorSampleRate = (sampling?.get("error_sample_rate") as? Number)?.toDouble(),
                transactionSampleRate = (sampling?.get("transaction_sample_rate") as? Number)?.toDouble(),
                profilesSampleRate = (sampling?.get("profiles_sample_rate") as? Number)?.toDouble(),
                replayOnErrorEnabled = replay?.get("on_error_enabled") as? Boolean,
                replayBurstSeconds = (replay?.get("burst_seconds") as? Number)?.toInt(),
                breadcrumbsMaxBuffer = (bc?.get("max_buffer") as? Number)?.toInt(),
                reason = raw["reason"] as? String,
            )
        }
    }
}

data class CatchCaptureOptions(
    val level: CatchLevel? = null,
    val tags: Map<String, String>? = null,
    val extra: Map<String, Any?>? = null,
    val user: CatchUserContext? = null,
    val fingerprint: List<String>? = null,
    val traceId: String? = null,
    val spanId: String? = null,
)
