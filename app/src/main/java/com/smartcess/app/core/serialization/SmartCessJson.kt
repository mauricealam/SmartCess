package com.smartcess.app.core.serialization

import kotlinx.serialization.json.Json

/** Shared kotlinx.serialization config: tolerant reads, no nulls on writes. */
object SmartCessJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }
}
