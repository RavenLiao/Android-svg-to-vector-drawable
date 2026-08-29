package io.github.ravenliao.svg2vd.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpstreamEngineAdapterTest {
    private val engine = UpstreamEngineAdapter()

    @Test
    fun `convert retains XML and downgrades upstream error logs when parsing produced XML`() {
        val engine = UpstreamEngineAdapter { _, output ->
            output.write("<vector/>".toByteArray())
            "ERROR: unsupported source element"
        }

        val result = engine.convert(fixture("valid.svg"), ConversionOptions())

        assertEquals("<vector/>", result.xml)
        assertEquals(listOf(EngineDiagnosticSeverity.WARNING), result.diagnostics.map(EngineDiagnostic::severity))
        assertEquals(listOf("engine_warning"), result.diagnostics.map(EngineDiagnostic::code))
    }

    @Test
    fun `validate accepts XML and downgrades upstream error logs when parsing produced XML`() {
        val engine = UpstreamEngineAdapter { _, output ->
            output.write("<vector/>".toByteArray())
            "ERROR: unsupported source element"
        }

        val result = engine.validate(fixture("valid.svg"))

        assertTrue(result.isValid)
        assertEquals(listOf(EngineDiagnosticSeverity.WARNING), result.diagnostics.map(EngineDiagnostic::severity))
        assertEquals(listOf("engine_warning"), result.diagnostics.map(EngineDiagnostic::code))
    }

    @Test
    fun `convert returns UTF-8 vector XML for a valid SVG`() {
        val result = engine.convert(fixture("valid.svg"), ConversionOptions())

        assertNotNull(result.xml)
        assertTrue(result.xml.contains("<vector"))
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `convert preserves upstream warnings without failing conversion`() {
        val result = engine.convert(svgFile("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path transform="scale(2,3)" stroke="#000000" stroke-width="1" d="M1,1 L2,2"/>
            </svg>
        """), ConversionOptions())

        assertNotNull(result.xml)
        assertTrue(result.diagnostics.any { it.severity == EngineDiagnosticSeverity.WARNING && it.code == "engine_warning" })
        assertFalse(result.diagnostics.any { it.severity == EngineDiagnosticSeverity.ERROR })
    }

    @Test
    fun `convert retains XML and maps upstream errors to warnings after writing bytes`() {
        val result = engine.convert(svgFile("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <path fill="#000000" d="M1,1 L2,2"/>
              <text x="1" y="2">unsupported</text>
            </svg>
        """), ConversionOptions())

        assertNotNull(result.xml)
        assertTrue(result.diagnostics.any { it.severity == EngineDiagnosticSeverity.WARNING && it.code == "engine_warning" })
        assertFalse(result.diagnostics.any { it.severity == EngineDiagnosticSeverity.ERROR })
    }

    @Test
    fun `convert turns upstream exceptions into engine errors`() {
        val result = engine.convert(Path.of("missing-${System.nanoTime()}.svg"), ConversionOptions())

        assertNull(result.xml)
        assertEquals(listOf("engine_error"), result.diagnostics.map(EngineDiagnostic::code))
        assertEquals(EngineDiagnosticSeverity.ERROR, result.diagnostics.single().severity)
    }

    @Test
    fun `convert treats an SVG with no leaf node as an engine error`() {
        val result = engine.convert(svgFile("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"/>
        """), ConversionOptions())

        assertNull(result.xml)
        assertTrue(result.diagnostics.any { it.severity == EngineDiagnosticSeverity.ERROR && it.code == "engine_error" })
    }

    @Test
    fun `validate rejects an SVG with no VectorDrawable XML`() {
        val result = engine.validate(svgFile("""
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"/>
        """))

        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.severity == EngineDiagnosticSeverity.ERROR && it.code == "engine_error" })
    }

    @Test
    fun `validate turns upstream exceptions into engine errors`() {
        val result = engine.validate(Path.of("missing-${System.nanoTime()}.svg"))

        assertFalse(result.isValid)
        assertEquals(listOf("engine_error"), result.diagnostics.map(EngineDiagnostic::code))
        assertEquals(EngineDiagnosticSeverity.ERROR, result.diagnostics.single().severity)
    }

    @Test
    fun `validate shares conversion diagnostics without producing XML`() {
        val result = engine.validate(fixture("valid.svg"))

        assertTrue(result.isValid)
        assertTrue(result.diagnostics.isEmpty())
    }

    private fun fixture(name: String): Path = Path.of(requireNotNull(javaClass.getResource("/svg/$name")).toURI())

    private fun svgFile(contents: String): Path = Files.createTempFile("svg2vd-adapter", ".svg").also {
        it.writeText(contents.trimIndent())
    }
}
