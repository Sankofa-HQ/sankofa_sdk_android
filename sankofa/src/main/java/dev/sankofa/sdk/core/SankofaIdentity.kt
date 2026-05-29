package dev.sankofa.sdk.core

import android.content.Context
import android.content.SharedPreferences
import dev.sankofa.sdk.Sankofa
import dev.sankofa.sdk.util.SankofaLogger
import java.util.UUID

/**
 * Manages the user's identity: an auto-generated anonymous ID and an optional
 * developer-supplied user ID (set via [identify]).
 *
 * Mirrors [sankofa_sdk_flutter/lib/src/sankofa_identity.dart].
 */
internal class SankofaIdentity(
    context: Context,
    private val logger: SankofaLogger,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Mutated by identify()/reset() on the IO pool and read lock-free from
    // arbitrary threads (track, screen, presence). @Volatile guarantees those
    // readers see the latest value; the [lock] makes the compound
    // identify/reset updates atomic against each other.
    @Volatile private var _anonymousId: String
    @Volatile private var _userId: String? = null
    private val lock = Any()

    init {
        _anonymousId = prefs.getString(KEY_ANON_ID, null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_ANON_ID, newId).apply()
            newId
        }
        _userId = prefs.getString(KEY_USER_ID, null)
    }

    /** The active distinct ID: userId if identified, otherwise the anonymous UUID. */
    val distinctId: String
        get() = _userId ?: _anonymousId

    val anonymousId: String get() = _anonymousId
    val userId: String? get() = _userId

    /**
     * Links an anonymous session to a known [userId].
     * Emits an alias event so the backend can merge the two profiles.
     * Returns the alias event map, or null if the user is already identified with the same ID.
     */
    fun identify(userId: String): Map<String, Any>? {
        val previousId: String
        synchronized(lock) {
            if (_userId == userId) return null
            previousId = _userId ?: _anonymousId
            _userId = userId
            prefs.edit().putString(KEY_USER_ID, userId).apply()
        }

        logger.debug("🔗 Identify: $previousId → $userId")
        return mapOf(
            "type" to "alias",
            "alias_id" to previousId,
            "distinct_id" to userId,
            "timestamp" to Sankofa.currentIsoTimestamp(),
            "message_id" to UUID.randomUUID().toString(),
        )
    }

    /**
     * Resets to a fresh anonymous identity. Clears the userId.
     */
    fun reset() {
        val newAnon: String
        synchronized(lock) {
            _userId = null
            newAnon = UUID.randomUUID().toString()
            _anonymousId = newAnon
            prefs.edit()
                .remove(KEY_USER_ID)
                .putString(KEY_ANON_ID, newAnon)
                .apply()
        }
        logger.debug("🔄 Identity reset – new anon ID: $newAnon")
    }

    companion object {
        private const val PREFS_NAME = "sankofa_identity"
        private const val KEY_ANON_ID = "sankofa_anon_id"
        private const val KEY_USER_ID = "sankofa_user_id"
    }
}
