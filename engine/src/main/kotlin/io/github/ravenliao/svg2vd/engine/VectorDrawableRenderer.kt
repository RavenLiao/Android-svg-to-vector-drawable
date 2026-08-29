package io.github.ravenliao.svg2vd.engine

import com.android.ide.common.vectordrawable.VdPreview
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

data class RenderOptions(val maxDimension: Int = 64)

data class RenderedPng(val bytes: ByteArray)

class VectorDrawableRenderException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface VectorDrawableRenderer {
    fun renderXml(xml: String, options: RenderOptions = RenderOptions()): RenderedPng
}

object VectorDrawableRenderers {
    fun upstream(): VectorDrawableRenderer = UpstreamVectorDrawableRenderer()
}

class UpstreamVectorDrawableRenderer internal constructor(
    private val preview: (String, Int) -> BufferedImage? = { xml, maxDimension ->
        VdPreview.getPreviewFromVectorXml(VdPreview.TargetSize.createFromMaxDimension(maxDimension), xml, null)
    },
    private val pngWriter: (BufferedImage, ByteArrayOutputStream) -> Boolean = { image, output ->
        ImageIO.write(image, "PNG", output)
    },
) : VectorDrawableRenderer {
    override fun renderXml(xml: String, options: RenderOptions): RenderedPng {
        if (options.maxDimension <= 0) {
            throw VectorDrawableRenderException("maxDimension must be positive.")
        }

        return try {
            val image = preview(xml, options.maxDimension)
                ?: throw VectorDrawableRenderException("VectorDrawable preview did not produce an image.")
            val output = ByteArrayOutputStream()
            if (!pngWriter(image, output)) {
                throw VectorDrawableRenderException("PNG encoder rejected the VectorDrawable preview.")
            }
            RenderedPng(output.toByteArray())
        } catch (error: VectorDrawableRenderException) {
            throw error
        } catch (error: Exception) {
            throw VectorDrawableRenderException("VectorDrawable preview rendering failed.", error)
        }
    }
}
