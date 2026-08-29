package io.github.ravenliao.svg2vd

import io.github.ravenliao.svg2vd.contract.CommandResult
import io.github.ravenliao.svg2vd.contract.ExitCode
import io.github.ravenliao.svg2vd.contract.FileStatus
import io.github.ravenliao.svg2vd.contract.Outcome
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class CliRuntimeTest {
    @Test
    fun `root and convert help are successful JSON responses`() {
        assertHelp(listOf("--help"), "svg2vd")
        assertHelp(listOf("convert", "--help"), "convert")
    }

    @Test
    fun `render help advertises optional size and overwrite flags`() {
        val process = runJar("render", "--help")
        val response = process.jsonResponse()

        assertEquals(0, process.exitCode)
        assertEquals("render", response.command)
        assertEquals("svg2vd render --input <svg-or-vector-xml> --output <png> [--size <positive-int>] [--overwrite]", response.help?.usage)
    }

    @Test
    fun `missing command and invalid convert option are JSON usage failures`() {
        assertUsageFailure(emptyList())
        assertUsageFailure(listOf("not-a-command"))
        assertUsageFailure(listOf("convert", "--input", "missing.svg"))
        assertUsageFailure(listOf("convert", "--format", "xml"))
    }

    @Test
    fun `convert writes vector XML and emits one JSON response`() {
        val root = Files.createTempDirectory("svg2vd-runtime")
        val input = root.resolve("icon.svg").also { it.writeSvg() }
        val output = root.resolve("out")

        val process = runJar("convert", "--input", input.toString(), "--output", output.toString())
        val response = process.jsonResponse()

        assertEquals(0, process.exitCode)
        assertEquals(Outcome.SUCCESS, response.outcome)
        assertTrue(Files.readString(output.resolve("icon.xml")).contains("<vector"))
    }

    @Test
    fun `convert preserves XML for upstream error logs`() {
        val root = Files.createTempDirectory("svg2vd-runtime")
        val good = root.resolve("good.svg").also { it.writeSvg() }
        val bad = root.resolve("bad.svg").also {
            it.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path fill=\"#000000\" d=\"M1,1 L2,2\"/><text>bad</text></svg>")
        }
        val output = root.resolve("out")

        val process = runJar(
            "convert", "--input", good.toString(), "--input", bad.toString(), "--output", output.toString(),
        )
        val response = process.jsonResponse()

        assertEquals(0, process.exitCode)
        assertEquals(Outcome.SUCCESS, response.outcome)
        assertTrue(response.results.any { it.input == good.toString() && it.status == FileStatus.SUCCEEDED })
        assertTrue(response.results.any { it.input == bad.toString() && it.status == FileStatus.SUCCEEDED && it.diagnostics.all { diagnostic -> diagnostic.code == "engine_warning" } })
        assertTrue(Files.readString(output.resolve("good.xml")).contains("<vector"))
        assertTrue(Files.readString(output.resolve("bad.xml")).contains("<vector"))
    }

    @Test
    fun `convert keeps partial failure for an SVG with no VectorDrawable XML`() {
        val root = Files.createTempDirectory("svg2vd-runtime")
        val good = root.resolve("good.svg").also { it.writeSvg() }
        val bad = root.resolve("bad.svg").also {
            it.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"/>")
        }
        val output = root.resolve("out")

        val process = runJar(
            "convert", "--input", good.toString(), "--input", bad.toString(), "--output", output.toString(),
        )
        val response = process.jsonResponse()

        assertEquals(3, process.exitCode)
        assertEquals(Outcome.PARTIAL_FAILURE, response.outcome)
        assertTrue(response.results.any { it.input == good.toString() && it.status == FileStatus.SUCCEEDED })
        assertTrue(response.results.any { it.input == bad.toString() && it.status == FileStatus.FAILED })
        assertTrue(Files.readString(output.resolve("good.xml")).contains("<vector"))
    }

    @Test
    fun `render writes a PNG from VectorDrawable XML`() {
        val root = Files.createTempDirectory("svg2vd-runtime")
        val input = projectRoot().resolve("cli/src/test/resources/render/simple-vector.xml")
        val output = root.resolve("simple.png")

        val process = runJar("render", "--input", input.toString(), "--output", output.toString())
        val response = process.jsonResponse()

        assertEquals(0, process.exitCode)
        assertEquals("render", response.command)
        assertEquals(Outcome.SUCCESS, response.outcome)
        assertEquals(FileStatus.SUCCEEDED, response.results.single().status)
        assertPng(output)
    }

    @Test
    fun `render accepts SVG conversion warnings when VectorDrawable XML is produced`() {
        val root = Files.createTempDirectory("svg2vd-runtime")
        val input = root.resolve("warning.svg").also {
            it.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path fill=\"#000000\" d=\"M1,1 L2,2\"/><text>bad</text></svg>")
        }
        val output = root.resolve("warning.png")

        val process = runJar("render", "--input", input.toString(), "--output", output.toString())
        val response = process.jsonResponse()

        assertEquals(0, process.exitCode)
        assertEquals("render", response.command)
        assertEquals(Outcome.SUCCESS, response.outcome)
        assertTrue(response.results.single().diagnostics.all { it.code == "engine_warning" })
        assertPng(output)
    }

    @Test
    fun `render rejects a non-positive size as JSON usage failure`() {
        val root = Files.createTempDirectory("svg2vd-runtime")
        val input = projectRoot().resolve("cli/src/test/resources/render/simple-vector.xml")

        val process = runJar("render", "--input", input.toString(), "--output", root.resolve("simple.png").toString(), "--size", "0")
        val response = process.jsonResponse()

        assertEquals(2, process.exitCode)
        assertEquals("render", response.command)
        assertEquals(Outcome.FAILURE, response.outcome)
        assertEquals("usage_error", response.diagnostics.single().code)
    }

    @Test
    fun `render fails an SVG with no VectorDrawable XML`() {
        val root = Files.createTempDirectory("svg2vd-runtime")
        val input = root.resolve("empty.svg").also { it.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"/>") }
        val output = root.resolve("empty.png")

        val process = runJar("render", "--input", input.toString(), "--output", output.toString())
        val response = process.jsonResponse()

        assertEquals(3, process.exitCode)
        assertEquals("render", response.command)
        assertEquals(Outcome.PARTIAL_FAILURE, response.outcome)
        assertEquals(FileStatus.FAILED, response.results.single().status)
        assertTrue(!Files.exists(output))
    }

    @Test
    fun `render preserves an existing target without overwrite`() {
        val root = Files.createTempDirectory("svg2vd-runtime")
        val input = projectRoot().resolve("cli/src/test/resources/render/simple-vector.xml")
        val output = root.resolve("simple.png").also { Files.write(it, byteArrayOf(7, 8, 9)) }

        val process = runJar("render", "--input", input.toString(), "--output", output.toString())
        val response = process.jsonResponse()

        assertEquals(3, process.exitCode)
        assertEquals("render", response.command)
        assertEquals("output_exists", response.results.single().diagnostics.single().code)
        assertTrue(Files.readAllBytes(output).contentEquals(byteArrayOf(7, 8, 9)))
    }

    @Test
    fun `render rejects a symbolic-link input`() {
        val root = Files.createTempDirectory("svg2vd-runtime")
        val target = projectRoot().resolve("cli/src/test/resources/render/simple-vector.xml")
        val input = root.resolve("linked.xml")
        try {
            Files.createSymbolicLink(input, target)
        } catch (_: UnsupportedOperationException) {
            return
        }
        val output = root.resolve("simple.png")

        val process = runJar("render", "--input", input.toString(), "--output", output.toString())
        val response = process.jsonResponse()

        assertEquals(3, process.exitCode)
        assertEquals("render", response.command)
        assertEquals("unsafe_symlink", response.results.single().diagnostics.single().code)
        assertTrue(!Files.exists(output))
    }

    @Test
    fun `command factory failure is an internal JSON response without stderr`() {
        val stdout = StringBuilder()
        val stderr = ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(PrintStream(stderr))
        try {
            val exit = CliRunner(stdout, commandFactory = { error("factory failure") }).run(emptyList())
            val response = Json.decodeFromString<CommandResult>(stdout.toString())

            assertEquals(ExitCode.INTERNAL, exit)
            assertEquals(Outcome.FAILURE, response.outcome)
            assertEquals("internal_error", response.diagnostics.single().code)
            assertTrue(stdout.toString().endsWith("\n"))
            assertTrue(stderr.toByteArray().isEmpty())
        } finally {
            System.setErr(originalErr)
        }
    }

    private fun assertHelp(arguments: List<String>, expectedCommand: String) {
        val process = runJar(*arguments.toTypedArray())
        val response = process.jsonResponse()

        assertEquals(0, process.exitCode)
        assertEquals(expectedCommand, response.command)
        assertEquals(Outcome.SUCCESS, response.outcome)
        assertTrue(response.help != null)
    }

    private fun assertUsageFailure(arguments: List<String>) {
        val process = runJar(*arguments.toTypedArray())
        val response = process.jsonResponse()

        assertEquals(2, process.exitCode)
        assertEquals(Outcome.FAILURE, response.outcome)
        assertEquals("usage_error", response.diagnostics.single().code)
    }

    private fun runJar(vararg arguments: String): ProcessResult {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(java, "-Djava.awt.headless=true", "-jar", shadowJar().toString()) + arguments
        val process = ProcessBuilder(command).start()
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
        return ProcessResult(process.waitFor(), stdout, stderr)
    }

    private fun ProcessResult.jsonResponse(): CommandResult {
        assertEquals("", stderr)
        assertTrue(stdout.endsWith("\n"))
        assertTrue(!stdout.dropLast(1).endsWith("\n"))
        return Json.decodeFromString(stdout)
    }

    private fun shadowJar(): Path = Path.of(requireNotNull(System.getProperty("svg2vd.shadowJar")))

    private fun projectRoot(): Path = Path.of(requireNotNull(System.getProperty("svg2vd.projectRoot")))

    private fun assertPng(path: Path) {
        assertTrue(Files.readAllBytes(path).copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
    }

    private fun Path.writeSvg() {
        parent.createDirectories()
        writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M1,1 L2,2\"/></svg>")
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)
}
