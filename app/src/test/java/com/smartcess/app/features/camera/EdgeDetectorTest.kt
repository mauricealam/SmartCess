package com.smartcess.app.features.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeDetectorTest {

    /** Y plane of [bg] with an optional bright rect (l, t, r, b) in pixels. */
    private fun frame(
        width: Int = 192,
        height: Int = 192,
        bg: Int = 20,
        rect: IntArray? = null,
        fg: Int = 230,
        extras: List<IntArray> = emptyList(),
    ): ByteArray {
        val y = ByteArray(width * height) { bg.toByte() }
        fun paint(r: IntArray) {
            for (row in r[1] until r[3]) {
                for (col in r[0] until r[2]) {
                    y[row * width + col] = fg.toByte()
                }
            }
        }
        if (rect != null) paint(rect)
        extras.forEach(::paint)
        return y
    }

    @Test
    fun blankFrame_hasNoContent() {
        val content = EdgeDetector.detectContent(frame(), 192, 192, 192)
        assertFalse(content.present)
    }

    @Test
    fun centeredBill_reportsCenterAndCoverage() {
        val content = EdgeDetector.detectContent(frame(rect = intArrayOf(48, 48, 144, 144)), 192, 192, 192)
        assertTrue(content.present)
        assertEquals(0.5f, content.centerX, 0.05f)
        assertEquals(0.5f, content.centerY, 0.05f)
        assertEquals(0.25f, content.coverage, 0.05f)
        assertEquals(0.25f, content.margin, 0.06f)
    }

    @Test
    fun leftBill_reportsLeftOffsetAndTightMargin() {
        val content = EdgeDetector.detectContent(frame(rect = intArrayOf(0, 48, 96, 144)), 192, 192, 192)
        assertTrue(content.present)
        assertTrue(content.centerX < 0.4f)
        assertEquals(0.5f, content.centerY, 0.05f)
        assertTrue(content.margin < 0.08f)
    }

    @Test
    fun tinySpeck_belowCountGate() {
        val content = EdgeDetector.detectContent(frame(rect = intArrayOf(94, 94, 98, 98)), 192, 192, 192)
        assertFalse(content.present)
    }

    @Test
    fun paddedRowStride_stillDetects() {
        val stride = 200
        val y = ByteArray(stride * 192) { 20 }
        for (row in 48 until 144) {
            for (col in 48 until 144) {
                y[row * stride + col] = 230.toByte()
            }
        }
        val content = EdgeDetector.detectContent(y, 192, 192, stride)
        assertTrue(content.present)
        assertEquals(0.5f, content.centerX, 0.05f)
    }

    @Test
    fun luminance_midGray_readsHalf() {
        assertEquals(0.5f, EdgeDetector.meanLuminance(ByteArray(1024) { 128.toByte() }), 0.02f)
    }

    @Test
    fun luminance_empty_readsUnknown() {
        assertEquals(1f, EdgeDetector.meanLuminance(ByteArray(0)), 0f)
    }

    @Test
    fun degenerateInputs_neverCrash() {
        assertFalse(EdgeDetector.detectContent(ByteArray(0), 192, 192, 192).present)
        assertFalse(EdgeDetector.detectContent(frame(), 0, 0, 0).present)
        assertFalse(EdgeDetector.detectContent(ByteArray(10), 192, 192, 192).present)
    }

    @Test
    fun centeredBill_reportsSymmetricSides() {
        val content = EdgeDetector.detectContent(frame(rect = intArrayOf(48, 48, 144, 144)), 192, 192, 192)
        assertTrue(content.present)
        assertTrue(content.marginLeft > 0.15f)
        assertTrue(content.marginRight > 0.15f)
        assertEquals(content.marginLeft, content.marginRight, 0.06f)
        assertEquals(content.marginTop, content.marginBottom, 0.06f)
        assertEquals(
            minOf(content.marginLeft, content.marginTop, content.marginRight, content.marginBottom),
            content.margin,
            1e-6f,
        )
    }

    @Test
    fun borderSpeckle_bboxFollowsPage() {
        // Specks at all four borders must not stretch the bbox: the page
        // blob wins by component size.
        val specks = listOf(
            intArrayOf(0, 0, 4, 4),
            intArrayOf(188, 0, 192, 4),
            intArrayOf(0, 188, 4, 192),
            intArrayOf(188, 188, 192, 192),
            intArrayOf(0, 94, 4, 98),
            intArrayOf(188, 94, 192, 98),
        )
        val content = EdgeDetector.detectContent(
            frame(rect = intArrayOf(48, 48, 144, 144), extras = specks), 192, 192, 192,
        )
        assertTrue(content.present)
        assertEquals(0.5f, content.centerX, 0.05f)
        assertEquals(0.5f, content.centerY, 0.05f)
        assertTrue(content.marginLeft > 0.15f)
        assertTrue(content.marginRight > 0.15f)
        assertTrue(content.marginTop > 0.15f)
        assertTrue(content.marginBottom > 0.15f)
    }

    @Test
    fun speckleOnly_reportsAbsent() {
        // Background speckle with no page: isolated specks stay tiny even
        // though their total count passes the raw presence gate.
        val specks = mutableListOf<IntArray>()
        for (row in listOf(20, 60, 100, 140)) {
            var x = 8
            while (x + 2 < 192) {
                specks.add(intArrayOf(x, row, x + 2, row + 2))
                x += 16
            }
        }
        val content = EdgeDetector.detectContent(
            frame(extras = specks), 192, 192, 192,
        )
        assertFalse(content.present)
    }

    @Test
    fun twoRects_largestWins() {
        val content = EdgeDetector.detectContent(
            frame(
                rect = intArrayOf(96, 96, 160, 160),
                extras = listOf(intArrayOf(8, 8, 40, 40)),
            ),
            192, 192, 192,
        )
        assertTrue(content.present)
        assertTrue(content.centerX > 0.5f)
        assertTrue(content.centerY > 0.5f)
    }

    @Test
    fun leftBleed_touchesLeftOnly_andEngineDirectsLeft() {
        // Page runs past the left frame edge: centroid stays near center
        // (no centroid signal) while the left side touches.
        val content = EdgeDetector.detectContent(frame(rect = intArrayOf(0, 48, 150, 144)), 192, 192, 192)
        assertTrue(content.present)
        assertTrue(content.marginLeft < 0.05f)
        assertTrue(content.marginRight > 0.12f)
        val offsetX = content.centerX - 0.5f
        assertTrue(kotlin.math.abs(offsetX) < FramingEngine.CENTER_TOLERANCE)
        // The engine must still say left from touch asymmetry alone.
        val condition = FramingEngine.evaluate(
            FramingInput(
                hasText = true,
                coverage = content.coverage,
                offsetX = offsetX,
                offsetY = content.centerY - 0.5f,
                minEdgeMargin = content.margin,
                marginLeft = content.marginLeft,
                marginTop = content.marginTop,
                marginRight = content.marginRight,
                marginBottom = content.marginBottom,
            ),
        )
        assertEquals(FramingCondition.EDGE_LEFT, condition)
    }
}
