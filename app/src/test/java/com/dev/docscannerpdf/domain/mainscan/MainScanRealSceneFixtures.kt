package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.detection.LumaFrame
import java.io.InputStream
import kotlin.math.hypot
import org.junit.Assert.assertEquals

/**
 * Loads the real device-derived detector inputs in `test/resources/mainscan/edge-fixtures`.
 *
 * These are the actual bounded luma frames the analyzer handed to the detector, so a test built on
 * them exercises the shipping pipeline end to end rather than a reconstruction of it.
 */
object MainScanRealSceneFixtures {

    const val WIDTH = 240
    const val HEIGHT = 180
    const val ROTATION_DEGREES = 90

    /** Annotated outer boundary of the package, normalized to the fixture. */
    val packageQuad = PerspectiveQuad(
        topLeft = CropPoint(0.310f, 0.413f),
        topRight = CropPoint(0.686f, 0.374f),
        bottomRight = CropPoint(0.669f, 0.749f),
        bottomLeft = CropPoint(0.326f, 0.777f)
    )

    const val TOLERANCE = 0.07f
    const val SEQUENCE_TOLERANCE = 0.09f

    val frameNames = listOf(
        "package_bright_screen_01.pgm",
        "package_bright_screen_02.pgm",
        "package_bright_screen_03.pgm"
    )

    /**
     * Reads a binary PGM (`P5`).
     *
     * PGM rather than PNG because Android unit tests compile against the Android SDK, which has no
     * `javax.imageio`. It is also the more honest container: the bytes ARE the luma plane the
     * detector consumes, with no colour model or codec sitting between the fixture and the test.
     */
    fun load(name: String): LumaFrame {
        val stream = requireNotNull(
            MainScanRealSceneFixtures::class.java.getResourceAsStream("/mainscan/edge-fixtures/$name")
        ) { "missing fixture $name" }

        return stream.use { input ->
            require(readToken(input) == "P5") { "$name is not a binary PGM" }
            val width = readToken(input).toInt()
            val height = readToken(input).toInt()
            require(readToken(input).toInt() == 255) { "$name is not 8-bit" }

            // Parity assertion, not decoration: a fixture of the wrong size would silently test a
            // different pipeline than the one that runs on the device.
            assertEquals("fixture width", WIDTH, width)
            assertEquals("fixture height", HEIGHT, height)

            val bytes = ByteArray(width * height)
            var read = 0
            while (read < bytes.size) {
                val count = input.read(bytes, read, bytes.size - read)
                require(count > 0) { "$name truncated at $read of ${bytes.size}" }
                read += count
            }
            LumaFrame(width, height, IntArray(bytes.size) { bytes[it].toInt() and 0xFF })
        }
    }

    /** Next whitespace-delimited ASCII token of a PGM header, skipping `#` comment lines. */
    private fun readToken(input: InputStream): String {
        val token = StringBuilder()
        var value = input.read()
        while (value != -1) {
            val character = value.toChar()
            when {
                character == '#' -> while (value != -1 && value.toChar() != '\n') value = input.read()
                character.isWhitespace() -> if (token.isNotEmpty()) return token.toString()
                else -> token.append(character)
            }
            value = input.read()
        }
        require(token.isNotEmpty()) { "unexpected end of PGM header" }
        return token.toString()
    }

    fun frames(): List<LumaFrame> = frameNames.map(::load)

    fun corners(quad: PerspectiveQuad): List<CropPoint> =
        listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)

    /** Mean distance between matching corners, in normalized units. */
    fun meanCornerError(actual: PerspectiveQuad, expected: PerspectiveQuad): Float {
        val a = corners(actual)
        val b = corners(expected)
        var total = 0f
        for (i in 0 until 4) total += hypot(a[i].x - b[i].x, a[i].y - b[i].y)
        return total / 4f
    }

    fun describe(quad: PerspectiveQuad?): String = when (quad) {
        null -> "null (no candidate)"
        else -> corners(quad).joinToString(prefix = "[", postfix = "]") {
            "(%.3f, %.3f)".format(it.x, it.y)
        }
    }
}
