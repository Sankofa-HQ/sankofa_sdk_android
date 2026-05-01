package dev.sankofa.sdk.pulse.targeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Targeting evaluator parity test suite. Mirrors
 * `sdks/sankofa_sdk_web/packages/pulse/src/__tests__/targeting.test.ts`
 * verbatim — failures here mean the Android SDK will disagree with
 * the Web SDK and Go server about whether a respondent is eligible
 * for a given survey, which is exactly the divergence the shared
 * DSL is designed to prevent.
 */
class PulseTargetingTest {

    private fun ctx(
        surveyId: String = "psv_x",
        respondentExternalId: String = "user_42",
        pageUrl: String? = "https://x.com/checkout",
        userProperties: Map<String, Any?>? = emptyMap(),
        cohorts: Map<String, Boolean>? = emptyMap(),
        flagValues: Map<String, Any?>? = emptyMap(),
        recentEvents: Map<String, Int>? = emptyMap(),
        priorResponseCount: Map<String, Int>? = emptyMap(),
    ) = PulseEligibilityContext(
        surveyId = surveyId,
        respondentExternalId = respondentExternalId,
        pageUrl = pageUrl,
        userProperties = userProperties,
        cohorts = cohorts,
        flagValues = flagValues,
        recentEvents = recentEvents,
        priorResponseCount = priorResponseCount,
    )

    @Test fun `empty rules eligible`() {
        val d = PulseTargeting.evaluate(emptyList(), ctx())
        assertTrue(d.eligible)
    }

    @Test fun `AND of rules - all must match`() {
        val rules = listOf(
            PulseTargetingRule(
                kind = PulseRuleKind.URL,
                urlMatch = PulseMatchOp.CONTAINS,
                urlValue = "/checkout",
            ),
            PulseTargetingRule(
                kind = PulseRuleKind.USER_PROPERTY,
                propertyKey = "plan",
                propertyOp = PulseMatchOp.EQUALS,
                propertyValue = "pro",
            ),
        )
        val proCtx = ctx(userProperties = mapOf("plan" to "pro"))
        assertTrue(PulseTargeting.evaluate(rules, proCtx).eligible)
        val freeCtx = ctx(userProperties = mapOf("plan" to "free"))
        assertEquals(false, PulseTargeting.evaluate(rules, freeCtx).eligible)
    }

    @Test fun `url match operations`() {
        data class Case(val match: String, val value: String, val url: String, val want: Boolean)
        val cases = listOf(
            Case(PulseMatchOp.EQUALS, "https://x.com/", "https://x.com/", true),
            Case(PulseMatchOp.EQUALS, "https://x.com/", "https://x.com/checkout", false),
            Case(PulseMatchOp.CONTAINS, "/checkout", "https://x.com/app/checkout/v2", true),
            Case(PulseMatchOp.CONTAINS, "/checkout", "https://x.com/app/cart", false),
            Case(PulseMatchOp.PREFIX, "https://x.com/", "https://x.com/checkout", true),
            Case(PulseMatchOp.PREFIX, "https://x.com/", "https://other.com/x", false),
            Case(PulseMatchOp.REGEX, "\\.com/(\\w+)/checkout", "https://x.com/app/checkout", true),
            Case(PulseMatchOp.REGEX, "\\.com/(\\w+)/checkout", "https://x.com/checkout", false),
        )
        for (c in cases) {
            val rule = PulseTargetingRule(kind = PulseRuleKind.URL, urlMatch = c.match, urlValue = c.value)
            val d = PulseTargeting.evaluate(listOf(rule), ctx(pageUrl = c.url))
            assertEquals(
                "url ${c.match} ${c.value} ${c.url}",
                c.want,
                d.eligible,
            )
        }
    }

    @Test fun `event respects min count`() {
        val cases = listOf(
            0 to false,
            1 to false,
            2 to false,
            3 to true,
            10 to true,
        )
        for ((count, want) in cases) {
            val rule = PulseTargetingRule(
                kind = PulseRuleKind.EVENT,
                eventName = "purchased",
                eventMinCount = 3,
            )
            val d = PulseTargeting.evaluate(
                listOf(rule),
                ctx(recentEvents = mapOf("purchased" to count)),
            )
            assertEquals("count=$count", want, d.eligible)
        }
    }

    @Test fun `event default min count is 1`() {
        val rule = PulseTargetingRule(kind = PulseRuleKind.EVENT, eventName = "signup")
        assertTrue(PulseTargeting.evaluate(
            listOf(rule), ctx(recentEvents = mapOf("signup" to 1))).eligible)
        assertEquals(false, PulseTargeting.evaluate(
            listOf(rule), ctx(recentEvents = mapOf("signup" to 0))).eligible)
    }

    @Test fun `user_property equals + numeric ops + in`() {
        fun equalsRule(v: Any?) = PulseTargetingRule(
            kind = PulseRuleKind.USER_PROPERTY,
            propertyKey = "k",
            propertyOp = PulseMatchOp.EQUALS,
            propertyValue = v,
        )
        assertTrue(PulseTargeting.evaluate(
            listOf(equalsRule("pro")), ctx(userProperties = mapOf("k" to "pro"))).eligible)
        assertEquals(false, PulseTargeting.evaluate(
            listOf(equalsRule("pro")), ctx(userProperties = mapOf("k" to "free"))).eligible)

        data class NumCase(val op: String, val target: Number, val actual: Any?, val want: Boolean)
        val numericCases = listOf(
            NumCase(PulseMatchOp.GT, 5, 10, true),
            NumCase(PulseMatchOp.GT, 5, 5, false),
            NumCase(PulseMatchOp.GTE, 5, 5, true),
            NumCase(PulseMatchOp.LT, 100, 99, true),
            NumCase(PulseMatchOp.LTE, 100, 100, true),
            NumCase(PulseMatchOp.GT, 5, "10", true),
            NumCase(PulseMatchOp.GT, 5, "abc", false),
        )
        for (c in numericCases) {
            val rule = PulseTargetingRule(
                kind = PulseRuleKind.USER_PROPERTY,
                propertyKey = "k",
                propertyOp = c.op,
                propertyValue = c.target,
            )
            val d = PulseTargeting.evaluate(
                listOf(rule), ctx(userProperties = mapOf("k" to c.actual)))
            assertEquals("op=${c.op} target=${c.target} actual=${c.actual}",
                c.want, d.eligible)
        }

        val inRule = PulseTargetingRule(
            kind = PulseRuleKind.USER_PROPERTY,
            propertyKey = "plan",
            propertyOp = PulseMatchOp.IN,
            propertyValue = listOf("pro", "enterprise"),
        )
        val inCases = listOf("pro" to true, "enterprise" to true, "free" to false, "trial" to false)
        for ((v, want) in inCases) {
            assertEquals("in: $v", want, PulseTargeting.evaluate(
                listOf(inRule), ctx(userProperties = mapOf("plan" to v))).eligible)
        }
    }

    @Test fun `user_property exists and not_exists`() {
        val exists = PulseTargetingRule(
            kind = PulseRuleKind.USER_PROPERTY,
            propertyKey = "k",
            propertyOp = PulseMatchOp.EXISTS,
        )
        val notExists = PulseTargetingRule(
            kind = PulseRuleKind.USER_PROPERTY,
            propertyKey = "k",
            propertyOp = PulseMatchOp.NOT_EXISTS,
        )
        val present = ctx(userProperties = mapOf("k" to "v"))
        val absent = ctx(userProperties = mapOf("other" to "v"))
        assertTrue(PulseTargeting.evaluate(listOf(exists), present).eligible)
        assertEquals(false, PulseTargeting.evaluate(listOf(exists), absent).eligible)
        assertEquals(false, PulseTargeting.evaluate(listOf(notExists), present).eligible)
        assertTrue(PulseTargeting.evaluate(listOf(notExists), absent).eligible)
    }

    @Test fun `sampling is deterministic for same user`() {
        val rule = PulseTargetingRule(kind = PulseRuleKind.SAMPLING, samplingRate = 0.5)
        val c = ctx()
        val first = PulseTargeting.evaluate(listOf(rule), c).eligible
        repeat(100) { i ->
            assertEquals("sampling drifted on iteration $i", first,
                PulseTargeting.evaluate(listOf(rule), c).eligible)
        }
    }

    @Test fun `sampling distributes near target rate within five percent`() {
        val rule = PulseTargetingRule(kind = PulseRuleKind.SAMPLING, samplingRate = 0.5)
        var admitted = 0
        val n = 5000
        for (i in 0 until n) {
            val c = ctx(respondentExternalId = "user_$i")
            if (PulseTargeting.evaluate(listOf(rule), c).eligible) admitted++
        }
        val rate = admitted.toDouble() / n
        assertTrue("rate=$rate drifted outside ±5%", rate in 0.45..0.55)
    }

    @Test fun `sampling rate 0 never admits`() {
        val rule = PulseTargetingRule(kind = PulseRuleKind.SAMPLING, samplingRate = 0.0)
        for (i in 0 until 100) {
            assertEquals(false, PulseTargeting.evaluate(
                listOf(rule), ctx(respondentExternalId = "u$i")).eligible)
        }
    }

    @Test fun `sampling rate 1 always admits`() {
        val rule = PulseTargetingRule(kind = PulseRuleKind.SAMPLING, samplingRate = 1.0)
        for (i in 0 until 100) {
            assertTrue(PulseTargeting.evaluate(
                listOf(rule), ctx(respondentExternalId = "u$i")).eligible)
        }
    }

    @Test fun `sampling - anonymous respondent fails closed`() {
        val rule = PulseTargetingRule(kind = PulseRuleKind.SAMPLING, samplingRate = 0.5)
        assertEquals(false, PulseTargeting.evaluate(
            listOf(rule), ctx(respondentExternalId = "")).eligible)
    }

    @Test fun `frequency cap enforces prior count`() {
        val rule = PulseTargetingRule(
            kind = PulseRuleKind.FREQUENCY_CAP,
            frequencyScope = "per_user",
            frequencyMax = 2,
            frequencyWindowDays = 30,
        )
        val cases = listOf(0 to true, 1 to true, 2 to false)
        for ((count, want) in cases) {
            val c = ctx(priorResponseCount = mapOf("psv_x" to count))
            assertEquals("prior=$count", want,
                PulseTargeting.evaluate(listOf(rule), c).eligible)
        }
    }

    @Test fun `feature_flag matches when value equal`() {
        val rule = PulseTargetingRule(
            kind = PulseRuleKind.FEATURE_FLAG,
            flagKey = "show_survey",
            flagValue = true,
        )
        assertTrue(PulseTargeting.evaluate(
            listOf(rule), ctx(flagValues = mapOf("show_survey" to true))).eligible)
        assertEquals(false, PulseTargeting.evaluate(
            listOf(rule), ctx(flagValues = mapOf("show_survey" to false))).eligible)
        assertEquals(false, PulseTargeting.evaluate(
            listOf(rule), ctx(flagValues = emptyMap())).eligible)
    }

    @Test fun `stableHash produces a value in 0 to 1`() {
        for (i in 0 until 100) {
            val score = PulseTargeting.stableHash("survey:$i")
            assertTrue("score=$score out of range", score in 0.0..1.0 && score < 1.0)
        }
    }

    @Test fun `stableHash is deterministic`() {
        val a = PulseTargeting.stableHash("psv_x:user_42")
        val b = PulseTargeting.stableHash("psv_x:user_42")
        assertEquals(a, b, 1e-12)
        val c = PulseTargeting.stableHash("psv_x:user_43")
        assertNotEquals(a, c, 1e-12)
    }
}
