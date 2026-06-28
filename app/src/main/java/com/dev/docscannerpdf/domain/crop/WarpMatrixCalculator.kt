package com.dev.docscannerpdf.domain.crop

/** A row-major 3x3 matrix (the layout android.graphics.Matrix.setValues expects). */
data class Matrix3x3(val values: DoubleArray) {
    init {
        require(values.size == 9) { "A 3x3 matrix needs 9 values" }
    }

    /** Applies the homography to a point, performing the perspective divide. */
    fun mapPoint(x: Double, y: Double): Pair<Double, Double> {
        val px = values[0] * x + values[1] * y + values[2]
        val py = values[3] * x + values[4] * y + values[5]
        val pw = values[6] * x + values[7] * y + values[8]
        return if (pw == 0.0) px to py else (px / pw) to (py / pw)
    }

    fun toFloatArray(): FloatArray = FloatArray(9) { values[it].toFloat() }

    // Value-based equality over the backing array (data class default compares by reference).
    override fun equals(other: Any?): Boolean =
        this === other || (other is Matrix3x3 && values.contentEquals(other.values))

    override fun hashCode(): Int = values.contentHashCode()
}

/**
 * Computes the perspective (homography) matrix mapping one quad onto another. Pure, exact,
 * and deterministic: it solves the 8x8 linear system from the four corner correspondences via
 * Gaussian elimination with partial pivoting, so identical inputs always yield identical output.
 */
object WarpMatrixCalculator {

    /**
     * Returns the 3x3 homography H such that, for each corner, H * src = dst (after the
     * perspective divide). Corners are paired in order TL, TR, BR, BL.
     */
    fun computeHomography(src: List<CropPoint>, dst: List<CropPoint>): Matrix3x3 {
        require(src.size == 4 && dst.size == 4) { "Homography requires 4 point correspondences" }

        // Solve A * h = b for h = [h0..h7]; h8 is fixed to 1.
        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)
        for (i in 0 until 4) {
            val sx = src[i].x.toDouble()
            val sy = src[i].y.toDouble()
            val dx = dst[i].x.toDouble()
            val dy = dst[i].y.toDouble()

            val row1 = i * 2
            a[row1] = doubleArrayOf(sx, sy, 1.0, 0.0, 0.0, 0.0, -dx * sx, -dx * sy)
            b[row1] = dx

            val row2 = i * 2 + 1
            a[row2] = doubleArrayOf(0.0, 0.0, 0.0, sx, sy, 1.0, -dy * sx, -dy * sy)
            b[row2] = dy
        }

        val h = solveLinearSystem(a, b)
        return Matrix3x3(
            doubleArrayOf(
                h[0], h[1], h[2],
                h[3], h[4], h[5],
                h[6], h[7], 1.0
            )
        )
    }

    /** Gaussian elimination with partial pivoting. Deterministic for a given input. */
    private fun solveLinearSystem(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        val n = rhs.size
        val a = Array(n) { matrix[it].copyOf() }
        val b = rhs.copyOf()

        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (kotlin.math.abs(a[row][col]) > kotlin.math.abs(a[pivot][col])) pivot = row
            }
            if (pivot != col) {
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                val tb = b[col]; b[col] = b[pivot]; b[pivot] = tb
            }

            val diagonal = a[col][col]
            if (kotlin.math.abs(diagonal) < 1e-12) continue // singular; leave as-is

            for (row in 0 until n) {
                if (row == col) continue
                val factor = a[row][col] / diagonal
                if (factor == 0.0) continue
                for (k in col until n) {
                    a[row][k] -= factor * a[col][k]
                }
                b[row] -= factor * b[col]
            }
        }

        return DoubleArray(n) { i ->
            val diagonal = a[i][i]
            if (kotlin.math.abs(diagonal) < 1e-12) 0.0 else b[i] / diagonal
        }
    }
}
