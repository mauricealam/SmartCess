package com.smartcess.app.features.camera

import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.atan2

/**
 * CameraX analyzer for the real-time framing loop. Runs on-device ML Kit
 * text recognition on every [sampleEvery]-th frame (spec: every 2-3 frames),
 * unions the text-block bounding boxes, and emits a normalized [FramingInput].
 *
 * Fully offline: no frame or result leaves the device. Callbacks arrive on
 * the main thread; the analyzer never blocks the camera pipeline because
 * overlapping frames are dropped while one recognition is in flight.
 */
class TextFramingAnalyzer(
    private val onResult: (FramingInput) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val sampleEvery: Int = DEFAULT_SAMPLE_EVERY,
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val frameCount = AtomicInteger(0)
    private val inFlight = AtomicBoolean(false)

    /** Latest full OCR text, reused as a hint for the final Spark extraction. */
    @Volatile
    var lastRecognizedText: String = ""
        private set

    /** Mean luma of the latest analyzed frame (0..1); 1.0 when unknown. */
    @Volatile
    var lastLuminance: Float = 1f
        private set

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        val index = frameCount.incrementAndGet()
        if (index % sampleEvery != 0 || !inFlight.compareAndSet(false, true)) {
            image.close()
            return
        }
        val mediaImage = image.image
        if (mediaImage == null) {
            inFlight.set(false)
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val width = image.width
        val height = image.height
        val luminance = estimateLuminance(image)
        lastLuminance = luminance
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                lastRecognizedText = visionText.text
                onResult(toFramingInput(visionText, width, height, luminance))
            }
            .addOnFailureListener { e -> onError(e) }
            .addOnCompleteListener {
                inFlight.set(false)
                image.close()
            }
    }

    /** Release the ML Kit client; call when the camera unbinds. */
    fun close() {
        runCatching { recognizer.close() }
    }

    private fun toFramingInput(
        visionText: Text,
        imageWidth: Int,
        imageHeight: Int,
        luminance: Float,
    ): FramingInput {
        val boxes = visionText.textBlocks.mapNotNull { it.boundingBox }
        if (boxes.isEmpty()) {
            return FramingInput(hasText = false, meanLuminance = luminance)
        }
        val union = Rect(boxes[0])
        boxes.drop(1).forEach { union.union(it) }
        val frameArea = (imageWidth * imageHeight).toFloat().coerceAtLeast(1f)
        val coverage = (union.width() * union.height()) / frameArea
        val offsetX = (union.exactCenterX() / imageWidth) - 0.5f
        val offsetY = (union.exactCenterY() / imageHeight) - 0.5f
        val skew = largestBlockSkew(visionText)
        return FramingInput(
            hasText = true,
            coverage = coverage.coerceIn(0f, 1f),
            offsetX = offsetX,
            offsetY = offsetY,
            skewDegrees = skew,
            meanLuminance = luminance,
        )
    }

    /**
     * Absolute tilt of the largest block's top edge from its corner points
     * (ML Kit orders them TL, TR, BR, BL). 0 when points are unavailable.
     */
    private fun largestBlockSkew(visionText: Text): Float {
        val block = visionText.textBlocks.maxByOrNull {
            it.boundingBox?.let { box -> box.width() * box.height() } ?: 0
        } ?: return 0f
        val points = block.cornerPoints
        if (points == null || points.size < 2) return 0f
        val (tl, tr) = points[0] to points[1]
        val degrees = Math.toDegrees(atan2((tr.y - tl.y).toDouble(), (tr.x - tl.x).toDouble()))
        return abs(degrees).toFloat()
    }

    /**
     * Mean luma from a subsample of the Y plane. Sampling every 64th byte
     * keeps the framing loop under its <500ms/instruction budget.
     */
    private fun estimateLuminance(image: ImageProxy): Float {
        return runCatching {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            if (bytes.isEmpty()) return 1f
            var sum = 0L
            var count = 0L
            var i = 0
            while (i < bytes.size) {
                sum += bytes[i].toInt() and 0xFF
                count++
                i += LUMA_STRIDE
            }
            (sum.toFloat() / count / 255f).coerceIn(0f, 1f)
        }.getOrElse { 1f }
    }

    companion object {
        const val DEFAULT_SAMPLE_EVERY = 3
        private const val LUMA_STRIDE = 64
    }
}
