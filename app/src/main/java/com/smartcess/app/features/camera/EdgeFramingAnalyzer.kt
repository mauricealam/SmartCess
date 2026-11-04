package com.smartcess.app.features.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * CameraX analyzer for the real-time framing loop. Runs no-model edge
 * detection on every [sampleEvery]-th frame and emits a normalized
 * [FramingInput]. No ML, no network: the Y plane never leaves the device.
 *
 * Synchronous by design (edge detection is sub-millisecond on the grid),
 * so frames are analyzed inline and overlapping work cannot pile up.
 */
class EdgeFramingAnalyzer(
    private val onResult: (FramingInput) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val sampleEvery: Int = DEFAULT_SAMPLE_EVERY,
) : ImageAnalysis.Analyzer {

    private var frameCount = 0

    /** Mean luma of the latest analyzed frame (0..1); 1.0 when unknown. */
    @Volatile
    var lastLuminance: Float = 1f
        private set

    override fun analyze(image: ImageProxy) {
        try {
            frameCount++
            if (frameCount % sampleEvery != 0) return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val luminance = EdgeDetector.meanLuminance(bytes)
            lastLuminance = luminance
            val content = EdgeDetector.detectContent(
                bytes, image.width, image.height, plane.rowStride,
            )
            if (!content.present) {
                onResult(FramingInput(hasText = false, meanLuminance = luminance))
                return
            }
            // Buffer-space geometry, then into upright screen space. Coverage
            // and the minimum margin are rotation-invariant and pass through
            // untouched; per-side margins rotate with the frame.
            val rotation = image.imageInfo.rotationDegrees
            val (uprightX, uprightY) = FramingGeometry.uprightOffsets(
                content.centerX - 0.5f,
                content.centerY - 0.5f,
                rotation,
            )
            val sides = FramingGeometry.uprightSides(
                content.marginLeft,
                content.marginTop,
                content.marginRight,
                content.marginBottom,
                rotation,
            )
            onResult(
                FramingInput(
                    hasText = true,
                    coverage = content.coverage,
                    offsetX = uprightX,
                    offsetY = uprightY,
                    // No text corners without a text model; tilt reads level.
                    skewDegrees = 0f,
                    meanLuminance = luminance,
                    minEdgeMargin = content.margin,
                    marginLeft = sides.left,
                    marginTop = sides.top,
                    marginRight = sides.right,
                    marginBottom = sides.bottom,
                ),
            )
        } catch (e: Exception) {
            onError(e)
        } finally {
            image.close()
        }
    }

    /** No native client to release; kept for API parity with callers. */
    fun close() {
    }

    companion object {
        const val DEFAULT_SAMPLE_EVERY = 3
    }
}
