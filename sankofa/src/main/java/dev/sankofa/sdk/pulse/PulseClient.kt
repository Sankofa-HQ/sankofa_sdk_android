package dev.sankofa.sdk.pulse

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.sankofa.sdk.pulse.branching.PulseBranchingRule
import dev.sankofa.sdk.pulse.targeting.PulseTargetingRule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Pulse REST client. Six endpoints:
 *
 *   - GET    /api/pulse/handshake          → lightweight list of surveys
 *   - GET    /api/pulse/surveys/:survey_id → full bundle
 *   - POST   /api/pulse/responses          → final submit
 *   - POST   /api/pulse/partial            → save in-progress state
 *   - GET    /api/pulse/partial            → load in-progress state
 *   - DELETE /api/pulse/partial            → clear in-progress state
 *
 * Authenticated via `x-api-key`. Synchronous on a worker thread —
 * Pulse traffic is low volume (a few completions per session) and
 * we already serialise through the queue actor.
 */
internal class PulseClient(
    private val endpoint: String,
    private val apiKey: String,
    private val httpClient: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson(),
) {

    class HttpException(
        val status: Int, val body: String?,
    ) : RuntimeException("HTTP $status${body?.let { ": $it" } ?: ""}")

    fun handshake(): PulseHandshakeResponse {
        val url = "${endpoint.trimEnd('/')}/api/pulse/handshake?installed=pulse"
        val req = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(req).execute().use { res ->
            val body = res.body?.string()
            if (!res.isSuccessful) throw HttpException(res.code, body)
            return gson.fromJson(body, PulseHandshakeResponse::class.java)
                ?: PulseHandshakeResponse(emptyList())
        }
    }

    /**
     * Load the full survey bundle (questions + targeting +
     * branching + theme + translations + partial state) for one
     * survey. The SDK calls this right before presenting so it can
     * run the targeting evaluator locally and skip the show if the
     * respondent isn't eligible.
     */
    fun loadSurveyBundle(surveyId: String): PulseSurveyBundle {
        val url = "${endpoint.trimEnd('/')}/api/pulse/surveys/$surveyId"
        val req = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(req).execute().use { res ->
            val body = res.body?.string()
            if (!res.isSuccessful) throw HttpException(res.code, body)
            return parseBundle(body ?: "{}")
        }
    }

    private fun parseBundle(json: String): PulseSurveyBundle {
        val raw = gson.fromJson(json, Map::class.java) as? Map<*, *>
            ?: return PulseSurveyBundle()
        val surveyJson = gson.toJson(raw["survey"] ?: emptyMap<String, Any?>())
        val questionsJson = gson.toJson(raw["questions"] ?: emptyList<Any>())
        val targetingJson = gson.toJson(raw["targeting_rules"] ?: emptyList<Any>())
        val branchingJson = gson.toJson(raw["branching_rules"] ?: emptyList<Any>())
        val survey = gson.fromJson(surveyJson, PulseSurvey::class.java)
            ?: return PulseSurveyBundle()
        val questionsType = TypeToken.getParameterized(
            List::class.java, PulseQuestion::class.java).type
        val questions: List<PulseQuestion> = gson.fromJson(questionsJson, questionsType)
            ?: emptyList()
        val targetingType = TypeToken.getParameterized(
            List::class.java, PulseTargetingRule::class.java).type
        val targetingRules: List<PulseTargetingRule> =
            gson.fromJson(targetingJson, targetingType) ?: emptyList()
        val branchingType = TypeToken.getParameterized(
            List::class.java, PulseBranchingRule::class.java).type
        val branchingRules: List<PulseBranchingRule> =
            gson.fromJson(branchingJson, branchingType) ?: emptyList()
        // Translations: server ships locale → strings map. We
        // tolerate the field being absent or malformed since most
        // surveys ship without translations and a parse failure
        // shouldn't keep the dialog from opening.
        val translations: Map<String, Map<String, String>> = run {
            val rawTranslations = raw["translations"]
            if (rawTranslations !is Map<*, *>) return@run emptyMap()
            val out = LinkedHashMap<String, Map<String, String>>()
            for ((rawLocale, rawStrings) in rawTranslations) {
                val locale = rawLocale?.toString() ?: continue
                if (rawStrings !is Map<*, *>) continue
                val stringsMap = LinkedHashMap<String, String>()
                for ((k, v) in rawStrings) {
                    if (k == null || v == null) continue
                    stringsMap[k.toString()] = v.toString()
                }
                if (stringsMap.isNotEmpty()) out[locale] = stringsMap
            }
            out
        }
        // The bundle survey row doesn't carry questions on the Go
        // wire — we attach them here so callers see one self-contained
        // PulseSurvey.
        val merged = survey.copy(questions = questions)
        return PulseSurveyBundle(
            survey = merged,
            targetingRules = targetingRules,
            branchingRules = branchingRules,
            translations = translations,
        )
    }

    fun submit(payload: PulseSubmitPayload): PulseSubmitResponse {
        val url = "${endpoint.trimEnd('/')}/api/pulse/responses"
        val json = gson.toJson(payload)
        val req = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
        httpClient.newCall(req).execute().use { res ->
            val body = res.body?.string()
            if (!res.isSuccessful) throw HttpException(res.code, body)
            return gson.fromJson(body, PulseSubmitResponse::class.java)
                ?: PulseSubmitResponse()
        }
    }

    /**
     * Upsert the in-progress partial for (survey_id, external_id).
     * Server returns 422 for non-published surveys and 400 if
     * external_id is missing — partials are device-scoped and
     * meaningless without it.
     */
    fun savePartial(payload: PulsePartialUpsert): PulsePartialAck {
        val url = "${endpoint.trimEnd('/')}/api/pulse/partial"
        val json = gson.toJson(payload)
        val req = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
        httpClient.newCall(req).execute().use { res ->
            val body = res.body?.string()
            if (!res.isSuccessful) throw HttpException(res.code, body)
            return gson.fromJson(body, PulsePartialAck::class.java)
                ?: PulsePartialAck()
        }
    }

    /**
     * Load the partial for (survey_id, external_id). Returns null
     * on 404 (no partial / expired) — distinguishes a clean miss
     * from a network failure, which still throws.
     */
    fun loadPartial(surveyId: String, externalId: String): PulsePartial? {
        val url = "${endpoint.trimEnd('/')}/api/pulse/partial" +
            "?survey_id=${java.net.URLEncoder.encode(surveyId, "UTF-8")}" +
            "&external_id=${java.net.URLEncoder.encode(externalId, "UTF-8")}"
        val req = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(req).execute().use { res ->
            if (res.code == 404) return null
            val body = res.body?.string()
            if (!res.isSuccessful) throw HttpException(res.code, body)
            return gson.fromJson(body, PulsePartial::class.java)
        }
    }

    /**
     * Idempotent clear of the partial for (survey_id, external_id).
     * The server also auto-cleans on successful submit, so the SDK
     * only calls this on explicit dismiss / "start over".
     */
    fun deletePartial(surveyId: String, externalId: String) {
        val url = "${endpoint.trimEnd('/')}/api/pulse/partial" +
            "?survey_id=${java.net.URLEncoder.encode(surveyId, "UTF-8")}" +
            "&external_id=${java.net.URLEncoder.encode(externalId, "UTF-8")}"
        val req = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json")
            .delete()
            .build()
        httpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful && res.code != 404) {
                throw HttpException(res.code, res.body?.string())
            }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
