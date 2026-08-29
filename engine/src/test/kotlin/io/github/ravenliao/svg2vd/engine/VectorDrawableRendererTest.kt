package io.github.ravenliao.svg2vd.engine

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VectorDrawableRendererTest {
    @Test
    fun `public factory renders a VectorDrawable as PNG`() {
        val rendered = VectorDrawableRenderers.upstream().renderXml("""
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
                <path android:fillColor="#FF000000" android:pathData="M0,0h24v24h-24z"/>
            </vector>
        """.trimIndent())

        assertContentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), rendered.bytes.copyOfRange(0, 8))
    }

    @Test
    fun `renderXml encodes preview as PNG`() {
        val renderer = UpstreamVectorDrawableRenderer(preview = { _, _ -> BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) })

        val rendered = renderer.renderXml("<vector/>")

        assertContentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), rendered.bytes.copyOfRange(0, 8))
    }

    @Test
    fun `renderXml rejects a non-positive maximum dimension`() {
        val renderer = UpstreamVectorDrawableRenderer(preview = { _, _ -> BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) })

        assertFailsWith<VectorDrawableRenderException> {
            renderer.renderXml("<vector/>", RenderOptions(maxDimension = 0))
        }
    }

    @Test
    fun `renderXml wraps a null upstream preview`() {
        val renderer = UpstreamVectorDrawableRenderer(preview = { _, _ -> null })

        assertFailsWith<VectorDrawableRenderException> { renderer.renderXml("<vector/>") }
    }

    @Test
    fun `renderXml wraps upstream preview exceptions`() {
        val renderer = UpstreamVectorDrawableRenderer(preview = { _, _ -> error("preview failed") })

        val error = assertFailsWith<VectorDrawableRenderException> { renderer.renderXml("<vector/>") }

        assertTrue(error.cause is IllegalStateException)
    }

    @Test
    fun `renderXml fails when PNG encoding is rejected`() {
        val renderer = UpstreamVectorDrawableRenderer(
            preview = { _, _ -> BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB) },
            pngWriter = { _: BufferedImage, _: ByteArrayOutputStream -> false },
        )

        assertFailsWith<VectorDrawableRenderException> { renderer.renderXml("<vector/>") }
    }
}
