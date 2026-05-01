package dev.sankofa.sdk.pulse

import com.google.gson.annotations.SerializedName

/**
 * Wire-shape types for the Pulse Android SDK. Mirrors the server's
 * JSON envelopes from `server/engine/ee/pulse/`.
 *
 * Kept in one file so the network layer + the renderer share a
 * single source of truth — drift here would silently break ingest.
 */

internal data class PulseQuestionOption(
    @SerializedName("key") val key: String,
    @SerializedName("label") val label: String,
    @SerializedName("image_url") val imageUrl: String? = null,
)

internal data class PulseQuestion(
    @SerializedName("id") val id: String,
    @SerializedName("kind") val kind: String,
    @SerializedName("prompt") val prompt: String,
    @SerializedName("helptext") val helptext: String? = null,
    @SerializedName("required") val required: Boolean = false,
    @SerializedName("order_index") val orderIndex: Int = 0,
    @SerializedName("options") val options: List<PulseQuestionOption>? = null,
    /** Per-kind validation block; opaque object decoded lazily. */
    @SerializedName("validation") val validation: Map<String, Any?>? = null,
)

internal data class PulseTheme(
    @SerializedName("primary_color") val primaryColor: String? = null,
    @SerializedName("background_color") val backgroundColor: String? = null,
    @SerializedName("foreground_color") val foregroundColor: String? = null,
    @SerializedName("muted_color") val mutedColor: String? = null,
    @SerializedName("border_color") val borderColor: String? = null,
    @SerializedName("font_family") val fontFamily: String? = null,
    @SerializedName("dark_mode") val darkMode: String? = null,
    @SerializedName("logo_url") val logoUrl: String? = null,
)

@ConsistentCopyVisibility
data class PulseSurvey internal constructor(
    @SerializedName("id") val id: String,
    @SerializedName("kind") val kind: String,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("questions") internal val questions: List<PulseQuestion> = emptyList(),
    @SerializedName("theme") internal val theme: PulseTheme? = null,
)

internal data class PulseHandshakeResponse(
    @SerializedName("surveys") val surveys: List<PulseSurvey> = emptyList(),
)

/**
 * Full survey bundle — survey row + targeting rules + branching
 * rules. Themes, translations and partial state will land here as
 * those features graduate; the Go-side wire shape includes them
 * all already.
 */
internal data class PulseSurveyBundle(
    val survey: PulseSurvey = PulseSurvey(
        id = "",
        kind = "",
        name = "",
    ),
    val targetingRules: List<dev.sankofa.sdk.pulse.targeting.PulseTargetingRule> = emptyList(),
    val branchingRules: List<dev.sankofa.sdk.pulse.branching.PulseBranchingRule> = emptyList(),
)

/**
 * Respondent identity envelope. Mirrors the server's
 * `ingestRespondent` (server/engine/ee/pulse/handlers_ingest.go).
 * At least one of the three fields should be set; the server
 * tolerates all-empty for fully anonymous submissions but the
 * dashboard groups responses by the most-specific id present.
 */
internal data class PulseRespondent(
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("external_id") val externalId: String? = null,
    @SerializedName("email") val email: String? = null,
)

/**
 * Final-submit payload. The wire shape matches the server's
 * `ingestPayload`: `answers` is a map keyed by question_id (NOT a
 * list of `{question_id, value}` pairs — that earlier shape was
 * silently 400'd in production because the Go decoder treats arrays
 * + maps as different types). Web + RN already speak this shape;
 * iOS, Android, and Flutter follow.
 */
internal data class PulseSubmitPayload(
    @SerializedName("survey_id") val surveyId: String,
    @SerializedName("respondent") val respondent: PulseRespondent = PulseRespondent(),
    @SerializedName("context") val context: PulseContext? = null,
    @SerializedName("submitted_at") val submittedAt: String? = null,
    @SerializedName("answers") val answers: Map<String, Any?> = emptyMap(),
)

internal data class PulseContext(
    @SerializedName("session_id") val sessionId: String? = null,
    @SerializedName("anonymous_id") val anonymousId: String? = null,
    @SerializedName("platform") val platform: String? = "android",
    @SerializedName("os_version") val osVersion: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
    @SerializedName("locale") val locale: String? = null,
)

internal data class PulseSubmitResponse(
    @SerializedName("id") val id: String? = null,
    @SerializedName("survey_id") val surveyId: String? = null,
)

/**
 * Wire payload for `POST /api/pulse/partial`. Same answers shape
 * (map keyed by question_id) as the final-submit body so the SDK
 * doesn't have to reformat between save + submit.
 */
internal data class PulsePartialUpsert(
    @SerializedName("survey_id") val surveyId: String,
    @SerializedName("respondent") val respondent: PulseRespondent,
    @SerializedName("context") val context: PulseContext? = null,
    @SerializedName("answers") val answers: Map<String, Any?> = emptyMap(),
    @SerializedName("current_question_id") val currentQuestionId: String? = null,
)

/** Shape returned by `POST /api/pulse/partial`. */
internal data class PulsePartialAck(
    @SerializedName("id") val id: String? = null,
    @SerializedName("survey_id") val surveyId: String? = null,
    @SerializedName("current_question_id") val currentQuestionId: String? = null,
    @SerializedName("version_number") val versionNumber: Int? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
)

/**
 * Shape returned by `GET /api/pulse/partial`. Mirrors the server's
 * `ResponsePartial` row — every field nullable so we can decode
 * partial server responses cleanly during a schema rollout.
 */
internal data class PulsePartial(
    @SerializedName("id") val id: String? = null,
    @SerializedName("survey_id") val surveyId: String? = null,
    @SerializedName("respondent_external_id") val respondentExternalId: String? = null,
    @SerializedName("respondent_user_id") val respondentUserId: String? = null,
    @SerializedName("respondent_email") val respondentEmail: String? = null,
    @SerializedName("answers") val answers: Map<String, Any?>? = null,
    @SerializedName("current_question_id") val currentQuestionId: String? = null,
    @SerializedName("version_number") val versionNumber: Int? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
)
