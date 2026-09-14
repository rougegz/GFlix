package com.gflix.app.utils

/**
 * Smart language matching for default audio/subtitle selection.
 * Matches a user preference like "Telugu" (or "te") against track labels
 * ("Telugu [CC]", "TELUGU"), ISO codes ("tel"/"te") and common variants.
 */
object TrackLanguage {

    private val CANONICAL: Map<String, String> = mapOf(
        // Telugu + Dravidian
        "telugu" to "tel", "telegu" to "tel", "tel" to "tel", "te" to "tel",
        "tamil" to "tam", "tam" to "tam", "ta" to "tam",
        "malayalam" to "mal", "mal" to "mal", "ml" to "mal",
        "kannada" to "kan", "kan" to "kan", "kn" to "kan",
        // Hindi belt
        "hindi" to "hin", "hin" to "hin", "hi" to "hin",
        "urdu" to "urd", "urd" to "urd", "ur" to "urd",
        "bengali" to "ben", "bangla" to "ben", "ben" to "ben", "bn" to "ben",
        "marathi" to "mar", "mar" to "mar", "mr" to "mar",
        "punjabi" to "pan", "panjabi" to "pan", "pan" to "pan", "pa" to "pan",
        "gujarati" to "guj", "guj" to "guj", "gu" to "guj",
        "odia" to "ori", "oriya" to "ori", "ori" to "ori", "or" to "ori",
        // European
        "english" to "eng", "eng" to "eng", "en" to "eng",
        "spanish" to "spa", "espanol" to "spa", "castilian" to "spa", "spa" to "spa", "es" to "spa",
        "french" to "fra", "fra" to "fra", "fre" to "fra", "fr" to "fra",
        "german" to "deu", "deutsch" to "deu", "deu" to "deu", "ger" to "deu", "de" to "deu",
        "italian" to "ita", "ita" to "ita", "it" to "ita",
        "portuguese" to "por", "por" to "por", "pt" to "por",
        "russian" to "rus", "rus" to "rus", "ru" to "rus",
        "arabic" to "ara", "ara" to "ara", "ar" to "ara",
        "turkish" to "tur", "tur" to "tur", "tr" to "tur",
        "dutch" to "nld", "nld" to "nld", "dut" to "nld", "nl" to "nld",
        "polish" to "pol", "pol" to "pol", "pl" to "pol",
        // East Asia
        "japanese" to "jpn", "jpn" to "jpn", "ja" to "jpn", "jp" to "jpn",
        "korean" to "kor", "kor" to "kor", "ko" to "kor",
        "chinese" to "zho", "mandarin" to "cmn", "cantonese" to "yue",
        "zho" to "zho", "chi" to "zho", "zh" to "zho", "cmn" to "cmn", "yue" to "yue",
        // Misc
        "thai" to "tha", "tha" to "tha", "th" to "tha",
        "vietnamese" to "vie", "vie" to "vie", "vi" to "vie",
        "indonesian" to "ind", "ind" to "ind", "id" to "ind",
        "malay" to "msa", "msa" to "msa", "may" to "msa", "ms" to "msa",
    )

    /** Normalize free text to a canonical ISO-639-ish token. */
    fun canonical(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val cleaned = raw.lowercase()
            .replace(Regex("[\\[\\](){}_.\\-+/]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return ""
        CANONICAL[cleaned]?.let { return it }
        // Multi-word label: try each token ("Telugu CC" -> tel).
        for (token in cleaned.split(" ")) {
            CANONICAL[token]?.let { return it }
        }
        // Unknown: return compacted token so distinct languages never collide.
        return cleaned.replace(" ", "")
    }

    /**
     * True when [preferred] (name like "Telugu" or code like "te") describes
     * the track given its display [label] and/or ISO [language].
     * Never bare-substring: "en" must not match "Bengali" (word boundaries).
     */
    fun matches(label: String?, language: String?, preferred: String): Boolean {
        val want = canonical(preferred)
        if (want.isEmpty()) return false
        if (canonical(language) == want) return true
        if (canonical(label) == want) return true
        // Word-boundary fallback for labels like "Telugu (Forced)".
        val flatLabel = (label ?: "").lowercase()
        val flatWant = preferred.lowercase().trim()
        if (flatWant.length >= 2 &&
            Regex("(?<![a-z])${Regex.escape(flatWant)}(?![a-z])").containsMatchIn(flatLabel)
        ) return true
        return false
    }
}
