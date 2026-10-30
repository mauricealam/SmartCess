package com.smartcess.app.core.ai

/** A prompt split per Meta's instruction-message guidance: standing rules go in a
 *  `developer` message (highest precedence), untrusted input data in the `user`
 *  message, so data can never override the instructions. */
data class SparkPrompt(val instruction: String, val input: String)

/** Versioned Spark prompts. Changelog: v1.0.0 = initial MVP prompts per spec. v1.1.0 = billing_period_start/end + percentage_change per spec schemas. v1.2.0 = developer/user role split per Meta instruction-message guidance. */
object SparkPrompts {
    const val VERSION = "v1.2.0"

    const val GLOBAL_RULES = """Respond with JSON only, matching the requested schema exactly.
Never invent provider names, amounts, or dates; use empty values and list them in missing_fields.
Always include confidence (0-1) and missing_fields.
Write summaries in plain spoken language for blind users.
Never give medical, legal, or financial advice."""

    const val NO_ADVICE_RULE = "Never give medical, legal, or financial advice. " +
        "For health questions, suggest asking a trusted person or professional."

    fun qa(question: String, context: String?): SparkPrompt = SparkPrompt(
        instruction = """Answer the user's question in at most two short sentences,
plain spoken language for a blind listener. No lists, no preamble.
$NO_ADVICE_RULE""",
        input = if (context.isNullOrBlank()) {
            """Question: "$question""""
        } else {
            """Conversation so far:
$context
Question: "$question""""
        },
    )

    fun intent(text: String, context: String? = null): SparkPrompt = SparkPrompt(
        instruction = """Classify the voice command into one intent:
daily_briefing, scan_bill, read_document, email_briefing, ask_bill_question,
create_reminder, cancel_reminder, archive_bill, add_appointment, add_routine, general_question.
$GLOBAL_RULES""",
        input = if (context.isNullOrBlank()) {
            """Command: "$text""""
        } else {
            """Conversation so far:
$context
Command: "$text""""
        },
    )

    fun bill(ocrHint: String?): SparkPrompt = SparkPrompt(
        instruction = """Extract the bill from this image as JSON: provider, amount (number),
currency (3-letter code), due_date (YYYY-MM-DD), account_reference,
billing_period_start (YYYY-MM-DD or null), billing_period_end (YYYY-MM-DD or null),
required_action (pay_before_due_date|review_bill|contact_provider|no_action_required),
confidence, missing_fields, summary (one spoken sentence).
$GLOBAL_RULES""",
        input = ocrHint?.let { "OCR hint: $it" } ?: "See attached bill photo.",
    )

    fun compare(current: String, previous: String): SparkPrompt = SparkPrompt(
        instruction = """Compare these two bills as JSON: amount_changed, difference, percentage_change,
possible_reasons, recommendation, confidence.
$GLOBAL_RULES""",
        input = """Current: $current
Previous: $previous""",
    )

    fun emailBriefing(emails: String): SparkPrompt = SparkPrompt(
        instruction = """Summarize these emails as JSON: emails[{id, urgency (low|normal|high|urgent),
summary, deadline}], briefing (short spoken overview naming urgent items first).
$GLOBAL_RULES""",
        input = "Emails: $emails",
    )

    fun document(text: String?): SparkPrompt = SparkPrompt(
        instruction = """Summarize this document as JSON: title, type, summary
(plain language), key_points, deadline (YYYY-MM-DD or null), amount, required_action, confidence.
$GLOBAL_RULES""",
        input = text?.let { "Text: $it" } ?: "See attached document photo.",
    )

    fun appointment(text: String): SparkPrompt = SparkPrompt(
        instruction = """Extract the appointment as JSON: title, provider, location, start_iso, end_iso,
doctor, prep_notes, confidence.
$GLOBAL_RULES""",
        input = """Text: "$text"""",
    )

    fun routine(text: String): SparkPrompt = SparkPrompt(
        instruction = """Extract the routine as JSON: title, kind (MEDICATION|DELIVERY|RECURRING|OTHER),
schedule_rule (e.g. DAILY 08:00), notes, confidence.
$NO_ADVICE_RULE $GLOBAL_RULES""",
        input = """Text: "$text"""",
    )

    fun briefing(todayData: String): SparkPrompt = SparkPrompt(
        instruction = """Build the daily briefing as JSON: greeting, priorities[{type, title, detail,
urgency, suggested_action, reference_id}], briefing (spoken, at most 3 urgent items first).
Order: overdue bills, bills due within 7 days, appointments today/tomorrow,
urgent email, document deadlines, routines due.
$GLOBAL_RULES""",
        input = "Today: $todayData",
    )
}
