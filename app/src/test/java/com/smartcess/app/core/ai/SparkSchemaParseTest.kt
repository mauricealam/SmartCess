package com.smartcess.app.core.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for Spark structured-output parsing (spec: schema-valid 100%, invalid JSON reject). */
class SparkSchemaParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun billExtraction_parsesFullPayload() {
        val parsed = json.decodeFromString<BillExtraction>(
            """{"provider":"Tenaga","amount":87.2,"currency":"MYR","due_date":"2026-11-20",
               |"required_action":"pay_before_due_date","confidence":0.93,
               |"missing_fields":[],"summary":"Electricity bill due Nov 20."}""".trimMargin(),
        )
        assertEquals("Tenaga", parsed.provider)
        assertEquals(87.2, parsed.amount, 0.001)
        assertEquals("2026-11-20", parsed.due_date)
        assertEquals(0.93, parsed.confidence, 0.001)
    }

    @Test
    fun billExtraction_missingFields_defaultSafely() {
        val parsed = json.decodeFromString<BillExtraction>("{}")
        assertEquals("", parsed.provider)
        assertEquals(-1.0, parsed.amount, 0.001)
        assertEquals("review_bill", parsed.required_action)
        assertEquals(0.0, parsed.confidence, 0.001)
    }

    @Test
    fun intentResult_parsesClarifyingQuestion() {
        val parsed = json.decodeFromString<IntentResult>(
            """{"intent":"scan_bill","confidence":0.88,"missing_information":[],
               |"clarifying_question":null}""".trimMargin(),
        )
        assertEquals("scan_bill", parsed.intent)
        assertEquals(0.88, parsed.confidence, 0.001)
    }

    @Test
    fun invalidJson_throwsForCallerToRetry() {
        var threw = false
        try {
            json.decodeFromString<BillExtraction>("{not json")
        } catch (_: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun billingPeriodBounds_parsed() {
        val parsed = json.decodeFromString<BillExtraction>(
            """{"provider":"T","amount":1.0,"due_date":"2026-11-01","confidence":0.9,
               |"billing_period_start":"2026-10-01","billing_period_end":"2026-10-31"}""".trimMargin(),
        )
        assertEquals("2026-10-01", parsed.billing_period_start)
        assertEquals("2026-10-31", parsed.billing_period_end)
    }

    @Test
    fun comparison_parsesPercentageChange() {
        val parsed = json.decodeFromString<ComparisonResult>(
            """{"amount_changed":true,"difference":5.0,"percentage_change":6.1,"confidence":0.8}""",
        )
        assertEquals(6.1, parsed.percentage_change, 0.001)
        assertTrue(parsed.amount_changed)
    }
}
