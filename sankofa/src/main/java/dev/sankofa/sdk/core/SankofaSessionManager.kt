package dev.sankofa.sdk.core

import android.content.Context
import android.content.SharedPreferences
import dev.sankofa.sdk.util.SankofaLogger
import java.util.UUID

/**
 * Manages session lifetime.
 * Following the enterprise standard, a session is rotated if the app is resumed
 * after being in the background for more than [SESSION_TIMEOUT_MS].
 */
internal class SankofaSessionManager(
    context: Context,
    private val logger: SankofaLogger,
    private val onNewSession: suspend (sessionId: String) -> Unit,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var _sessionId: String? = null
    val sessionId: String get() = _sessionId ?: generateAndStoreSessionId()

    /**
     * Checks whether the current session has expired based on background duration.
     * If so, starts a new one and fires [onNewSession].
     * Returns true if a new session was rotated.
     */
    suspend fun checkSessionRotationOnResume(): Boolean {
        val now = System.currentTimeMillis()
        val lastBackground = prefs.getLong(KEY_LAST_BACKGROUND, 0L)
        
        if (lastBackground == 0L) return false

        val elapsed = now - lastBackground
        if (elapsed > SESSION_TIMEOUT_MS) {
            logger.debug("⌛ Session timed out after ${elapsed / 60000} minutes. Rotating.")
            startNewSession()
            prefs.edit().remove(KEY_LAST_BACKGROUND).apply()
            return true
        }
        
        return false
    }

    /**
     * Called when the app moves to the background.
     */
    fun setLastBackgroundTime() {
        prefs.edit().putLong(KEY_LAST_BACKGROUND, System.currentTimeMillis()).apply()
    }

    /**
     * Standard refresh to ensure sessionId is ready.
     */
    suspend fun refresh() {
        if (_sessionId == null) {
            val stored = prefs.getString(KEY_SESSION_ID, null)
            if (stored == null) {
                startNewSession()
            } else {
                _sessionId = stored
                onNewSession(_sessionId!!)
            }
        }
    }

    /** Force-starts a new session regardless of timeout state. */
    suspend fun startNewSession() {
        _sessionId = generateAndStoreSessionId()
        onNewSession(_sessionId!!)
    }

    private fun generateAndStoreSessionId(): String {
        val newId = "s_${UUID.randomUUID()}"
        prefs.edit()
            .putString(KEY_SESSION_ID, newId)
            .apply()
        logger.debug("🆕 Session ID: $newId")
        return newId
    }

    companion object {
        private const val PREFS_NAME = "sankofa_session"
        private const val KEY_SESSION_ID = "sankofa_session_id"
        private const val KEY_LAST_BACKGROUND = "sankofa_last_background_time"

        /** 30 minutes of inactivity triggers a new session. */
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
