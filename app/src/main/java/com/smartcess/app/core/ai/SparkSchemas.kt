package com.smartcess.app.core.ai

import kotlinx.serialization.Serializable

// Structured-output schemas for Muse Spark (response_format JSON Schema counterparts).

@Serializable
data class IntentResult(
    val intent: String = "general_question",
    val confidence: Double = 0.0,
    val missing_information: List<String> = emptyList(),
    val clarifying_question: String? = null,
)

@Serializable
data class BillExtraction(
    val provider: String = "",
    val amount: Double = -1.0,
    val currency: String = "",
    val due_date: String = "",
    val account_reference: String? = null,
    val billing_period_start: String? = null,
    val billing_period_end: String? = null,
    val required_action: String = "review_bill",
    val confidence: Double = 0.0,
    val missing_fields: List<String> = emptyList(),
    val summary: String = "",
)

@Serializable
data class ComparisonResult(
    val amount_changed: Boolean = false,
    val difference: Double = 0.0,
    val percentage_change: Double = 0.0,
    val possible_reasons: List<String> = emptyList(),
    val recommendation: String = "",
    val confidence: Double = 0.0,
)

@Serializable
data class EmailBrief(
    val id: String = "",
    val urgency: String = "normal",
    val summary: String = "",
    val deadline: String? = null,
)

@Serializable
data class EmailBriefing(
    val emails: List<EmailBrief> = emptyList(),
    val briefing: String = "",
)

@Serializable
data class DocumentSummary(
    val title: String = "",
    val type: String = "",
    val summary: String = "",
    val key_points: List<String> = emptyList(),
    val deadline: String? = null,
    val amount: Double? = null,
    val required_action: String? = null,
    val confidence: Double = 0.0,
)

@Serializable
data class AppointmentExtraction(
    val title: String = "",
    val provider: String? = null,
    val location: String? = null,
    val start_iso: String = "",
    val end_iso: String? = null,
    val doctor: String? = null,
    val prep_notes: String? = null,
    val confidence: Double = 0.0,
)

@Serializable
data class RoutineHelp(
    val title: String = "",
    val kind: String = "OTHER",
    val schedule_rule: String = "",
    val notes: String? = null,
    val confidence: Double = 0.0,
)

@Serializable
data class BriefingPriority(
    val type: String = "",
    val title: String = "",
    val detail: String = "",
    val urgency: String = "normal",
    val suggested_action: String = "",
    val reference_id: String? = null,
)

@Serializable
data class BriefingResult(
    val greeting: String = "",
    val priorities: List<BriefingPriority> = emptyList(),
    val briefing: String = "",
)
