package com.smartcess.app.core.ai

/** Ordered model chain, strongest-first. The caller supplies the order
 *  (primary, fallback, last resort); this helper only walks it. */
data class ModelRoute(val models: List<String>)

object ModelFallback {
    fun route(vararg models: String?): ModelRoute = ModelRoute(models.filterNotNull())

    /** Returns the model to try for [attempt] (0 = primary, then fallbacks), or null to stop. */
    fun modelForAttempt(route: ModelRoute, attempt: Int): String? = route.models.getOrNull(attempt)
}
