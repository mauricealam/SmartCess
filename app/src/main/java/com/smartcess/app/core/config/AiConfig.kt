package com.smartcess.app.core.config

import com.smartcess.app.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meta Model API configuration. Endpoint/auth details were unspecified in the spec,
 * so base URLs are overridable via local.properties / env (see app/build.gradle.kts).
 */
@Singleton
class AiConfig @Inject constructor() {
    val sparkBaseUrl: String = BuildConfig.META_API_BASE_URL
    val sparkModel: String = "muse-spark"
    val sparkFallbackModel: String = "muse-spark-fallback"
    val sttUrl: String = BuildConfig.META_STT_URL

    /** Per-call timeouts (spec budgets: capture->result <30s, doc <45s, briefing <10s). */
    val extractionTimeoutMillis: Long = 30_000
    val documentTimeoutMillis: Long = 45_000
    val briefingTimeoutMillis: Long = 10_000
    val intentTimeoutMillis: Long = 3_000

    /** Rate limits: max concurrent AI calls and calls per minute (client-side guard). */
    val maxConcurrentCalls: Int = 2
    val maxCallsPerMinute: Int = 20

    /** Retries: transient failures 2x backoff, Spark timeout 1x, rate-limit surfaces a message. */
    val transientMaxRetries: Int = 2
    val timeoutMaxRetries: Int = 1
}

/** Non-essential AI stays off by default; SAM/Image are post-MVP. */
data class AiFeatureFlags(
    val nonEssentialAiEnabled: Boolean = false,
    val samEnabled: Boolean = false,
    val imageGenEnabled: Boolean = false,
) {
    fun isImageAllowed(): Boolean = nonEssentialAiEnabled && imageGenEnabled
    fun isSamAllowed(): Boolean = nonEssentialAiEnabled && samEnabled
}
