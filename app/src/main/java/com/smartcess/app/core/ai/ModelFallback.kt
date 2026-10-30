package com.smartcess.app.core.ai

/** Model chain, strongest-first: TEMPORARY Wonder lead while Meta's Spark
 *  serving 404s; Spark versions stay as fallbacks so the chain heals
 *  itself when Spark recovers (then revert the lead to 1.3). */
data class ModelRoute(val models: List<String>)

object ModelFallback {
    fun route(vararg models: String?): ModelRoute = ModelRoute(models.filterNotNull())

    /** Returns the model to try for [attempt] (0 = primary, then fallbacks), or null to stop. */
    fun modelForAttempt(route: ModelRoute, attempt: Int): String? = route.models.getOrNull(attempt)
}
