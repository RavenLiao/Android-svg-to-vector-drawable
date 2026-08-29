package io.github.ravenliao.svg2vd.verification

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImageSimilarityComparatorTest {
    @Test
    fun `compares the shared area when dimensions differ by one pixel`() {
        val golden = image(2, 1, 0xff112233.toInt(), 0xff445566.toInt())
        val actual = image(3, 1, 0xff112233.toInt(), 0xff445566.toInt(), 0xff778899.toInt())

        val comparison = assertImageSimilar("one-pixel-dimension-difference", golden, actual)

        assertEquals(0.0, comparison.percentDifference)
        assertNull(comparison.deltaPng)
    }

    @Test
    fun `rejects dimensions that differ by two pixels`() {
        val golden = image(1, 1, 0xff000000.toInt())
        val actual = image(3, 1, 0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt())

        assertFailsWith<AssertionError> {
            assertImageSimilar("two-pixel-dimension-difference", golden, actual)
        }
    }

    @Test
    fun `ignores RGB differences when both pixels are transparent`() {
        val golden = image(1, 1, 0x00000000)
        val actual = image(1, 1, 0x00ffffff)

        val comparison = assertImageSimilar("transparent-rgb", golden, actual, maxPercentDifferent = 0.0)

        assertEquals(0.0, comparison.percentDifference)
        assertNull(comparison.deltaPng)
    }

    @Test
    fun `converts non-ARGB images before comparing pixels`() {
        val golden = BufferedImage(1, 1, BufferedImage.TYPE_3BYTE_BGR).also {
            it.setRGB(0, 0, 0xff112233.toInt())
        }
        val actual = BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR).also {
            it.setRGB(0, 0, 0xff112233.toInt())
        }

        val comparison = assertImageSimilar("convert-to-ARGB", golden, actual)

        assertEquals(0.0, comparison.percentDifference)
        assertNull(comparison.deltaPng)
    }

    @Test
    fun `normalizes one opaque channel difference across RGB channel range`() {
        val golden = image(1, 1, 0xff000000.toInt())
        val actual = image(1, 1, 0xff010000.toInt())

        val comparison = assertImageSimilar("one-channel-difference", golden, actual)

        assertEquals(100.0 / (3.0 * 256.0), comparison.percentDifference, 0.000000001)
        assertNull(comparison.deltaPng)
    }

    @Test
    fun `delta PNG uses a three-panel width and wraps positive RGB deltas`() {
        val golden = image(1, 1, 0x0a000000)
        val actual = image(1, 1, 0x1eff0000)

        val failure = assertFailsWith<ImageComparisonFailure> {
            assertImageSimilar("positive-delta", golden, actual, maxPercentDifferent = 0.0)
        }

        val png = requireNotNull(failure.comparison.deltaPng)
        assertContentEquals(PNG_SIGNATURE, png.copyOfRange(0, PNG_SIGNATURE.size))
        val delta = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(3, delta.width)
        assertEquals(1, delta.height)
        assertEquals(0x147f8080, delta.getRGB(1, 0))
    }

    @Test
    fun `delta PNG wraps negative RGB deltas and averages alpha with integer division`() {
        val golden = image(1, 1, 0x640000ff)
        val actual = image(1, 1, 0xc8000000.toInt())

        val failure = assertFailsWith<ImageComparisonFailure> {
            assertImageSimilar("negative-delta", golden, actual, maxPercentDifferent = 0.0)
        }

        val delta = ImageIO.read(ByteArrayInputStream(requireNotNull(failure.comparison.deltaPng)))
        assertEquals(0xc8000000.toInt(), delta.getRGB(2, 0))
        assertEquals(0x96808081.toInt(), delta.getRGB(1, 0))
    }

    private fun image(width: Int, height: Int, vararg pixels: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
            require(pixels.size == width * height)
            pixels.forEachIndexed { index, pixel -> image.setRGB(index % width, index / width, pixel) }
        }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
