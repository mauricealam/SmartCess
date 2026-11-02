package com.smartcess.app.features.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic rotation cases: buffer offsets must land upright at 0/90/180/270. */
class FramingGeometryTest {

    private fun assertPair(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 1e-6f)
        assertEquals(expected.second, actual.second, 1e-6f)
    }

    @Test
    fun offsets_identity_atZero() {
        assertPair(Pair(0.25f, -0.1f), FramingGeometry.uprightOffsets(0.25f, -0.1f, 0))
    }

    @Test
    fun offsets_rotate90_bufferEast_landsSouth() {
        // Portrait phone: buffer point right of center appears below
        // center once the frame is upright.
        assertPair(Pair(-0f, 0.25f), FramingGeometry.uprightOffsets(0.25f, 0f, 90))
        assertPair(Pair(0.1f, 0.25f), FramingGeometry.uprightOffsets(0.25f, -0.1f, 90))
    }

    @Test
    fun offsets_rotate180() {
        assertPair(Pair(-0.25f, 0.1f), FramingGeometry.uprightOffsets(0.25f, -0.1f, 180))
    }

    @Test
    fun offsets_rotate270_bufferEast_landsNorth() {
        assertPair(Pair(-0f, -0.25f), FramingGeometry.uprightOffsets(0.25f, 0f, 270))
        assertPair(Pair(-0.1f, -0.25f), FramingGeometry.uprightOffsets(0.25f, -0.1f, 270))
    }

    @Test
    fun offsets_rotation_normalizes() {
        assertPair(Pair(0.25f, -0.1f), FramingGeometry.uprightOffsets(0.25f, -0.1f, 360))
        assertPair(Pair(-0.1f, -0.25f), FramingGeometry.uprightOffsets(0.25f, -0.1f, -90))
        // Non-right-angles are treated as already upright, never guessed.
        assertPair(Pair(0.25f, -0.1f), FramingGeometry.uprightOffsets(0.25f, -0.1f, 45))
    }

    @Test
    fun skew_levelPage_readsZeroUpright() {
        // A level page reads `rotation` in the buffer and 0 upright.
        assertEquals(0f, FramingGeometry.uprightSkew(0f, 0), 1e-6f)
        assertEquals(0f, FramingGeometry.uprightSkew(90f, 90), 1e-6f)
        assertEquals(0f, FramingGeometry.uprightSkew(180f, 180), 1e-6f)
        assertEquals(0f, FramingGeometry.uprightSkew(90f, 270), 1e-6f)
    }

    @Test
    fun skew_tiltMagnitude_survivesRotation() {
        assertEquals(5f, FramingGeometry.uprightSkew(5f, 0), 1e-6f)
        assertEquals(5f, FramingGeometry.uprightSkew(95f, 90), 1e-6f)
        assertEquals(5f, FramingGeometry.uprightSkew(85f, 90), 1e-6f)
        assertEquals(10f, FramingGeometry.uprightSkew(170f, 180), 1e-6f)
    }
}
