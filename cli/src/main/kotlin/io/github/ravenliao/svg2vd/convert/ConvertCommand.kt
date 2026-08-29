package io.github.ravenliao.svg2vd.convert

import io.github.ravenliao.svg2vd.contract.CommandResult
import io.github.ravenliao.svg2vd.contract.Diagnostic
import io.github.ravenliao.svg2vd.contract.ExitCode
import io.github.ravenliao.svg2vd.contract.FileResult
import io.github.ravenliao.svg2vd.contract.FileStatus
import io.github.ravenliao.svg2vd.contract.JsonEmitter
import io.github.ravenliao.svg2vd.contract.Outcome
import io.github.ravenliao.svg2vd.contract.Severity
import io.github.ravenliao.svg2vd.contract.Summary
import io.github.ravenliao.svg2vd.engine.AtomicFileWriter
import io.github.ravenliao.svg2vd.engine.ConversionEngine
import io.github.ravenliao.svg2vd.engine.ConversionOptions
import io.github.ravenliao.svg2vd.engine.ConvertRequest
import io.github.ravenliao.svg2vd.engine.EngineDiagnostic
import io.github.ravenliao.svg2vd.engine.EngineDiagnosticSeverity
import io.github.ravenliao.svg2vd.engine.OutputPlanner
import io.github.ravenliao.svg2vd.engine.UpstreamEngineAdapter
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class ConvertCommand(
    private val engine: ConversionEngine = UpstreamEngineAdapter(),
    private val planner: OutputPlanner = OutputPlanner(),
    private val writer: AtomicFileWriter = AtomicFileWriter(),
) {
    fun execute(request: ConvertRequest): CommandResult {
        validate(request)?.let { return usageFailure(it) }
        val planned = planner.plan(request)
        val results = planned.map { item ->
            val target = item.output
            if (item.diagnostics.any { it.severity == EngineDiagnosticSeverity.ERROR } || target == null) {
                failure(item.input, target, item.diagnostics)
            } else {
                convert(item.input, target, request)
            }
        }
        val failed = results.count { it.status == FileStatus.FAILED }
        return CommandResult(
            command = "convert",
            outcome = if (failed == 0) Outcome.SUCCESS else Outcome.PARTIAL_FAILURE,
            summary = Summary(results.size, results.size - failed, failed),
            results = results,
        )
    }

    fun emit(request: ConvertRequest, output: Appendable): ExitCode = JsonEmitter(output).emit(execute(request))

    fun emitArguments(arguments: List<String>, output: Appendable): ExitCode = JsonEmitter(output).emit(
        parseArguments(arguments).fold(
            onSuccess = ::execute,
            onFailure = { failure -> usageFailure(failure.message ?: "Invalid convert arguments.") },
        ),
    )

    private fun convert(input: Path, output: Path, request: ConvertRequest): FileResult {
        return try {
            val inputDiagnostics = planner.revalidateInput(input)
            if (inputDiagnostics.any { it.severity == EngineDiagnosticSeverity.ERROR }) {
                return failure(input, output, inputDiagnostics)
            }
            val conversion = engine.convert(input, ConversionOptions(request.widthDp, request.heightDp))
            val xml = conversion.xml
            if (xml == null || conversion.diagnostics.any { it.severity == EngineDiagnosticSeverity.ERROR }) {
                failure(input, output, conversion.diagnostics)
            } else {
                val bytes = xml.toByteArray(StandardCharsets.UTF_8)
                val content = if (request.addAospHeader) AOSP_HEADER + bytes else bytes
                val written = writer.writeAtomically(output, content, request.overwrite)
                val diagnostics = conversion.diagnostics + written.diagnostics
                if (written.succeeded) {
                    FileResult(input.toString(), output.toString(), FileStatus.SUCCEEDED, diagnostics.map { it.asContract() })
                } else {
                    failure(input, output, diagnostics)
                }
            }
        } catch (_: Exception) {
            failure(input, output, listOf(EngineDiagnostic(EngineDiagnosticSeverity.ERROR, "engine_error", "SVG conversion failed.")))
        }
    }

    private fun failure(input: Path, output: Path?, diagnostics: List<EngineDiagnostic>) = FileResult(
        input = input.toString(),
        output = output?.toString(),
        status = FileStatus.FAILED,
        diagnostics = diagnostics.map { it.asContract() },
    )

    private fun validate(request: ConvertRequest): String? = when {
        request.inputs.isEmpty() -> "At least one --input is required."
        request.widthDp?.let { it <= 0 } == true -> "--width-dp must be positive."
        request.heightDp?.let { it <= 0 } == true -> "--height-dp must be positive."
        else -> null
    }

    private fun usageFailure(message: String) = CommandResult(
        command = "convert",
        outcome = Outcome.FAILURE,
        diagnostics = listOf(Diagnostic(Severity.ERROR, "usage_error", message)),
    )

    private fun parseArguments(arguments: List<String>): Result<ConvertRequest> = runCatching {
        val inputs = mutableListOf<Path>()
        var output: Path? = null
        var recursive = false
        var overwrite = false
        var widthDp: Int? = null
        var heightDp: Int? = null
        var addAospHeader = false
        var index = 0
        fun value(option: String): String {
            index += 1
            check(index < arguments.size) { "$option requires a value." }
            val candidate = arguments[index]
            check(!candidate.startsWith("--")) { "$option requires a value." }
            return candidate
        }
        while (index < arguments.size) {
            when (arguments[index]) {
                "--input" -> inputs.add(Path.of(value("--input")))
                "--output" -> {
                    check(output == null) { "--output may be supplied only once." }
                    output = Path.of(value("--output"))
                }
                "--recursive" -> recursive = true
                "--overwrite" -> overwrite = true
                "--width-dp" -> widthDp = value("--width-dp").toIntOrNull() ?: error("--width-dp must be an integer.")
                "--height-dp" -> heightDp = value("--height-dp").toIntOrNull() ?: error("--height-dp must be an integer.")
                "--add-aosp-header" -> addAospHeader = true
                else -> error("Unsupported convert option: ${arguments[index]}")
            }
            index += 1
        }
        ConvertRequest(inputs, requireNotNull(output) { "--output is required." }, recursive, overwrite, widthDp, heightDp, addAospHeader)
    }

    private fun EngineDiagnostic.asContract() = Diagnostic(
        severity = if (severity == EngineDiagnosticSeverity.WARNING) Severity.WARNING else Severity.ERROR,
        code = code,
        message = message,
    )

    private companion object {
        val AOSP_HEADER = """
            <!--
            Copyright (C) 2016 The Android Open Source Project

            Licensed under the Apache License, Version 2.0 (the "License");
            you may not use this file except in compliance with the License.
            You may obtain a copy of the License at

                  http://www.apache.org/licenses/LICENSE-2.0

            Unless required by applicable law or agreed to in writing, software
            distributed under the License is distributed on an "AS IS" BASIS,
            WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            See the License for the specific language governing permissions and
            limitations under the License.
            -->
        """.trimIndent().plus("\n").toByteArray(StandardCharsets.UTF_8)
    }
}
