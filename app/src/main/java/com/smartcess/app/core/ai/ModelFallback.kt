package com.smartcess.app.core.ai

/** Model fallback: primary Muse Spark, one fallback attempt on 5xx/timeout. */
data class ModelRoute(val primary: String, val fallback: String?)

object ModelFallback {
    fun route(primary: String, fallback: String?): ModelRoute = ModelRoute(primary, fallback)

    /** Returns the model to try for [attempt] (0 = primary, 1 = fallback), or null to stop. */
    fun modelForAttempt(route: ModelRoute, attempt: Int): String? = when (attempt) {
        0 -> route.primary
        1 -> route.fallback
        else -> null
    }
}
