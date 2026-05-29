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

    @Volatile private var _sessionId: String? = null
    private val idLock = Any()

    /**
     * The active session id. Lazily generated under [idLock] on first access so
     * concurrent readers can't each mint a different id, and the result is
     * cached in [_sessionId] (the previous implementation regenerated + stored
     * a new id on every call until refresh() ran).
     */
    val sessionId: String
        get() {
            _sessionId?.let { return it }
            return synchronized(idLock) {
                _sessionId ?: generateAndStoreSessionId().also { _sessionId = it }
            }
        }

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
        // Resolve (and assign) the session id under the lock, then fire the
        // suspend callback OUTSIDE the lock — onNewSession can't be invoked
        // while holding a monitor. A null result means the id was already set,
        // so there's no new session to announce.
        val toAnnounce: String? = synchronized(idLock) {
            if (_sessionId != null) {
                null
            } else {
                val id = prefs.getString(KEY_SESSION_ID, null) ?: generateAndStoreSessionId()
                _sessionId = id
                id
            }
        }
        toAnnounce?.let { onNewSession(it) }
    }

    /** Force-starts a new session regardless of timeout state. */
    suspend fun startNewSession() {
        val id = synchronized(idLock) {
            generateAndStoreSessionId().also { _sessionId = it }
        }
        onNewSession(id)
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
