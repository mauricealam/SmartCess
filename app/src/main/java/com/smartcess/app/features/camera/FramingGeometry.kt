package com.smartcess.app.features.camera

import kotlin.math.abs

/**
 * Buffer-to-upright geometry for the framing loop. Pure and unit-tested.
 *
 * Coordinate contract (verified against the CameraX 1.3.4 AAR in this
 * build, which provides output-image rotation):
 * - The analyzer enables output image rotation, so frames arrive upright
 *   with [rotationDegrees] == 0 at runtime and the transform below is the
 *   identity. The general path still applies [rotationDegrees] whenever a
 *   frame reports it, so the math stays correct on any device/config.
 * - The edge detector reports its mass box in the input buffer's
 *   coordinates; rotationDegrees is the clockwise rotation that makes the
 *   image upright. Offsets are center-relative, x right, y down.
 * - Coverage (area ratio) and the minimum edge margin (minimum over four
 *   sides) are rotation-invariant and need no transform. The offset vector,
 *   the per-side margins, and the baseline tilt are rotated here.
 * - Back camera only: no mirror handling. PreviewView center-crops
 *   (FILL_CENTER), which is symmetric, so near-center offsets align with
 *   the preview; the READY tolerance absorbs edge deviation.
 */
object FramingGeometry {

    /** Snaps to 0/90/180/270; anything else is treated as already upright. */
    fun normalizeRotation(rotationDegrees: Int): Int {
        val r = ((rotationDegrees % 360) + 360) % 360
        return if (r % 90 == 0) r else 0
    }

    /**
     * Rotates a center-relative offset (x right, y down) from buffer space
     * into upright screen space. Verified: buffer east point with R=90
     * lands south in the upright frame.
     */
    fun uprightOffsets(offsetX: Float, offsetY: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (normalizeRotation(rotationDegrees)) {
            90 -> Pair(-offsetY, offsetX)
            180 -> Pair(-offsetX, -offsetY)
            270 -> Pair(offsetY, -offsetX)
            else -> Pair(offsetX, offsetY)
        }

    /** Per-side margins in upright screen space, same order as passed in. */
    data class UprightSides(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    /**
     * Rotates per-side bbox margins from buffer space into upright screen
     * space. Consistent with [uprightOffsets]: buffer east lands south at
     * R=90, so the buffer right margin becomes the upright bottom margin.
     */
    fun uprightSides(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotationDegrees: Int,
    ): UprightSides =
        when (normalizeRotation(rotationDegrees)) {
            90 -> UprightSides(left = bottom, top = left, right = top, bottom = right)
            180 -> UprightSides(left = right, top = bottom, right = left, bottom = top)
            270 -> UprightSides(left = top, top = right, right = bottom, bottom = left)
            else -> UprightSides(left = left, top = top, right = right, bottom = bottom)
        }

    /**
     * Text-baseline tilt measured in buffer space, converted to upright
     * tilt magnitude. A level page reads [rotationDegrees] in the buffer
     * and 0 upright.
     */
    fun uprightSkew(skewDegrees: Float, rotationDegrees: Int): Float {
        val r = normalizeRotation(rotationDegrees)
        if (r == 0) return skewDegrees
        var d = (skewDegrees - r) % 180f
        if (d >= 90f) d -= 180f
        if (d < -90f) d += 180f
        return abs(d)
    }
}
