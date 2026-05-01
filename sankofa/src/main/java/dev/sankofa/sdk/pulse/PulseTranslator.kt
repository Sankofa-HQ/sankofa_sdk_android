package dev.sankofa.sdk.pulse

import java.util.Locale

/**
 * Translation helpers — wire-shape lookups + locale resolution.
 * Mirrors the Web SDK's `i18n.ts` so a survey rendered on Android
 * picks the same locale + same per-string fallback chain as the
 * same survey rendered in a browser.
 *
 * Lookup keys are dot-paths:
 *   - survey.name
 *   - survey.description
 *   - question.<question_id>.prompt
 *   - question.<question_id>.helptext
 *   - question.<question_id>.option.<option_key>.label
 *
 * Missing keys fall back to the source string on the survey /
 * question / option object — never throws, never blanks the UI.
 */
/**
 * BCP-47 language tags whose script renders right-to-left. Matches
 * the Unicode CLDR list of writing systems with `rtl` direction.
 * Used by SDKs to flip dialog layout when a survey's resolved locale
 * is RTL even though the host's system locale is LTR.
 */
private val PULSE_RTL_LANGUAGE_TAGS = setOf(
    "ar", "fa", "he", "iw", "ji", "ku", "ps", "sd", "ug", "ur", "yi",
)

internal fun pulseLocaleIsRTL(locale: String?): Boolean {
    if (locale.isNullOrEmpty()) return false
    val language = locale.substringBefore('-').lowercase()
    return language in PULSE_RTL_LANGUAGE_TAGS
}

internal class PulseTranslator(
    private val strings: Map<String, String>,
    /** BCP-47 tag this translator was built for; null for source. */
    val locale: String? = null,
) {

    fun surveyName(survey: PulseSurvey): String =
        strings["survey.name"] ?: survey.name

    fun surveyDescription(survey: PulseSurvey): String? =
        strings["survey.description"] ?: survey.description

    fun questionPrompt(question: PulseQuestion): String =
        strings["question.${question.id}.prompt"] ?: question.prompt

    fun questionHelptext(question: PulseQuestion): String? =
        strings["question.${question.id}.helptext"] ?: question.helptext

    fun optionLabel(question: PulseQuestion, option: PulseQuestionOption): String =
        strings["question.${question.id}.option.${option.key}.label"]
            ?: option.label

    companion object {
        /**
         * Resolve which locale to use given the host's preference,
         * the bundle's available locales, and the device default.
         *
         * Resolution order:
         *   1. Exact match against translations keys
         *   2. Language-only fallback (en-US → en)
         *   3. Device default — exact, then language-only
         *   4. null → render source strings unchanged
         */
        fun resolveLocale(
            translations: Map<String, Map<String, String>>?,
            preferred: String? = null,
        ): String? {
            if (translations.isNullOrEmpty()) return null
            val candidates = mutableListOf<String>()
            if (!preferred.isNullOrEmpty()) candidates += preferred
            val deviceLocale = Locale.getDefault().toLanguageTag()
            if (deviceLocale.isNotEmpty()) candidates += deviceLocale
            for (candidate in candidates) {
                if (translations.containsKey(candidate)) return candidate
                val language = candidate.substringBefore('-')
                if (language != candidate &&
                    translations.containsKey(language)) return language
            }
            return null
        }

        fun build(
            translations: Map<String, Map<String, String>>?,
            preferred: String? = null,
        ): PulseTranslator? {
            val locale = resolveLocale(translations, preferred) ?: return null
            val strings = translations?.get(locale) ?: return null
            return PulseTranslator(strings, locale = locale)
        }
    }
}
