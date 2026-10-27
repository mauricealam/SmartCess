package com.smartcess.app.core.ai

/**
 * Content safety: no medical/legal/financial advice, sensitive numbers masked
 * before speech, calm crisis response. Applied to every AI output shown or spoken.
 */
object ContentSafety {
    const val OFFLINE_LABEL = "Offline mode. Please review."
    const val LOW_CONFIDENCE_WARNING = "Low confidence. Please review carefully."

    const val MEDICAL_REFUSAL =
        "I can help you understand this document, but I cannot give medical, legal, " +
            "or financial advice. Here is a summary of the key information."

    const val CRISIS_RESPONSE =
        "I'm really glad you told me. Please reach out to someone you trust, " +
            "a counselor, or your local helpline right away. You don't have to face this alone."

    private val medicalPatterns = listOf(
        Regex("you (should|must|need to) (take|stop|start|increase|decrease).{0,40}(pill|tablet|dose|mg|medicine|medication)", RegexOption.IGNORE_CASE),
        Regex("(diagnos|prescri).{0,20}(you|your)", RegexOption.IGNORE_CASE),
    )
    private val legalPatterns = listOf(
        Regex("you should sue", RegexOption.IGNORE_CASE),
        Regex("take (them|him|her|your landlord|your employer) to court", RegexOption.IGNORE_CASE),
    )
    private val financialPatterns = listOf(
        Regex("you should (invest in|buy|sell).{0,40}(stock|share|fund|crypto|bond)", RegexOption.IGNORE_CASE),
        Regex("guaranteed return", RegexOption.IGNORE_CASE),
    )
    private val crisisPatterns = listOf(
        Regex("kill (myself|me)", RegexOption.IGNORE_CASE),
        Regex("suicid", RegexOption.IGNORE_CASE),
        Regex("end (my|it all|everything)", RegexOption.IGNORE_CASE),
        Regex("self.?harm|hurt(ing)? myself", RegexOption.IGNORE_CASE),
    )
    private val digitRun = Regex("\\d[\\d \\-]{3,}\\d")

    fun needsCrisisResponse(text: String): Boolean = crisisPatterns.any { it.containsMatchIn(text) }

    fun looksLikeMedicalAdvice(text: String): Boolean = medicalPatterns.any { it.containsMatchIn(text) }

    /** Spec §29: no medical, legal, or financial advice in any spoken output. */
    fun looksLikeAdvice(text: String): Boolean =
        medicalPatterns.any { it.containsMatchIn(text) } ||
            legalPatterns.any { it.containsMatchIn(text) } ||
            financialPatterns.any { it.containsMatchIn(text) }

    /** Masks digit runs of 5+, keeping the last 4 digits visible (never spoken in full). */
    fun maskSensitiveNumbers(text: String): String = digitRun.replace(text) { match ->
        val digits = match.value.filter { it.isDigit() }
        if (digits.length < 5) {
            match.value
        } else {
            val masked = "•".repeat(digits.length - 4) + digits.takeLast(4)
            var i = 0
            match.value.map { ch ->
                if (ch.isDigit()) masked[i++] else ch
            }.joinToString("")
        }
    }

    /** Final pass before display/speech: crisis -> support, advice -> refusal, else masked. */
    fun sanitizeForSpeech(text: String): String = when {
        needsCrisisResponse(text) -> CRISIS_RESPONSE
        looksLikeAdvice(text) -> MEDICAL_REFUSAL
        else -> maskSensitiveNumbers(text)
    }
}
