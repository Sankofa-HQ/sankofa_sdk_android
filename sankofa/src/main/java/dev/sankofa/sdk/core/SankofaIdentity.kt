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

    private var _anonymousId: String
    private var _userId: String? = null

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
        if (_userId == userId) return null

        val previousId = distinctId
        _userId = userId
        prefs.edit().putString(KEY_USER_ID, userId).apply()

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
        _userId = null
        _anonymousId = UUID.randomUUID().toString()
        prefs.edit()
            .remove(KEY_USER_ID)
            .putString(KEY_ANON_ID, _anonymousId)
            .apply()
        logger.debug("🔄 Identity reset – new anon ID: $_anonymousId")
    }

    companion object {
        private const val PREFS_NAME = "sankofa_identity"
        private const val KEY_ANON_ID = "sankofa_anon_id"
        private const val KEY_USER_ID = "sankofa_user_id"
    }
}
