package io.github.ravenliao.svg2vd.contract

import java.lang.StringBuilder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonEmitterTest {
    @Test
    fun `partial file result is emitted as one exact JSON document`() {
        val result = CommandResult(
            command = "convert",
            outcome = Outcome.PARTIAL_FAILURE,
            summary = Summary(total = 3, succeeded = 2, failed = 1),
            results = listOf(
                FileResult(
                    input = "icons/add.svg",
                    output = "res/drawable/add.xml",
                    status = FileStatus.SUCCEEDED,
                    diagnostics = emptyList(),
                ),
                FileResult(
                    input = "icons/broken.svg",
                    output = "res/drawable/broken.xml",
                    status = FileStatus.FAILED,
                    diagnostics = listOf(
                        Diagnostic(Severity.ERROR, "engine_error", "conversion failed"),
                    ),
                ),
            ),
        )
        val stdout = StringBuilder()

        val exitCode = JsonEmitter(stdout).emit(result)

        assertEquals(ExitCode.CONVERSION_FAILURE, exitCode)
        assertEquals(
            """{"schema_version":1,"command":"convert","outcome":"partial_failure","summary":{"total":3,"succeeded":2,"failed":1},"results":[{"input":"icons/add.svg","output":"res/drawable/add.xml","status":"succeeded","diagnostics":[]},{"input":"icons/broken.svg","output":"res/drawable/broken.xml","status":"failed","diagnostics":[{"severity":"error","code":"engine_error","message":"conversion failed"}]}],"diagnostics":[],"help":null}""" + "\n",
            stdout.toString(),
        )
        assertTrue(stdout.toString().endsWith("}\n"))
        assertFalse(stdout.toString().contains("Usage:"))
    }

    @Test
    fun `root and subcommand help have distinct exact JSON responses`() {
        val root = StringBuilder()
        val child = StringBuilder()
        val rootResult = CommandResult(
            command = "svg2vd",
            outcome = Outcome.SUCCESS,
            help = HelpPayload(usage = "svg2vd <command>", description = "Convert SVG files"),
        )
        val childResult = rootResult.copy(
            command = "convert",
            help = HelpPayload(usage = "svg2vd convert --input <file>", description = "Convert SVG files"),
        )

        assertEquals(ExitCode.SUCCESS, JsonEmitter(root).emit(rootResult))
        assertEquals(ExitCode.SUCCESS, JsonEmitter(child).emit(childResult))
        assertEquals(
            """{"schema_version":1,"command":"svg2vd","outcome":"success","summary":null,"results":[],"diagnostics":[],"help":{"usage":"svg2vd <command>","description":"Convert SVG files"}}""" + "\n",
            root.toString(),
        )
        assertEquals(
            """{"schema_version":1,"command":"convert","outcome":"success","summary":null,"results":[],"diagnostics":[],"help":{"usage":"svg2vd convert --input <file>","description":"Convert SVG files"}}""" + "\n",
            child.toString(),
        )
        assertFalse(root.toString().contains("Usage:"))
        assertFalse(child.toString().contains("Usage:"))
    }

    @Test
    fun `successful warning result uses exact JSON and leaves stderr empty`() {
        val stdout = StringBuilder()
        val stderr = ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(PrintStream(stderr))
        try {
            val result = CommandResult(
                command = "validate",
                outcome = Outcome.SUCCESS,
                summary = Summary(total = 1, succeeded = 1, failed = 0),
                results = listOf(
                    FileResult(
                        input = "icons/edit.svg",
                        output = null,
                        status = FileStatus.SUCCEEDED,
                        diagnostics = listOf(Diagnostic(Severity.WARNING, "engine_warning", "legacy path")),
                    ),
                ),
            )

            assertEquals(ExitCode.SUCCESS, JsonEmitter(stdout).emit(result))
            assertEquals(
                """{"schema_version":1,"command":"validate","outcome":"success","summary":{"total":1,"succeeded":1,"failed":0},"results":[{"input":"icons/edit.svg","output":null,"status":"succeeded","diagnostics":[{"severity":"warning","code":"engine_warning","message":"legacy path"}]}],"diagnostics":[],"help":null}""" + "\n",
                stdout.toString(),
            )
            assertTrue(stderr.toByteArray().isEmpty())
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun `usage failure is represented by JSON diagnostics and exit code two`() {
        val stdout = StringBuilder()
        val result = CommandResult(
            command = "svg2vd",
            outcome = Outcome.FAILURE,
            diagnostics = listOf(Diagnostic(Severity.ERROR, "usage_error", "a subcommand is required")),
        )

        assertEquals(ExitCode.USAGE, JsonEmitter(stdout).emit(result))
        assertEquals(
            """{"schema_version":1,"command":"svg2vd","outcome":"failure","summary":null,"results":[],"diagnostics":[{"severity":"error","code":"usage_error","message":"a subcommand is required"}],"help":null}""" + "\n",
            stdout.toString(),
        )
    }

    @Test
    fun `failure exit codes do not depend on diagnostic order`() {
        fun result(vararg codes: String) = CommandResult(
            command = "svg2vd",
            outcome = Outcome.FAILURE,
            diagnostics = codes.map { Diagnostic(Severity.ERROR, it, it) },
        )

        assertEquals(ExitCode.USAGE, JsonEmitter(StringBuilder()).emit(result("usage_error")))
        assertEquals(ExitCode.ENVIRONMENT, JsonEmitter(StringBuilder()).emit(result("environment_error")))
        assertEquals(ExitCode.INTERNAL, JsonEmitter(StringBuilder()).emit(result("internal_error")))
        assertEquals(ExitCode.CONVERSION_FAILURE, JsonEmitter(StringBuilder()).emit(result("engine_error")))
        assertEquals(ExitCode.ENVIRONMENT, JsonEmitter(StringBuilder()).emit(result("usage_error", "environment_error")))
        assertEquals(ExitCode.ENVIRONMENT, JsonEmitter(StringBuilder()).emit(result("environment_error", "usage_error")))
        assertEquals(ExitCode.INTERNAL, JsonEmitter(StringBuilder()).emit(result("internal_error", "usage_error")))
        assertEquals(ExitCode.INTERNAL, JsonEmitter(StringBuilder()).emit(result("usage_error", "internal_error")))
    }
}
