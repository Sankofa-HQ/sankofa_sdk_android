package dev.sankofa.sdk.pulse.branching

import com.google.gson.annotations.SerializedName

/**
 * Branching wire-shape types. Mirrors the server's
 * `server/engine/ee/pulse/branching/types.go` and the Web SDK's
 * `branching.ts` types. Keep this file in lockstep with both —
 * drift is exactly the bug the cross-language tests catch.
 */

/** Sentinel: when [PulseOutcome.nextQuestionId] equals this string,
 *  the survey should end immediately. Mirrors the Go + Web side
 *  `BRANCHING_END_OF_SURVEY` constant.
 */
const val PULSE_BRANCHING_END_OF_SURVEY: String = "__end__"

internal object PulseBranchingActionKind {
    const val SKIP_TO = "skip_to"
    const val END_SURVEY = "end_survey"
}

internal object PulseBranchingCondKind {
    const val ANSWER = "answer"
}

internal object PulseBranchingCondOp {
    const val EQUALS = "equals"
    const val NOT_EQUALS = "not_equals"
    const val GT = "gt"
    const val LT = "lt"
    const val GTE = "gte"
    const val LTE = "lte"
    const val CONTAINS = "contains"
    const val NOT_CONTAINS = "not_contains"
    const val IN = "in"
    const val NOT_IN = "not_in"
    const val ANSWERED = "answered"
    const val NOT_ANSWERED = "not_answered"
}

internal data class PulseBranchingCondition(
    @SerializedName("kind") val kind: String,
    @SerializedName("question_id") val questionId: String,
    @SerializedName("op") val op: String,
    @SerializedName("value") val value: Any? = null,
)

internal data class PulseBranchingRule(
    @SerializedName("from_question_id") val fromQuestionId: String,
    @SerializedName("condition") val condition: PulseBranchingCondition,
    @SerializedName("action") val action: String,
    @SerializedName("to_question_id") val toQuestionId: String? = null,
)

/**
 * Result of resolving the next question for a given current
 * question + answer state. `nextQuestionId` is:
 *   - `""` (empty) → fall through; the caller advances by
 *     order_index as usual.
 *   - `__end__` ([PULSE_BRANCHING_END_OF_SURVEY]) → end the survey
 *     immediately, even if there are more questions.
 *   - any other string → jump to the question with that id.
 */
data class PulseOutcome(
    val nextQuestionId: String,
    val reason: String? = null,
)
