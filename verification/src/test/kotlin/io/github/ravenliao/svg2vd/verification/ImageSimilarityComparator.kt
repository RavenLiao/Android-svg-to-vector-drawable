package io.github.ravenliao.svg2vd.verification

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs

data class ImageComparison(val percentDifference: Double, val deltaPng: ByteArray?)

class ImageComparisonFailure(val comparison: ImageComparison) : AssertionError(
    "Images differ by ${comparison.percentDifference}%",
)

fun assertImageSimilar(
    name: String,
    golden: BufferedImage,
    actual: BufferedImage,
    maxPercentDifferent: Double = 1.25,
): ImageComparison {
    if (abs(golden.width - actual.width) >= 2) {
        throw AssertionError("Widths differ too much for $name")
    }
    if (abs(golden.height - actual.height) >= 2) {
        throw AssertionError("Widths differ too much for $name")
    }

    val expected = golden.toArgb()
    val observed = actual.toArgb()
    val imageWidth = minOf(expected.width, observed.width)
    val imageHeight = minOf(expected.height, observed.height)
    val deltaImage = BufferedImage(3 * imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)

    var delta = 0L
    for (y in 0 until imageHeight) {
        for (x in 0 until imageWidth) {
            val goldenRgb = expected.getRGB(x, y)
            val actualRgb = observed.getRGB(x, y)
            if (goldenRgb == actualRgb || (goldenRgb and ALPHA_MASK == 0 && actualRgb and ALPHA_MASK == 0)) {
                deltaImage.setRGB(imageWidth + x, y, NEUTRAL_DELTA)
                continue
            }

            val deltaR = red(actualRgb) - red(goldenRgb)
            val deltaG = green(actualRgb) - green(goldenRgb)
            val deltaB = blue(actualRgb) - blue(goldenRgb)
            val averageAlpha = ((alpha(goldenRgb) + alpha(actualRgb)) / 2) shl 24
            val deltaRgb = averageAlpha or
                (((128 + deltaR) and 0xff) shl 16) or
                (((128 + deltaG) and 0xff) shl 8) or
                ((128 + deltaB) and 0xff)
            deltaImage.setRGB(imageWidth + x, y, deltaRgb)
            delta += abs(deltaR).toLong() + abs(deltaG).toLong() + abs(deltaB).toLong()
        }
    }

    val percentDifference = delta * 100.0 / (imageWidth * imageHeight * 3L * 256L)
    if (percentDifference <= maxPercentDifferent) {
        return ImageComparison(percentDifference, null)
    }

    val graphics = deltaImage.createGraphics()
    try {
        graphics.drawImage(expected, 0, 0, null)
        graphics.drawImage(observed, 2 * imageWidth, 0, null)
        if (imageWidth > 80) {
            graphics.color = Color.RED
            graphics.drawString("Expected", 10, 20)
            graphics.drawString("Actual", 2 * imageWidth + 10, 20)
        }
    } finally {
        graphics.dispose()
    }
    val deltaPng = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(deltaImage, "PNG", output)) { "No PNG writer is available" }
        output.toByteArray()
    }
    throw ImageComparisonFailure(ImageComparison(percentDifference, deltaPng))
}

private const val ALPHA_MASK = -0x1000000
private const val NEUTRAL_DELTA = 0x00808080

private fun BufferedImage.toArgb(): BufferedImage {
    if (type == BufferedImage.TYPE_INT_ARGB) return this
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { converted ->
        val graphics = converted.createGraphics()
        try {
            graphics.drawImage(this, 0, 0, null)
        } finally {
            graphics.dispose()
        }
    }
}

private fun alpha(rgb: Int): Int = rgb ushr 24

private fun red(rgb: Int): Int = (rgb ushr 16) and 0xff

private fun green(rgb: Int): Int = (rgb ushr 8) and 0xff

private fun blue(rgb: Int): Int = rgb and 0xff
