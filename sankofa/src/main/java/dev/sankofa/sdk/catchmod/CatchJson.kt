package dev.sankofa.sdk.catchmod

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Kotlin → wire-JSON serialiser. Uses Android's bundled
 * org.json package so we don't drag in Gson/Moshi for every SDK
 * consumer. Every key name here MUST match the Go wire.go struct
 * tags 1:1.
 */
internal object CatchJson {

    fun encodeBatch(events: List<CatchEvent>): String {
        val root = JSONObject()
        root.put("wire_version", CATCH_WIRE_VERSION)
        val arr = JSONArray()
        for (e in events) arr.put(encodeEvent(e))
        root.put("events", arr)
        return root.toString()
    }

    fun encodeEvent(e: CatchEvent): JSONObject {
        val o = JSONObject()
        o.put("wire_version", CATCH_WIRE_VERSION)
        o.put("event_id", e.eventId)
        o.put("ts_ms", e.tsMs)
        o.put("environment", e.environment)
        o.put("level", e.level.wireName)
        o.put("type", e.type)
        o.put("platform", e.platform)
        o.put("sdk", JSONObject().put("name", e.sdk.name).put("version", e.sdk.version))

        e.distinctId?.let { o.put("distinct_id", it) }
        e.anonId?.let { o.put("anon_id", it) }
        e.sessionId?.let { o.put("session_id", it) }
        e.exception?.let { o.put("exception", encodeException(it)) }
        e.message?.let { o.put("message", it) }
        e.tags?.let { o.put("tags", JSONObject(it as Map<*, *>)) }
        e.extra?.let { o.put("extra", toJSON(it)) }
        e.user?.let { o.put("user", encodeUser(it)) }
        e.device?.let { o.put("device", encodeDevice(it)) }
        e.release?.let { o.put("release", it) }
        e.breadcrumbs?.let { bc ->
            val arr = JSONArray()
            for (b in bc) arr.put(encodeBreadcrumb(b))
            o.put("breadcrumbs", arr)
        }
        e.fingerprint?.let { o.put("fingerprint", JSONArray(it)) }
        e.flagSnapshot?.let { o.put("flag_snapshot", JSONObject(it as Map<*, *>)) }
        e.configSnapshot?.let { o.put("config_snapshot", toJSON(it)) }
        e.traceId?.let { o.put("trace_id", it) }
        e.spanId?.let { o.put("span_id", it) }
        e.replayChunkIndex?.let { o.put("replay_chunk_index", it) }
        e.debugMeta?.let { o.put("debug_meta", encodeDebugMeta(it)) }
        return o
    }

    private fun encodeException(ex: CatchException): JSONObject {
        val o = JSONObject()
        o.put("type", ex.type)
        o.put("value", ex.value)
        ex.module?.let { o.put("module", it) }
        ex.mechanism?.let { o.put("mechanism", encodeMechanism(it)) }
        ex.stacktrace?.let { o.put("stacktrace", encodeStackTrace(it)) }
        ex.chained?.let {
            val arr = JSONArray()
            for (c in it) arr.put(encodeException(c))
            o.put("chained", arr)
        }
        return o
    }

    private fun encodeMechanism(m: CatchMechanism) = JSONObject().apply {
        put("type", m.type)
        put("handled", m.handled)
        m.description?.let { put("description", it) }
    }

    private fun encodeStackTrace(st: CatchStackTrace): JSONObject {
        val arr = JSONArray()
        for (f in st.frames) arr.put(encodeFrame(f))
        return JSONObject().put("frames", arr)
    }

    private fun encodeFrame(f: CatchStackFrame) = JSONObject().apply {
        f.filename?.let { put("filename", it) }
        f.function?.let { put("function", it) }
        f.module?.let { put("module", it) }
        f.lineno?.let { put("lineno", it) }
        f.colno?.let { put("colno", it) }
        f.absPath?.let { put("abs_path", it) }
        f.inApp?.let { put("in_app", it) }
        f.platform?.let { put("platform", it) }
        f.instructionAddr?.let { put("instruction_addr", it) }
        f.pkg?.let { put("package", it) }
        f.symbol?.let { put("symbol", it) }
        f.symbolAddr?.let { put("symbol_addr", it) }
        f.addrMode?.let { put("addr_mode", it) }
    }

    private fun encodeUser(u: CatchUserContext) = JSONObject().apply {
        u.id?.let { put("id", it) }
        u.email?.let { put("email", it) }
        u.username?.let { put("username", it) }
        u.ipAddress?.let { put("ip_address", it) }
        u.segment?.let { put("segment", it) }
        u.data?.let { put("data", JSONObject(it as Map<*, *>)) }
    }

    private fun encodeDevice(d: CatchDeviceContext) = JSONObject().apply {
        d.os?.let { put("os", it) }
        d.osVersion?.let { put("os_version", it) }
        d.model?.let { put("model", it) }
        d.arch?.let { put("arch", it) }
        d.memoryMb?.let { put("memory_mb", it) }
        d.locale?.let { put("locale", it) }
        d.country?.let { put("country", it) }
        d.timezone?.let { put("timezone", it) }
        d.appVersion?.let { put("app_version", it) }
        d.online?.let { put("online", it) }
    }

    private fun encodeBreadcrumb(b: CatchBreadcrumb) = JSONObject().apply {
        put("ts_ms", b.tsMs)
        put("type", b.type)
        b.category?.let { put("category", it) }
        b.message?.let { put("message", it) }
        b.level?.let { put("level", it.wireName) }
        b.data?.let { put("data", toJSON(it)) }
    }

    private fun encodeDebugMeta(dm: CatchDebugMeta) = JSONObject().apply {
        dm.images?.let {
            val arr = JSONArray()
            for (img in it) arr.put(encodeDebugImage(img))
            put("images", arr)
        }
        dm.sdkInfo?.let { put("sdk_info", encodeDebugSDKInfo(it)) }
    }

    private fun encodeDebugImage(img: CatchDebugImage) = JSONObject().apply {
        put("type", img.type)
        put("debug_id", img.debugId)
        img.codeId?.let { put("code_id", it) }
        img.codeFile?.let { put("code_file", it) }
        put("image_addr", img.imageAddr)
        img.imageSize?.let { put("image_size", it) }
        img.imageVmaddr?.let { put("image_vmaddr", it) }
        img.arch?.let { put("arch", it) }
    }

    private fun encodeDebugSDKInfo(i: CatchDebugSDKInfo) = JSONObject().apply {
        i.sdkName?.let { put("sdk_name", it) }
        i.versionMajor?.let { put("version_major", it) }
        i.versionMinor?.let { put("version_minor", it) }
        i.versionPatchlevel?.let { put("version_patchlevel", it) }
    }

    private fun toJSON(v: Any?): Any {
        return when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val o = JSONObject()
                for ((k, value) in v) {
                    if (k is String) o.put(k, toJSON(value))
                }
                o
            }
            is List<*> -> {
                val a = JSONArray()
                for (x in v) a.put(toJSON(x))
                a
            }
            is Array<*> -> {
                val a = JSONArray()
                for (x in v) a.put(toJSON(x))
                a
            }
            else -> v
        }
    }
}
