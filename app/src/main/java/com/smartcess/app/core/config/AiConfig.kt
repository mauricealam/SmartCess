package com.smartcess.app.core.config

import com.smartcess.app.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meta Model API configuration. Base URLs default to the official endpoints
 * (dev.meta.ai/docs) and stay overridable via env / local.properties / .env
 * (see app/build.gradle.kts).
 */
@Singleton
class AiConfig @Inject constructor() {
    val sparkBaseUrl: String = BuildConfig.META_API_BASE_URL
    val sparkModel: String = "muse-spark-1.3"
    /** Fallback when Meta cannot serve the primary (same Muse Spark family). */
    val sparkFallbackModel: String? = "muse-spark-1.2"
    val sttUrl: String = BuildConfig.META_STT_URL
    val sttModel: String = "muse-voice-transcribe-1.0"
    /** SmartCess backend base URL (Render). All production AI traffic goes here. */
    val backendBaseUrl: String = BuildConfig.BACKEND_URL

    /** Per-call timeouts (spec budgets: capture->result <30s, doc <45s, briefing <10s). */
    val extractionTimeoutMillis: Long = 30_000
    val documentTimeoutMillis: Long = 45_000
    val briefingTimeoutMillis: Long = 10_000
    // Must exceed the backend Meta-retry span (~3.6s for 3 tries), or the
    // app would cut off retries during model flaps and fail needlessly.
    val intentTimeoutMillis: Long = 10_000

    /** Rate limits: max concurrent AI calls and calls per minute (client-side guard). */
    val maxConcurrentCalls: Int = 2
    val maxCallsPerMinute: Int = 20

    /** Retries: transient failures 2x backoff, Spark timeout 1x, rate-limit surfaces a message. */
    val transientMaxRetries: Int = 2
    val timeoutMaxRetries: Int = 1
}

