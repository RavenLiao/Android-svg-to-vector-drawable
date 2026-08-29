package io.github.ravenliao.svg2vd.render

import io.github.ravenliao.svg2vd.contract.FileStatus
import io.github.ravenliao.svg2vd.contract.Outcome
import io.github.ravenliao.svg2vd.engine.EngineDiagnostic
import io.github.ravenliao.svg2vd.engine.EngineDiagnosticSeverity
import io.github.ravenliao.svg2vd.engine.RenderedPng
import io.github.ravenliao.svg2vd.engine.VectorDrawableRenderer
import io.github.ravenliao.svg2vd.engine.WriteResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class RenderCommandTest {
    @Test
    fun `writer failure preserves an existing target and is a file failure`() {
        val root = Files.createTempDirectory("render-command")
        val input = root.resolve("icon.xml").also { it.writeText("<vector/>") }
        val output = root.resolve("icon.png").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val command = RenderCommand(
            renderer = renderer(),
            writer = { _, _, _ -> WriteResult(false, listOf(error("filesystem_error"))) },
        )

        val result = command.execute(RenderRequest(input, output, overwrite = true))

        assertEquals(Outcome.PARTIAL_FAILURE, result.outcome)
        assertEquals(FileStatus.FAILED, result.results.single().status)
        assertEquals("filesystem_error", result.results.single().diagnostics.single().code)
        assertTrue(Files.readAllBytes(output).contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `input replaced after planning is not rendered or written`() {
        val root = Files.createTempDirectory("render-command")
        val input = root.resolve("icon.xml").also { it.writeText("<vector/>") }
        val replacement = Files.createTempDirectory("render-command-external").resolve("replacement.xml").also { it.writeText("<vector/>") }
        val output = root.resolve("icon.png")
        val planner = object : RenderFilePlanner() {
            override fun plan(input: Path, output: Path): RenderPlan {
                val planned = super.plan(input, output)
                Files.delete(input)
                Files.createSymbolicLink(input, replacement)
                return planned
            }
        }
        var rendered = false
        val command = RenderCommand(
            renderer = object : VectorDrawableRenderer {
                override fun renderXml(xml: String, options: io.github.ravenliao.svg2vd.engine.RenderOptions): RenderedPng {
                    rendered = true
                    return RenderedPng(byteArrayOf(1))
                }
            },
            planner = planner,
        )

        val result = try {
            command.execute(RenderRequest(input, output))
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported by this file system")
            throw AssertionError("unreachable")
        }

        assertEquals(Outcome.PARTIAL_FAILURE, result.outcome)
        assertEquals(FileStatus.FAILED, result.results.single().status)
        assertEquals("unsafe_symlink", result.results.single().diagnostics.single().code)
        assertFalse(rendered)
        assertFalse(Files.exists(output))
    }

    @Test
    fun `input with a symbolic-link dot-dot path is rejected before rendering`() {
        val root = Files.createTempDirectory("render-command")
        val external = Files.createTempDirectory("render-command-external")
        val linkTarget = external.resolve("nested").also { Files.createDirectories(it) }
        val link = root.resolve("link")
        try {
            Files.createSymbolicLink(link, linkTarget)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported by this file system")
        }
        external.resolve("icon.xml").writeText("<vector/>")
        val input = link.resolve("..").resolve("icon.xml")
        var rendered = false
        var written = false
        val command = RenderCommand(
            renderer = recordingRenderer { rendered = true },
            writer = { _, _, _ ->
                written = true
                WriteResult(true)
            },
        )

        val result = command.execute(RenderRequest(input, root.resolve("icon.png")))

        assertEquals(Outcome.PARTIAL_FAILURE, result.outcome)
        assertEquals("unsafe_path", result.results.single().diagnostics.single().code)
        assertFalse(rendered)
        assertFalse(written)
    }

    @Test
    fun `output with a symbolic-link dot-dot path is rejected before writing`() {
        val root = Files.createTempDirectory("render-command")
        val external = Files.createTempDirectory("render-command-external")
        val linkTarget = external.resolve("nested").also { Files.createDirectories(it) }
        val link = root.resolve("link")
        try {
            Files.createSymbolicLink(link, linkTarget)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported by this file system")
        }
        val input = root.resolve("icon.xml").also { it.writeText("<vector/>") }
        val output = link.resolve("..").resolve("icon.png")
        var rendered = false
        var written = false
        val command = RenderCommand(
            renderer = recordingRenderer { rendered = true },
            writer = { path, bytes, _ ->
                written = true
                Files.write(path, bytes)
                WriteResult(true)
            },
        )

        val result = command.execute(RenderRequest(input, output))

        assertEquals(Outcome.PARTIAL_FAILURE, result.outcome)
        assertEquals("unsafe_path", result.results.single().diagnostics.single().code)
        assertFalse(rendered)
        assertFalse(written)
        assertFalse(Files.exists(external.resolve("icon.png")))
    }

    private fun renderer() = object : VectorDrawableRenderer {
        override fun renderXml(xml: String, options: io.github.ravenliao.svg2vd.engine.RenderOptions) = RenderedPng(byteArrayOf(1, 2, 3))
    }

    private fun recordingRenderer(rendered: () -> Unit) = object : VectorDrawableRenderer {
        override fun renderXml(xml: String, options: io.github.ravenliao.svg2vd.engine.RenderOptions): RenderedPng {
            rendered()
            return RenderedPng(byteArrayOf(1, 2, 3))
        }
    }

    private fun error(code: String) = EngineDiagnostic(EngineDiagnosticSeverity.ERROR, code, "write failed")
}
