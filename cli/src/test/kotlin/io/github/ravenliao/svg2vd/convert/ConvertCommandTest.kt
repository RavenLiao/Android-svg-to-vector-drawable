package io.github.ravenliao.svg2vd.convert

import io.github.ravenliao.svg2vd.contract.CommandResult
import io.github.ravenliao.svg2vd.contract.ExitCode
import io.github.ravenliao.svg2vd.contract.FileStatus
import io.github.ravenliao.svg2vd.contract.Outcome
import io.github.ravenliao.svg2vd.engine.AtomicFileWriter
import io.github.ravenliao.svg2vd.engine.ConvertRequest
import io.github.ravenliao.svg2vd.engine.EngineConversion
import io.github.ravenliao.svg2vd.engine.EngineDiagnostic
import io.github.ravenliao.svg2vd.engine.EngineDiagnosticSeverity
import io.github.ravenliao.svg2vd.engine.OutputPlanner
import io.github.ravenliao.svg2vd.engine.UpstreamEngineAdapter
import io.github.ravenliao.svg2vd.engine.ConversionEngine
import io.github.ravenliao.svg2vd.engine.ConversionOptions
import io.github.ravenliao.svg2vd.engine.EngineValidation
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue

class ConvertCommandTest {
    @Test
    fun `converts successful inputs preserves warnings and emits one JSON response`() {
        val root = Files.createTempDirectory("convert-command")
        val input = svg(root.resolve("icon.svg"))
        val output = root.resolve("out")
        val engine = RecordingEngine()
        val command = command(engine)
        val stdout = StringBuilder()

        val exit = command.emit(ConvertRequest(listOf(input), output, widthDp = 32, heightDp = 16), stdout)
        val result = Json.decodeFromString<CommandResult>(stdout.toString())

        assertEquals(ExitCode.SUCCESS, exit)
        assertEquals(Outcome.SUCCESS, result.outcome)
        assertEquals(FileStatus.SUCCEEDED, result.results.single().status)
        assertEquals("<vector/>", Files.readString(output.resolve("icon.xml")))
        assertEquals(32, engine.options.widthDp)
        assertEquals(16, engine.options.heightDp)
        assertTrue(stdout.toString().endsWith("\n"))
        assertFalse(stdout.toString().contains("Usage:"))
    }

    @Test
    fun `an SVG with upstream error logs still converts when VectorDrawable XML is produced`() {
        val root = Files.createTempDirectory("convert-command")
        val good = svg(root.resolve("good.svg"))
        val bad = root.resolve("bad.svg").also { it.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path fill=\"#000000\" d=\"M1,1 L2,2\"/><text>bad</text></svg>") }
        val output = root.resolve("out")
        val stdout = StringBuilder()

        val exit = command(UpstreamEngineAdapter()).emit(ConvertRequest(listOf(good, bad), output), stdout)
        val result = Json.decodeFromString<CommandResult>(stdout.toString())

        assertEquals(ExitCode.SUCCESS, exit)
        assertEquals(Outcome.SUCCESS, result.outcome)
        assertEquals(2, result.results.size)
        assertTrue(result.results.any { it.input == good.toString() && it.status == FileStatus.SUCCEEDED })
        assertTrue(result.results.any { it.input == bad.toString() && it.status == FileStatus.SUCCEEDED && it.diagnostics.all { diagnostic -> diagnostic.code == "engine_warning" } })
        assertTrue(Files.isRegularFile(output.resolve("good.xml")))
        assertTrue(Files.isRegularFile(output.resolve("bad.xml")))
    }

    @Test
    fun `an SVG with no VectorDrawable XML does not prevent a neighboring good SVG from being converted`() {
        val root = Files.createTempDirectory("convert-command")
        val good = svg(root.resolve("good.svg"))
        val bad = root.resolve("bad.svg").also { it.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"/>") }
        val output = root.resolve("out")
        val stdout = StringBuilder()

        val exit = command(UpstreamEngineAdapter()).emit(ConvertRequest(listOf(good, bad), output), stdout)
        val result = Json.decodeFromString<CommandResult>(stdout.toString())

        assertEquals(ExitCode.CONVERSION_FAILURE, exit)
        assertEquals(Outcome.PARTIAL_FAILURE, result.outcome)
        assertTrue(result.results.any { it.input == good.toString() && it.status == FileStatus.SUCCEEDED })
        assertTrue(result.results.any { it.input == bad.toString() && it.status == FileStatus.FAILED })
        assertTrue(Files.isRegularFile(output.resolve("good.xml")))
        assertFalse(Files.exists(output.resolve("bad.xml")))
    }

    @Test
    fun `an engine exception fails only its input and continues the batch`() {
        val root = Files.createTempDirectory("convert-command")
        val broken = svg(root.resolve("broken.svg"))
        val good = svg(root.resolve("good.svg"))
        val output = root.resolve("out")
        val engine = object : ConversionEngine {
            override fun convert(input: Path, options: ConversionOptions): EngineConversion {
                if (input == broken) throw IllegalStateException("unexpected upstream failure")
                return EngineConversion("<vector/>")
            }

            override fun validate(input: Path) = EngineValidation(isValid = true)
        }

        val result = command(engine).execute(ConvertRequest(listOf(broken, good), output))

        assertEquals(Outcome.PARTIAL_FAILURE, result.outcome)
        assertTrue(result.results.any { it.input == broken.toString() && it.status == FileStatus.FAILED && it.diagnostics.single().code == "engine_error" })
        assertTrue(result.results.any { it.input == good.toString() && it.status == FileStatus.SUCCEEDED })
        assertTrue(Files.isRegularFile(output.resolve("good.xml")))
    }

    @Test
    fun `AOSP header is prepended as the fixture byte sequence`() {
        val root = Files.createTempDirectory("convert-command")
        val input = svg(root.resolve("icon.svg"))
        val output = root.resolve("out")
        val header = Path.of(requireNotNull(System.getProperty("svg2vd.projectRoot"))).resolve("fixtures/aosp-header.txt").toFile().readBytes()

        command(RecordingEngine()).execute(ConvertRequest(listOf(input), output, addAospHeader = true))

        assertTrue(Files.readAllBytes(output.resolve("icon.xml")).contentEquals(header + "<vector/>".toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `missing required command arguments are a JSON usage failure without outputs`() {
        val root = Files.createTempDirectory("convert-command")
        val input = svg(root.resolve("icon.svg"))
        val stdout = StringBuilder()

        val exit = command(RecordingEngine()).emitArguments(listOf("--input", input.toString()), stdout)
        val result = Json.decodeFromString<CommandResult>(stdout.toString())

        assertEquals(ExitCode.USAGE, exit)
        assertEquals(Outcome.FAILURE, result.outcome)
        assertEquals("usage_error", result.diagnostics.single().code)
        assertFalse(Files.exists(root.resolve("out")))
    }

    @Test
    fun `unsupported format flag is a usage failure rather than a text parser error`() {
        val root = Files.createTempDirectory("convert-command")
        val input = svg(root.resolve("icon.svg"))
        val stdout = StringBuilder()

        val exit = command(RecordingEngine()).emitArguments(listOf("--input", input.toString(), "--output", root.resolve("out").toString(), "--format", "xml"), stdout)
        val result = Json.decodeFromString<CommandResult>(stdout.toString())

        assertEquals(ExitCode.USAGE, exit)
        assertEquals("usage_error", result.diagnostics.single().code)
    }

    @Test
    fun `value options reject a following option token as a missing value`() {
        val root = Files.createTempDirectory("convert-command")
        val input = svg(root.resolve("icon.svg"))
        val output = root.resolve("out")
        val cases = listOf(
            listOf("--input", "--recursive", "--output", output.toString()) to "--input requires a value.",
            listOf("--input", input.toString(), "--output", "--recursive", "--unknown") to "--output requires a value.",
            listOf("--input", input.toString(), "--width-dp", "--recursive", "--output", output.toString()) to "--width-dp requires a value.",
            listOf("--input", input.toString(), "--height-dp", "--recursive", "--output", output.toString()) to "--height-dp requires a value.",
        )

        cases.forEach { (arguments, expectedMessage) ->
            val stdout = StringBuilder()

            val exit = command(RecordingEngine()).emitArguments(arguments, stdout)
            val result = Json.decodeFromString<CommandResult>(stdout.toString())

            assertEquals(ExitCode.USAGE, exit)
            assertEquals(Outcome.FAILURE, result.outcome)
            assertEquals(expectedMessage, result.diagnostics.single().message)
            assertTrue(result.results.isEmpty())
            assertFalse(Files.exists(output))
        }
    }

    @Test
    fun `overwrite rejects an existing symbolic-link output without changing its target`() {
        val root = Files.createTempDirectory("convert-command")
        val input = svg(root.resolve("icon.svg"))
        val output = root.resolve("out")
        val external = Files.createTempDirectory("convert-command-external")
        val externalTarget = external.resolve("existing.xml").also { it.writeText("sentinel") }
        output.createDirectories()
        try {
            Files.createSymbolicLink(output.resolve("icon.xml"), externalTarget)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported by this file system")
        }

        val result = command(RecordingEngine()).execute(ConvertRequest(listOf(input), output, overwrite = true))

        assertEquals(Outcome.PARTIAL_FAILURE, result.outcome)
        assertEquals(FileStatus.FAILED, result.results.single().status)
        assertTrue(result.results.single().diagnostics.any { it.code == "unsafe_symlink" })
        assertTrue(Files.isSymbolicLink(output.resolve("icon.xml")))
        assertEquals("sentinel", Files.readString(externalTarget))
    }

    @Test
    fun `output directory replaced by a symbolic link after planning fails each file without external writes`() {
        val root = Files.createTempDirectory("convert-command")
        val first = svg(root.resolve("first.svg"))
        val second = svg(root.resolve("second.svg"))
        val output = root.resolve("out").also { it.createDirectories() }
        val external = Files.createTempDirectory("convert-command-external")
        val converted = mutableListOf<Path>()
        val engine = object : ConversionEngine {
            private var swapped = false

            override fun convert(input: Path, options: ConversionOptions): EngineConversion {
                converted.add(input)
                if (!swapped) {
                    Files.delete(output)
                    Files.createSymbolicLink(output, external)
                    swapped = true
                }
                return EngineConversion("<vector/>")
            }

            override fun validate(input: Path) = EngineValidation(isValid = true)
        }

        val result = try {
            command(engine).execute(ConvertRequest(listOf(first, second), output))
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported by this file system")
            throw AssertionError("unreachable")
        }

        assertEquals(Outcome.PARTIAL_FAILURE, result.outcome)
        assertEquals(listOf(first, second), converted)
        assertTrue(result.results.all { it.status == FileStatus.FAILED })
        assertTrue(result.results.all { file -> file.diagnostics.single().code == "unsafe_symlink" })
        assertFalse(Files.exists(external.resolve("first.xml")))
        assertFalse(Files.exists(external.resolve("second.xml")))
    }

    @Test
    fun `input replaced by a symbolic link after planning fails before engine conversion`() {
        val root = Files.createTempDirectory("convert-command")
        val first = svg(root.resolve("first.svg"))
        val second = svg(root.resolve("second.svg"))
        val external = svg(Files.createTempDirectory("convert-command-external").resolve("external.svg"))
        val probe = root.resolve("symbolic-link-probe")
        val linksSupported = try {
            Files.createSymbolicLink(probe, external)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } finally {
            Files.deleteIfExists(probe)
        }
        assumeTrue(linksSupported, "symbolic links are not supported by this file system")
        val converted = mutableListOf<Path>()
        val engine = object : ConversionEngine {
            override fun convert(input: Path, options: ConversionOptions): EngineConversion {
                converted.add(input)
                if (input == first) {
                    Files.delete(second)
                    Files.createSymbolicLink(second, external)
                }
                return EngineConversion("<vector/>")
            }

            override fun validate(input: Path) = EngineValidation(isValid = true)
        }
        val stdout = StringBuilder()

        val exit = command(engine).emit(ConvertRequest(listOf(first, second), root.resolve("out")), stdout)
        val result = Json.decodeFromString<CommandResult>(stdout.toString())
        val summary = requireNotNull(result.summary)
        val secondResult = result.results.single { it.input == second.toString() }

        assertEquals(ExitCode.CONVERSION_FAILURE, exit)
        assertEquals(Outcome.PARTIAL_FAILURE, result.outcome)
        assertEquals(2, summary.total)
        assertEquals(1, summary.succeeded)
        assertEquals(1, summary.failed)
        assertEquals(FileStatus.SUCCEEDED, result.results.single { it.input == first.toString() }.status)
        assertEquals(FileStatus.FAILED, secondResult.status)
        assertEquals("unsafe_symlink", secondResult.diagnostics.single().code)
        assertEquals(listOf(first), converted)
        assertTrue(Files.isRegularFile(root.resolve("out/first.xml")))
        assertFalse(Files.exists(root.resolve("out/second.xml")))
    }

    private fun command(engine: ConversionEngine) = ConvertCommand(engine, OutputPlanner(), AtomicFileWriter())

    private fun svg(path: Path): Path = path.also {
        it.parent.createDirectories()
        it.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M1,1 L2,2\"/></svg>")
    }

    private class RecordingEngine : ConversionEngine {
        lateinit var options: ConversionOptions

        override fun convert(input: Path, options: ConversionOptions): EngineConversion {
            this.options = options
            return EngineConversion("<vector/>", listOf(EngineDiagnostic(EngineDiagnosticSeverity.WARNING, "engine_warning", "warning")))
        }

        override fun validate(input: Path) = EngineValidation(isValid = true)
    }
}
