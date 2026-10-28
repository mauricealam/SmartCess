package com.smartcess.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M8: converter round-trip + fail-open behavior on corrupt rows. */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun stringList_roundTrips() {
        val value = listOf("a", "b,c", "d\"e")
        assertEquals(value, converters.toStringList(converters.fromStringList(value)))
    }

    @Test
    fun intList_roundTrips() {
        val value = listOf(30, 60, 90)
        assertEquals(value, converters.toIntList(converters.fromIntList(value)))
    }

    @Test
    fun corruptRow_failsOpenToEmpty() {
        assertTrue(converters.toStringList("not-json{{").isEmpty())
        assertTrue(converters.toIntList("[1,").isEmpty())
        assertTrue(converters.toStringList("null").isEmpty())
    }

    @Test
    fun wrongShape_failsOpenToEmpty() {
        assertTrue(converters.toIntList("[\"a\"]").isEmpty())
    }
}
