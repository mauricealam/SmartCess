package com.smartcess.app.data.local

import androidx.room.TypeConverter
import com.smartcess.app.core.serialization.SmartCessJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Room converters for list columns, serialized as JSON.
 *
 * M8: reads fail OPEN to emptyList. Only a corrupt row (impossible through
 * this app's writers, which always emit valid JSON) can trigger the fallback,
 * and crashing the read would be worse than an empty list. Both paths pinned
 * by ConvertersTest.
 */
class Converters {

    private val json = SmartCessJson.instance

    @TypeConverter
    fun fromStringList(value: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), value) }
            .getOrDefault(emptyList())

    @TypeConverter
    fun fromIntList(value: List<Int>): String =
        json.encodeToString(ListSerializer(Int.serializer()), value)

    @TypeConverter
    fun toIntList(value: String): List<Int> =
        runCatching { json.decodeFromString(ListSerializer(Int.serializer()), value) }
            .getOrDefault(emptyList())
}
