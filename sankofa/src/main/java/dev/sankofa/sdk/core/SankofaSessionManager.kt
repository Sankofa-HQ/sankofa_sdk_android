package dev.sankofa.sdk.core

import android.content.Context
import android.content.SharedPreferences
import dev.sankofa.sdk.util.SankofaLogger
import java.util.UUID

/**
 * Manages session lifetime.
 * A session expires after [SESSION_TIMEOUT_MS] of inactivity.
 * When a new session starts, [onNewSession] is called so the caller
 * can fetch remote replay config and start recording.
 *
 * Mirrors [sankofa_sdk_flutter/lib/src/sankofa_session_manager.dart].
 */
internal class SankofaSessionManager(
    context: Context,
    private val logger: SankofaLogger,
    private val onNewSession: suspend (sessionId: String) -> Unit,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var _sessionId: String? = null
    val sessionId: String get() = _sessionId ?: error("Session not started – call refresh() first")

    /**
     * Checks whether the current session has expired.
     * If so (or if there is no session), starts a new one and fires [onNewSession].
     */
    suspend fun refresh() {
        val now = System.currentTimeMillis()
        val lastEventTime = prefs.getLong(KEY_LAST_EVENT, 0L)
        val storedSessionId = prefs.getString(KEY_SESSION_ID, null)

        val expired = storedSessionId == null ||
                (now - lastEventTime) > SESSION_TIMEOUT_MS

        if (expired) {
            startNewSession()
        } else {
            _sessionId = storedSessionId
        }

        prefs.edit().putLong(KEY_LAST_EVENT, now).apply()
    }

    /** Force-starts a new session regardless of timeout state. */
    suspend fun startNewSession() {
        val newId = UUID.randomUUID().toString()
        _sessionId = newId
        prefs.edit()
            .putString(KEY_SESSION_ID, newId)
            .putLong(KEY_LAST_EVENT, System.currentTimeMillis())
            .apply()
        logger.debug("🆕 New session: $newId")
        onNewSession(newId)
    }

    companion object {
        private const val PREFS_NAME = "sankofa_session"
        private const val KEY_SESSION_ID = "sankofa_session_id"
        private const val KEY_LAST_EVENT = "sankofa_last_event_time"

        /** 30 minutes of inactivity triggers a new session. */
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
