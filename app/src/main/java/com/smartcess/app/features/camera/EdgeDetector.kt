package com.smartcess.app.features.camera

/**
 * No-model content detection for the framing loop. Sobel edges on a
 * downscaled Y plane decide whether a subject (bill/document) is in frame
 * and where its mass sits. Pure JVM: no Android classes, no ML, no network.
 *
 * Framing bounds need no model at all, so every model SmartCess calls is a
 * Meta model.
 *
 * Pipeline: Sobel threshold → morphological close (radius [CLOSE_RADIUS]:
 * merges text lines into a page blob) → largest 8-connected component →
 * its bbox. The component step segments the page from background texture:
 * a bbox over ALL edges would span marble veins and shadows and pin
 * coverage near 1.0 at any distance. A component smaller than
 * [MIN_COMPONENT_CELLS] (background speckle, no page) reports absent.
 */
object EdgeDetector {

    /** Small-side resolution of the analysis grid. */
    const val TARGET_SMALL_SIDE = 96

    /** Sobel |gx|+|gy| above this counts as an edge pixel (0..2040 scale). */
    const val SOBEL_THRESHOLD = 128

    /** Minimum edge pixels before the frame counts as holding content. */
    const val MIN_EDGE_COUNT = 24

    /** Luma subsample stride, mirroring the old analyzer's budget. */
    const val LUMA_STRIDE = 64

    /** Morphological close radius in grid cells (merges text lines). */
    const val CLOSE_RADIUS = 2

    /** Smallest page-blob size in closed grid cells; below this: absent. */
    const val MIN_COMPONENT_CELLS = 60

    data class EdgeContent(
        /** True when enough edge mass exists to guide framing. */
        val present: Boolean,
        /** Mass center in buffer space, 0..1. */
        val centerX: Float = 0.5f,
        val centerY: Float = 0.5f,
        /** Mass bbox area as a fraction of frame area. */
        val coverage: Float = 0f,
        /** Min bbox-to-edge distance as a fraction of the small side. */
        val margin: Float = 1f,
        /**
         * Per-side bbox-to-edge distances in buffer space, same units as
         * [margin]. When the page overflows the frame the bbox centroid
         * collapses to center, so these carry the directional signal: the
         * page extends past the touched side.
         */
        val marginLeft: Float = 1f,
        val marginTop: Float = 1f,
        val marginRight: Float = 1f,
        val marginBottom: Float = 1f,
    )

    /**
     * Mean luma of Y-plane bytes (0..1); 1.0 when empty. Callers pass the
     * raw plane buffer; stride sampling keeps this cheap on every frame.
     */
    fun meanLuminance(y: ByteArray): Float {
        if (y.isEmpty()) return 1f
        var sum = 0L
        var count = 0L
        var i = 0
        while (i < y.size) {
            sum += y[i].toInt() and 0xFF
            count++
            i += LUMA_STRIDE
        }
        return (sum.toFloat() / count / 255f).coerceIn(0f, 1f)
    }

    /**
     * Finds edge mass in a Y plane. [rowStride] is the plane's row stride in
     * bytes (Y pixel stride is always 1). Never throws on short buffers:
     * missing rows read as empty.
     */
    fun detectContent(
        y: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
    ): EdgeContent {
        if (width <= 0 || height <= 0 || y.isEmpty()) return EdgeContent(false)
        val step = maxOf(1, minOf(width, height) / TARGET_SMALL_SIDE)
        val gw = width / step
        val gh = height / step
        if (gw < 3 || gh < 3) return EdgeContent(false)

        fun luma(gx: Int, gy: Int): Int {
            val idx = gy * step * rowStride + gx * step
            if (idx < 0 || idx >= y.size) return 0
            return y[idx].toInt() and 0xFF
        }

        // Sobel pass: mark edge cells on the grid.
        val grid = ByteArray(gw * gh)
        var count = 0
        for (gy in 1 until gh - 1) {
            for (gx in 1 until gw - 1) {
                val gxV = -luma(gx - 1, gy - 1) - 2 * luma(gx - 1, gy) - luma(gx - 1, gy + 1) +
                    luma(gx + 1, gy - 1) + 2 * luma(gx + 1, gy) + luma(gx + 1, gy + 1)
                val gyV = -luma(gx - 1, gy - 1) - 2 * luma(gx, gy - 1) - luma(gx + 1, gy - 1) +
                    luma(gx - 1, gy + 1) + 2 * luma(gx, gy + 1) + luma(gx + 1, gy + 1)
                val mag = kotlin.math.abs(gxV) + kotlin.math.abs(gyV)
                if (mag > SOBEL_THRESHOLD) {
                    count++
                    grid[gy * gw + gx] = 1
                }
            }
        }
        if (count < MIN_EDGE_COUNT) return EdgeContent(false)
        // Close: dilate then erode. Merges text lines into one page blob;
        // large features keep their extremes, speckle stays tiny.
        val r = CLOSE_RADIUS
        val dilated = ByteArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                if (grid[gy * gw + gx] == 0.toByte()) continue
                for (dy in -r..r) {
                    val ny = gy + dy
                    if (ny < 0 || ny >= gh) continue
                    for (dx in -r..r) {
                        val nx = gx + dx
                        if (nx < 0 || nx >= gw) continue
                        dilated[ny * gw + nx] = 1
                    }
                }
            }
        }
        // Erode with border replication: out-of-bounds counts as set, so a
        // page bleeding off the frame keeps its border-touch evidence.
        val closed = ByteArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var all = true
                for (dy in -r..r) {
                    val ny = gy + dy
                    if (ny < 0 || ny >= gh) continue
                    for (dx in -r..r) {
                        val nx = gx + dx
                        if (nx < 0 || nx >= gw) continue
                        if (dilated[ny * gw + nx] == 0.toByte()) {
                            all = false
                            break
                        }
                    }
                    if (!all) break
                }
                if (all) closed[gy * gw + gx] = 1
            }
        }
        // Largest 8-connected component of the closed grid (BFS).
        val seen = ByteArray(gw * gh)
        val queue = IntArray(gw * gh)
        var bestCount = 0
        var minX = 0
        var minY = 0
        var maxX = 0
        var maxY = 0
        for (sy in 0 until gh) {
            for (sx in 0 until gw) {
                val start = sy * gw + sx
                if (closed[start] == 0.toByte() || seen[start] != 0.toByte()) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                seen[start] = 1
                var compCount = 0
                var cMinX = sx
                var cMinY = sy
                var cMaxX = sx
                var cMaxY = sy
                while (head < tail) {
                    val cur = queue[head++]
                    compCount++
                    val cx = cur % gw
                    val cy = cur / gw
                    if (cx < cMinX) cMinX = cx
                    if (cy < cMinY) cMinY = cy
                    if (cx > cMaxX) cMaxX = cx
                    if (cy > cMaxY) cMaxY = cy
                    for (dy in -1..1) {
                        val ny = cy + dy
                        if (ny < 0 || ny >= gh) continue
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            if (nx < 0 || nx >= gw) continue
                            val ni = ny * gw + nx
                            if (closed[ni] != 0.toByte() && seen[ni] == 0.toByte()) {
                                seen[ni] = 1
                                queue[tail++] = ni
                            }
                        }
                    }
                }
                if (compCount > bestCount) {
                    bestCount = compCount
                    minX = cMinX
                    minY = cMinY
                    maxX = cMaxX
                    maxY = cMaxY
                }
            }
        }
        if (bestCount < MIN_COMPONENT_CELLS) return EdgeContent(false)
        // Back to buffer-space fractions (cell centers).
        val left = (minX + 0.5f) / gw
        val top = (minY + 0.5f) / gh
        val right = (maxX + 0.5f) / gw
        val bottom = (maxY + 0.5f) / gh
        val coverage = ((right - left) * (bottom - top)).coerceIn(0f, 1f)
        val smallSide = minOf(gw, gh).toFloat()
        val sideL = (minX + 0.5f).coerceAtLeast(0f) / smallSide
        val sideT = (minY + 0.5f).coerceAtLeast(0f) / smallSide
        val sideR = (gw - maxX - 0.5f).coerceAtLeast(0f) / smallSide
        val sideB = (gh - maxY - 0.5f).coerceAtLeast(0f) / smallSide
        return EdgeContent(
            present = true,
            centerX = (left + right) / 2f,
            centerY = (top + bottom) / 2f,
            coverage = coverage,
            margin = minOf(sideL, sideT, sideR, sideB),
            marginLeft = sideL,
            marginTop = sideT,
            marginRight = sideR,
            marginBottom = sideB,
        )
    }
}
