package dev.sankofa.sdk.pulse.branching

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Branching evaluator parity test suite. Mirrors
 * `sdks/sankofa_sdk_web/packages/pulse/src/__tests__/branching.test.ts`
 * verbatim — failures here mean the Android SDK will disagree with
 * the Web SDK and Go server about which question to show next, and
 * surveys with skip-logic will silently misbehave on Android.
 */
class PulseBranchingTest {

    @Test fun `empty rules - fall through`() {
        val out = PulseBranching.resolveNext(emptyList(), "psq_q1", emptyMap())
        assertEquals("", out.nextQuestionId)
    }

    @Test fun `no matching rule - fall through`() {
        val rules = listOf(
            PulseBranchingRule(
                fromQuestionId = "psq_q1",
                condition = PulseBranchingCondition(
                    kind = PulseBranchingCondKind.ANSWER,
                    questionId = "psq_q1",
                    op = PulseBranchingCondOp.EQUALS,
                    value = "never",
                ),
                action = PulseBranchingActionKind.SKIP_TO,
                toQuestionId = "psq_q5",
            ),
        )
        val out = PulseBranching.resolveNext(rules, "psq_q1", mapOf("psq_q1" to "always"))
        assertEquals("", out.nextQuestionId)
    }

    @Test fun `skip_to fires on match`() {
        val rules = listOf(
            PulseBranchingRule(
                fromQuestionId = "psq_nps",
                condition = PulseBranchingCondition(
                    kind = PulseBranchingCondKind.ANSWER,
                    questionId = "psq_nps",
                    op = PulseBranchingCondOp.LT,
                    value = 7,
                ),
                action = PulseBranchingActionKind.SKIP_TO,
                toQuestionId = "psq_why",
            ),
        )
        val out = PulseBranching.resolveNext(rules, "psq_nps", mapOf("psq_nps" to 3))
        assertEquals("psq_why", out.nextQuestionId)
    }

    @Test fun `end_survey fires on match`() {
        val rules = listOf(
            PulseBranchingRule(
                fromQuestionId = "psq_consent",
                condition = PulseBranchingCondition(
                    kind = PulseBranchingCondKind.ANSWER,
                    questionId = "psq_consent",
                    op = PulseBranchingCondOp.NOT_ANSWERED,
                ),
                action = PulseBranchingActionKind.END_SURVEY,
            ),
        )
        val out = PulseBranching.resolveNext(rules, "psq_consent", emptyMap())
        assertEquals(PULSE_BRANCHING_END_OF_SURVEY, out.nextQuestionId)
    }

    @Test fun `first matching rule wins`() {
        val rules = listOf(
            PulseBranchingRule(
                fromQuestionId = "psq_q1",
                condition = PulseBranchingCondition(
                    kind = PulseBranchingCondKind.ANSWER,
                    questionId = "psq_q1",
                    op = PulseBranchingCondOp.ANSWERED,
                ),
                action = PulseBranchingActionKind.SKIP_TO,
                toQuestionId = "psq_a",
            ),
            PulseBranchingRule(
                fromQuestionId = "psq_q1",
                condition = PulseBranchingCondition(
                    kind = PulseBranchingCondKind.ANSWER,
                    questionId = "psq_q1",
                    op = PulseBranchingCondOp.EQUALS,
                    value = "x",
                ),
                action = PulseBranchingActionKind.SKIP_TO,
                toQuestionId = "psq_b",
            ),
        )
        val out = PulseBranching.resolveNext(rules, "psq_q1", mapOf("psq_q1" to "x"))
        assertEquals("psq_a", out.nextQuestionId)
    }

    @Test fun `rules for other from-questions are ignored`() {
        val rules = listOf(
            PulseBranchingRule(
                fromQuestionId = "psq_q2",
                condition = PulseBranchingCondition(
                    kind = PulseBranchingCondKind.ANSWER,
                    questionId = "psq_q2",
                    op = PulseBranchingCondOp.ANSWERED,
                ),
                action = PulseBranchingActionKind.SKIP_TO,
                toQuestionId = "psq_z",
            ),
        )
        val out = PulseBranching.resolveNext(rules, "psq_q1", mapOf("psq_q2" to "x"))
        assertEquals("", out.nextQuestionId)
    }

    @Test fun `numeric comparators incl string-coerced`() {
        data class Case(val op: String, val target: Number, val answer: Any?, val want: Boolean)
        val cases = listOf(
            Case(PulseBranchingCondOp.LT, 7, 3, true),
            Case(PulseBranchingCondOp.LT, 7, 7, false),
            Case(PulseBranchingCondOp.LTE, 7, 7, true),
            Case(PulseBranchingCondOp.GT, 7, 8, true),
            Case(PulseBranchingCondOp.GTE, 7, 7, true),
            Case(PulseBranchingCondOp.GT, 7, "10", true),
            Case(PulseBranchingCondOp.GT, 7, "abc", false),
        )
        for (c in cases) {
            val ok = PulseBranching.evaluateCondition(
                PulseBranchingCondition(
                    kind = PulseBranchingCondKind.ANSWER,
                    questionId = "q",
                    op = c.op,
                    value = c.target,
                ),
                mapOf("q" to c.answer),
            )
            assertEquals("op=${c.op} target=${c.target} answer=${c.answer}", c.want, ok)
        }
    }

    @Test fun `contains works for arrays + strings`() {
        val arrCond = PulseBranchingCondition(
            kind = PulseBranchingCondKind.ANSWER,
            questionId = "q",
            op = PulseBranchingCondOp.CONTAINS,
            value = "key_b",
        )
        assertEquals(true, PulseBranching.evaluateCondition(
            arrCond, mapOf("q" to listOf("key_a", "key_b", "key_c"))))
        assertEquals(false, PulseBranching.evaluateCondition(
            arrCond, mapOf("q" to listOf("key_a", "key_c"))))

        val strCond = PulseBranchingCondition(
            kind = PulseBranchingCondKind.ANSWER,
            questionId = "q",
            op = PulseBranchingCondOp.CONTAINS,
            value = "slow",
        )
        assertEquals(true, PulseBranching.evaluateCondition(
            strCond, mapOf("q" to "the app feels slow")))
        assertEquals(false, PulseBranching.evaluateCondition(
            strCond, mapOf("q" to "the app feels fast")))
    }

    @Test fun `in matches array values`() {
        val cond = PulseBranchingCondition(
            kind = PulseBranchingCondKind.ANSWER,
            questionId = "q",
            op = PulseBranchingCondOp.IN,
            value = listOf("pro", "enterprise"),
        )
        assertEquals(true, PulseBranching.evaluateCondition(cond, mapOf("q" to "pro")))
        assertEquals(false, PulseBranching.evaluateCondition(cond, mapOf("q" to "free")))
    }

    @Test fun `answered + not_answered handle empty or missing`() {
        val answered = PulseBranchingCondition(
            kind = PulseBranchingCondKind.ANSWER,
            questionId = "q",
            op = PulseBranchingCondOp.ANSWERED,
        )
        val notAnswered = PulseBranchingCondition(
            kind = PulseBranchingCondKind.ANSWER,
            questionId = "q",
            op = PulseBranchingCondOp.NOT_ANSWERED,
        )
        assertEquals(true, PulseBranching.evaluateCondition(answered, mapOf("q" to "x")))
        assertEquals(false, PulseBranching.evaluateCondition(notAnswered, mapOf("q" to "x")))

        assertEquals(false, PulseBranching.evaluateCondition(answered, mapOf("q" to "")))
        assertEquals(true, PulseBranching.evaluateCondition(notAnswered, mapOf("q" to "")))

        assertEquals(false, PulseBranching.evaluateCondition(answered, mapOf("q" to emptyList<Any>())))

        assertEquals(false, PulseBranching.evaluateCondition(answered, emptyMap()))
        assertEquals(true, PulseBranching.evaluateCondition(notAnswered, emptyMap()))
    }

    @Test fun `value-needing ops fail closed when answer is missing`() {
        val cond = PulseBranchingCondition(
            kind = PulseBranchingCondKind.ANSWER,
            questionId = "q",
            op = PulseBranchingCondOp.EQUALS,
            value = "x",
        )
        assertEquals(false, PulseBranching.evaluateCondition(cond, emptyMap()))
    }

    @Test fun `boolean answers coerce to numeric`() {
        val cond = PulseBranchingCondition(
            kind = PulseBranchingCondKind.ANSWER,
            questionId = "q",
            op = PulseBranchingCondOp.GT,
            value = 0,
        )
        assertEquals(true, PulseBranching.evaluateCondition(cond, mapOf("q" to true)))
        assertEquals(false, PulseBranching.evaluateCondition(cond, mapOf("q" to false)))
    }
}
