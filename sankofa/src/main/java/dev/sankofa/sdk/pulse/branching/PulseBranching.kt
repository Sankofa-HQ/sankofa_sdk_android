package dev.sankofa.sdk.pulse.branching

/**
 * Branching evaluator — Kotlin port of
 * `sdks/sankofa_sdk_web/packages/pulse/src/branching.ts` and
 * `server/engine/ee/pulse/branching/evaluator.go`.
 *
 * Behavioural contract: every (rules, currentQuestionId, answers)
 * triple MUST produce the same Outcome the Web + Go evaluators
 * produce. Tests in
 * `dev/sankofa/sdk/pulse/branching/PulseBranchingTest.kt` mirror
 * the Web suite verbatim; both ports are themselves derived from
 * the Go test cases.
 *
 * Composition: first matching rule attached to currentQuestionId
 * wins. When no rule matches, returns nextQuestionId="" so the SDK
 * falls through to the natural next question (by order_index).
 * When the matching rule has action="end_survey", returns the
 * [PULSE_BRANCHING_END_OF_SURVEY] sentinel.
 */
internal object PulseBranching {

    fun resolveNext(
        rules: List<PulseBranchingRule>,
        currentQuestionId: String,
        answers: Map<String, Any?>,
    ): PulseOutcome {
        for ((i, rule) in rules.withIndex()) {
            if (rule.fromQuestionId != currentQuestionId) continue
            if (!evaluateCondition(rule.condition, answers)) continue
            return when (rule.action) {
                PulseBranchingActionKind.SKIP_TO -> PulseOutcome(
                    nextQuestionId = rule.toQuestionId.orEmpty(),
                    reason = "rule[$i] skip_to ${rule.toQuestionId}",
                )
                PulseBranchingActionKind.END_SURVEY -> PulseOutcome(
                    nextQuestionId = PULSE_BRANCHING_END_OF_SURVEY,
                    reason = "rule[$i] end_survey",
                )
                else -> continue
            }
        }
        return PulseOutcome(
            nextQuestionId = "",
            reason = "fall through (no rule matched)",
        )
    }

    fun evaluateCondition(
        cond: PulseBranchingCondition,
        answers: Map<String, Any?>,
    ): Boolean = when (cond.kind) {
        PulseBranchingCondKind.ANSWER -> evalAnswerCondition(cond, answers)
        else -> false
    }

    private fun evalAnswerCondition(
        cond: PulseBranchingCondition,
        answers: Map<String, Any?>,
    ): Boolean {
        val present = answers.containsKey(cond.questionId)
        val v = answers[cond.questionId]
        when (cond.op) {
            PulseBranchingCondOp.ANSWERED -> return present && !isEmptyAnswer(v)
            PulseBranchingCondOp.NOT_ANSWERED -> return !present || isEmptyAnswer(v)
        }
        if (!present || isEmptyAnswer(v)) return false
        return when (cond.op) {
            PulseBranchingCondOp.EQUALS -> jsonEqual(v, cond.value)
            PulseBranchingCondOp.NOT_EQUALS -> !jsonEqual(v, cond.value)
            PulseBranchingCondOp.CONTAINS -> jsonContains(v, cond.value)
            PulseBranchingCondOp.NOT_CONTAINS -> !jsonContains(v, cond.value)
            PulseBranchingCondOp.IN -> jsonInArray(v, cond.value)
            PulseBranchingCondOp.NOT_IN -> !jsonInArray(v, cond.value)
            PulseBranchingCondOp.GT,
            PulseBranchingCondOp.LT,
            PulseBranchingCondOp.GTE,
            PulseBranchingCondOp.LTE -> compareNumeric(v, cond.value, cond.op)
            else -> false
        }
    }

    private fun isEmptyAnswer(v: Any?): Boolean = when (v) {
        null -> true
        is String -> v.trim().isEmpty()
        is Collection<*> -> v.isEmpty()
        else -> false
    }

    private fun jsonEqual(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        if (a::class != b::class) return false
        return a == b
    }

    private fun jsonContains(v: Any?, target: Any?): Boolean {
        // Array containment (multi-select).
        if (v is Collection<*>) {
            return v.any { jsonEqual(it, target) }
        }
        // String containment.
        if (v is String && target is String) {
            return v.contains(target)
        }
        return false
    }

    private fun jsonInArray(v: Any?, target: Any?): Boolean {
        val list = target as? Collection<*> ?: return false
        return list.any { jsonEqual(v, it) }
    }

    private fun compareNumeric(v: Any?, target: Any?, op: String): Boolean {
        val left = numericValue(v) ?: return false
        val right = numericValue(target) ?: return false
        return when (op) {
            PulseBranchingCondOp.GT -> left > right
            PulseBranchingCondOp.LT -> left < right
            PulseBranchingCondOp.GTE -> left >= right
            PulseBranchingCondOp.LTE -> left <= right
            else -> false
        }
    }

    /**
     * Same numeric coercion shape as the Go side: numbers, strings
     * that parse as numbers, and booleans (true → 1, false → 0).
     * The boolean coercion is defensive — a boolean question wired
     * to a numeric op shouldn't crash the evaluator.
     */
    private fun numericValue(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().takeIf { it.isNotEmpty() }
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() }
        else -> null
    }
}
