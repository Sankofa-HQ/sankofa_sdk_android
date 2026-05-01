package dev.sankofa.sdk.pulse

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Cross-SDK contract test. Reads the canonical golden submit body
 * from `sdks/_contract_tests/goldens/pulse_submit_basic.json` and
 * asserts that the Android SDK serialises the same fixture inputs
 * into a structurally identical JSON payload.
 *
 * If this test fails, the Android wire shape has drifted away from
 * the server contract that Web + RN already speak. Fix the SDK, not
 * the golden — the golden mirrors what the server's `ingestPayload`
 * struct accepts in `server/engine/ee/pulse/handlers_ingest.go`.
 *
 * No Robolectric needed — this exercises pure-data serialisation,
 * not Android framework code.
 */
class PulseContractTest {

    private val gson = Gson()

    @Test
    fun pulseSubmitBasic_matchesGolden() {
        val golden = readGolden("pulse_submit_basic.json")
        val payload = PulseSubmitPayload(
            surveyId = "psv_test_001",
            respondent = PulseRespondent(
                userId = "usr_42",
                externalId = "ext_42",
                email = "alice@example.com",
            ),
            context = PulseContext(
                sessionId = "sess_abc",
                anonymousId = "anon_xyz",
                platform = "contract-test",
                osVersion = "test-os",
                appVersion = "1.0.0",
                locale = "en-US",
            ),
            answers = linkedMapOf(
                "q1" to "hello",
                "q2" to 9,
                "q3" to listOf("red", "green"),
            ),
        )
        val produced = gson.toJson(payload)
        assertStructurallyEqual(golden, parseJson(produced))
    }

    @Test
    fun pulseSubmitAnonymous_matchesGolden() {
        // Fully anonymous submission: no userId, no externalId, no
        // email. This catches regressions where the SDK fabricates
        // an empty string for missing identity fields rather than
        // omitting the keys entirely.
        val golden = readGolden("pulse_submit_anonymous.json")
        val payload = PulseSubmitPayload(
            surveyId = "psv_anon_001",
            respondent = PulseRespondent(),
            context = PulseContext(
                sessionId = null,
                anonymousId = null,
                platform = "contract-test",
                osVersion = null,
                appVersion = null,
                locale = null,
            ),
            answers = linkedMapOf("q1" to "anonymous"),
        )
        val produced = gson.toJson(payload)
        assertStructurallyEqual(golden, parseJson(produced))
    }

    @Test
    fun pulseSubmitAllAnswerKinds_matchesGolden() {
        // Every supported answer value type encoded into a single
        // payload — string, number, array, bool, nested map. Catches
        // encoder regressions that only affect a specific kind.
        val golden = readGolden("pulse_submit_all_answer_kinds.json")
        val payload = PulseSubmitPayload(
            surveyId = "psv_kinds_001",
            respondent = PulseRespondent(externalId = "ext_42"),
            context = PulseContext(
                sessionId = null,
                anonymousId = null,
                platform = "contract-test",
                osVersion = null,
                appVersion = null,
                locale = null,
                replaySessionId = "rep_abc",
            ),
            answers = linkedMapOf(
                "short_text" to "hello",
                "long_text" to "the app feels slow when I open the cart screen",
                "number" to 42,
                "rating" to 4,
                "nps" to 9,
                "single" to "key_pro",
                "multi" to listOf("key_a", "key_c"),
                "boolean" to true,
                "slider" to 75,
                "date" to "2026-05-01",
                "ranking" to listOf("key_b", "key_a", "key_c"),
                "matrix" to linkedMapOf(
                    "row_a" to "col_x",
                    "row_b" to "col_y",
                ),
                "consent" to true,
                "image_choice" to "key_blue",
                "maxdiff" to linkedMapOf(
                    "best" to "key_a",
                    "worst" to "key_c",
                ),
                "signature" to "data:image/png;base64,iVBORw0KGgo=",
            ),
        )
        val produced = gson.toJson(payload)
        assertStructurallyEqual(golden, parseJson(produced))
    }

    // ── helpers ───────────────────────────────────────────────────

    private fun readGolden(name: String): Map<String, Any?> {
        val file = resolveGolden(name)
        assertNotNull("golden file $name not found", file)
        @Suppress("UNCHECKED_CAST")
        return gson.fromJson(file!!.readText(), Map::class.java)
            as Map<String, Any?>
    }

    /**
     * Walks up from the gradle test cwd until it finds the
     * `sdks/_contract_tests/goldens` directory. Falls back to the
     * `SANKOFA_CONTRACT_GOLDENS` env var for CI runs that exec
     * outside the workspace.
     */
    private fun resolveGolden(name: String): File? {
        val override = System.getenv("SANKOFA_CONTRACT_GOLDENS")
        if (override != null && override.isNotBlank()) {
            return File(override).resolve(name).takeIf { it.exists() }
        }
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val candidate = dir?.resolve("sdks/_contract_tests/goldens/$name")
            if (candidate != null && candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJson(json: String): Map<String, Any?> =
        gson.fromJson(json, Map::class.java) as Map<String, Any?>

    /**
     * Structural equality: same keys, same values, same nesting.
     * Numbers that round-trip through Gson land as doubles; we
     * normalise both sides to Double before comparing so `9` and
     * `9.0` aren't a false positive.
     */
    private fun assertStructurallyEqual(expected: Any?, actual: Any?, path: String = "$") {
        when (expected) {
            is Map<*, *> -> {
                if (actual !is Map<*, *>) {
                    throw AssertionError("$path: expected map, got ${actual?.javaClass?.simpleName}")
                }
                assertEquals(
                    "$path: key set mismatch",
                    expected.keys.map { it.toString() }.toSortedSet(),
                    actual.keys.map { it.toString() }.toSortedSet(),
                )
                for ((k, v) in expected) {
                    assertStructurallyEqual(v, actual[k.toString()], "$path.$k")
                }
            }
            is List<*> -> {
                if (actual !is List<*>) {
                    throw AssertionError("$path: expected list, got ${actual?.javaClass?.simpleName}")
                }
                assertEquals("$path: list length", expected.size, actual.size)
                for (i in expected.indices) {
                    assertStructurallyEqual(expected[i], actual[i], "$path[$i]")
                }
            }
            is Number -> {
                val a = (actual as? Number)?.toDouble()
                    ?: throw AssertionError("$path: expected number, got ${actual?.javaClass?.simpleName}")
                assertEquals("$path", expected.toDouble(), a, 1e-9)
            }
            else -> assertEquals(path, expected, actual)
        }
    }
}
