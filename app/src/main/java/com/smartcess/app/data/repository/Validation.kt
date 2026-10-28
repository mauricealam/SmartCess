package com.smartcess.app.data.repository

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Confidence below this forces mandatory user review + warning (spec). */
const val LOW_CONFIDENCE_THRESHOLD = 0.70

enum class IssueSeverity { WARN, ERROR }

data class ValidationIssue(val field: String, val message: String, val severity: IssueSeverity)

data class ValidationResult(val issues: List<ValidationIssue>) {
    val hasErrors: Boolean get() = issues.any { it.severity == IssueSeverity.ERROR }
    val needsReview: Boolean get() = hasErrors || issues.isNotEmpty()
}

val REQUIRED_ACTIONS = setOf("pay_before_due_date", "review_bill", "contact_provider", "no_action_required")
val EMAIL_URGENCIES = setOf("low", "normal", "high", "urgent")
val ROUTINE_KINDS = setOf("MEDICATION", "DELIVERY", "RECURRING", "OTHER")

/** Spec validation: provider non-empty, amount > 0, recognizable currency, valid ISO due date. */
fun validateBill(
    provider: String,
    amount: Double,
    currency: String,
    dueDate: String,
    requiredAction: String,
    confidence: Double,
    today: LocalDate = LocalDate.now(),
): ValidationResult {
    val issues = mutableListOf<ValidationIssue>()
    if (provider.isBlank()) {
        issues += ValidationIssue("provider", "Provider is missing.", IssueSeverity.ERROR)
    }
    if (amount.isNaN() || amount <= 0) {
        issues += ValidationIssue("amount", "Amount must be greater than zero.", IssueSeverity.ERROR)
    }
    if (!currency.matches(Regex("^[A-Za-z]{3}$"))) {
        issues += ValidationIssue("currency", "Currency is not recognizable.", IssueSeverity.ERROR)
    }
    try {
        val due = LocalDate.parse(dueDate)
        if (due.isBefore(today)) {
            issues += ValidationIssue("dueDate", "Due date is in the past.", IssueSeverity.WARN)
        }
    } catch (_: DateTimeParseException) {
        issues += ValidationIssue("dueDate", "Due date is not a valid date.", IssueSeverity.ERROR)
    }
    if (requiredAction !in REQUIRED_ACTIONS) {
        issues += ValidationIssue("requiredAction", "Required action is unknown.", IssueSeverity.ERROR)
    }
    if (confidence < LOW_CONFIDENCE_THRESHOLD) {
        issues += ValidationIssue("confidence", "Low confidence. Please review carefully.", IssueSeverity.WARN)
    }
    return ValidationResult(issues)
}

fun normalizeRequiredAction(value: String?): String =
    value?.takeIf { it in REQUIRED_ACTIONS } ?: "review_bill"

/** Spec §14: unknown routine types fall back to custom (OTHER). */
fun normalizeRoutineKind(value: String?): String =
    value?.uppercase()?.takeIf { it in ROUTINE_KINDS } ?: "OTHER"

/** Spec §14 failure response: blank document titles become "Untitled document". */
fun normalizeDocumentTitle(title: String?): String =
    title?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled document"

/** Spec §14 document rules: non-empty summary, valid deadline if present, 0.70 review gate. */
fun validateDocument(
    summary: String,
    deadline: String?,
    confidence: Double,
): ValidationResult {
    val issues = mutableListOf<ValidationIssue>()
    if (summary.isBlank()) {
        issues += ValidationIssue("summary", "Summary is missing.", IssueSeverity.ERROR)
    }
    if (!deadline.isNullOrBlank() && !isValidIsoDate(deadline)) {
        issues += ValidationIssue("deadline", "Deadline is not a valid date.", IssueSeverity.WARN)
    }
    if (confidence < LOW_CONFIDENCE_THRESHOLD) {
        issues += ValidationIssue("confidence", "Low confidence. Please review carefully.", IssueSeverity.WARN)
    }
    return ValidationResult(issues)
}

/** Spec §14 appointment rules: title, valid start, sane end, 0.70 review gate. */
fun validateAppointment(
    title: String,
    startMillis: Long,
    endMillis: Long?,
    confidence: Double,
    nowMillis: Long = System.currentTimeMillis(),
): ValidationResult {
    val issues = mutableListOf<ValidationIssue>()
    if (title.isBlank()) {
        issues += ValidationIssue("title", "Title is missing.", IssueSeverity.ERROR)
    }
    if (startMillis <= 0) {
        issues += ValidationIssue("start", "Start time is missing.", IssueSeverity.ERROR)
    }
    if (endMillis != null && endMillis <= startMillis) {
        issues += ValidationIssue("end", "End time must be after start time.", IssueSeverity.ERROR)
    }
    if (startMillis > 0 && startMillis < nowMillis) {
        issues += ValidationIssue("start", "This appointment is in the past.", IssueSeverity.WARN)
    }
    if (confidence < LOW_CONFIDENCE_THRESHOLD) {
        issues += ValidationIssue("confidence", "Low confidence. Please review carefully.", IssueSeverity.WARN)
    }
    return ValidationResult(issues)
}

/** Spec §14 routine rules: non-empty title and schedule. */
fun validateRoutine(title: String, scheduleRule: String): ValidationResult {
    val issues = mutableListOf<ValidationIssue>()
    if (title.isBlank()) {
        issues += ValidationIssue("title", "Title is missing.", IssueSeverity.ERROR)
    }
    if (scheduleRule.isBlank()) {
        issues += ValidationIssue("scheduleRule", "Schedule is missing.", IssueSeverity.ERROR)
    }
    return ValidationResult(issues)
}

fun isValidIsoDate(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    return try {
        LocalDate.parse(value); true
    } catch (_: DateTimeParseException) {
        false
    }
}
