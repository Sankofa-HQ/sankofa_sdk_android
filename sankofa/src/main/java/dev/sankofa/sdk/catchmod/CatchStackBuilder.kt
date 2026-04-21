package dev.sankofa.sdk.catchmod

/**
 * Convert a Java/Kotlin Throwable into the wire's [CatchException]
 * shape. Chained causes (`initCause` / `Throwable.cause`) become the
 * `chained` list.
 */
object CatchStackBuilder {

    fun fromThrowable(
        t: Throwable,
        mechanism: CatchMechanism? = null,
        depth: Int = 0,
    ): CatchException {
        // Guard against runaway chain — "caused by" rings up to 10 is
        // plenty for any real bug.
        val chained = if (depth < 10 && t.cause != null) {
            listOf(fromThrowable(t.cause!!, null, depth + 1))
        } else {
            null
        }

        return CatchException(
            type = t.javaClass.simpleName.ifEmpty { t.javaClass.name },
            value = t.message ?: t.javaClass.name,
            module = t.javaClass.`package`?.name,
            mechanism = mechanism,
            stacktrace = buildStackTrace(t.stackTrace),
            chained = chained,
        )
    }

    private fun buildStackTrace(elements: Array<StackTraceElement>): CatchStackTrace? {
        if (elements.isEmpty()) return null
        // Array is newest-first; wire wants oldest-first.
        val frames = elements.reversed().map { toFrame(it) }
        return CatchStackTrace(frames)
    }

    private fun toFrame(st: StackTraceElement): CatchStackFrame {
        val file = st.fileName
        val cls = st.className
        // Detect in-app heuristically: framework / stdlib packages
        // never belong to the customer.
        val inApp = when {
            cls.startsWith("android.") -> false
            cls.startsWith("androidx.") -> false
            cls.startsWith("java.") -> false
            cls.startsWith("kotlin.") -> false
            cls.startsWith("kotlinx.") -> false
            cls.startsWith("dalvik.") -> false
            cls.startsWith("com.google.") && !cls.startsWith("com.google.firebase") -> false
            else -> true
        }
        return CatchStackFrame(
            filename = file,
            function = "$cls.${st.methodName}",
            module = cls,
            lineno = st.lineNumber.takeIf { it > 0 },
            inApp = inApp,
            platform = "java",
        )
    }
}
