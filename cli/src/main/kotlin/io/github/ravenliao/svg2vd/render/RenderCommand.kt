package io.github.ravenliao.svg2vd.render

import io.github.ravenliao.svg2vd.contract.CommandResult
import io.github.ravenliao.svg2vd.contract.Diagnostic
import io.github.ravenliao.svg2vd.contract.FileResult
import io.github.ravenliao.svg2vd.contract.FileStatus
import io.github.ravenliao.svg2vd.contract.Outcome
import io.github.ravenliao.svg2vd.contract.Severity
import io.github.ravenliao.svg2vd.contract.Summary
import io.github.ravenliao.svg2vd.engine.AtomicFileWriter
import io.github.ravenliao.svg2vd.engine.ConversionEngine
import io.github.ravenliao.svg2vd.engine.ConversionOptions
import io.github.ravenliao.svg2vd.engine.EngineConversion
import io.github.ravenliao.svg2vd.engine.EngineDiagnostic
import io.github.ravenliao.svg2vd.engine.EngineDiagnosticSeverity
import io.github.ravenliao.svg2vd.engine.RenderOptions
import io.github.ravenliao.svg2vd.engine.UpstreamEngineAdapter
import io.github.ravenliao.svg2vd.engine.VectorDrawableRenderer
import io.github.ravenliao.svg2vd.engine.VectorDrawableRenderers
import io.github.ravenliao.svg2vd.engine.WriteResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class RenderRequest(
    val input: Path,
    val output: Path,
    val size: Int = 64,
    val overwrite: Boolean = false,
)

class RenderCommand(
    private val svgEngine: ConversionEngine = UpstreamEngineAdapter(),
    private val renderer: VectorDrawableRenderer = VectorDrawableRenderers.upstream(),
    private val planner: RenderFilePlanner = RenderFilePlanner(),
    private val writer: (Path, ByteArray, Boolean) -> WriteResult = AtomicFileWriter()::writeAtomically,
) {
    fun execute(request: RenderRequest): CommandResult {
        if (request.size <= 0) return usageFailure("--size must be positive.")
        val planned = try {
            planner.plan(request.input, request.output)
        } catch (_: Exception) {
            return result(failure(request.input, null, listOf(error("filesystem_error", "Unable to plan render output."))))
        }
        if (planned.output == null || planned.diagnostics.hasError()) {
            return result(failure(planned.input, planned.output, planned.diagnostics))
        }
        return result(render(planned.input, planned.output, request))
    }

    private fun render(input: Path, output: Path, request: RenderRequest): FileResult = try {
        planner.revalidateInput(input).takeIf { it.hasError() }?.let { return failure(input, output, it) }
        val conversion = if (input.fileName.toString().endsWith(".svg", ignoreCase = true)) {
            svgEngine.convert(input, ConversionOptions())
        } else {
            EngineConversion(Files.readString(input, StandardCharsets.UTF_8))
        }
        val xml = conversion.xml
        if (xml == null || conversion.diagnostics.hasError()) {
            failure(input, output, conversion.diagnostics)
        } else {
            val written = writer(output, renderer.renderXml(xml, RenderOptions(request.size)).bytes, request.overwrite)
            val diagnostics = conversion.diagnostics + written.diagnostics
            if (written.succeeded) {
                FileResult(input.toString(), output.toString(), FileStatus.SUCCEEDED, diagnostics.map { it.asContract() })
            } else {
                failure(input, output, diagnostics)
            }
        }
    } catch (_: Exception) {
        failure(input, output, listOf(error("render_error", "VectorDrawable rendering failed.")))
    }

    private fun result(file: FileResult) = CommandResult(
        command = "render",
        outcome = if (file.status == FileStatus.SUCCEEDED) Outcome.SUCCESS else Outcome.PARTIAL_FAILURE,
        summary = Summary(total = 1, succeeded = if (file.status == FileStatus.SUCCEEDED) 1 else 0, failed = if (file.status == FileStatus.FAILED) 1 else 0),
        results = listOf(file),
    )

    private fun usageFailure(message: String) = CommandResult(
        command = "render",
        outcome = Outcome.FAILURE,
        diagnostics = listOf(Diagnostic(Severity.ERROR, "usage_error", message)),
    )

    private fun failure(input: Path, output: Path?, diagnostics: List<EngineDiagnostic>) = FileResult(
        input = input.toString(),
        output = output?.toString(),
        status = FileStatus.FAILED,
        diagnostics = diagnostics.map { it.asContract() },
    )

    private fun List<EngineDiagnostic>.hasError() = any { it.severity == EngineDiagnosticSeverity.ERROR }

    private fun error(code: String, message: String) = EngineDiagnostic(EngineDiagnosticSeverity.ERROR, code, message)

    private fun EngineDiagnostic.asContract() = Diagnostic(
        severity = if (severity == EngineDiagnosticSeverity.WARNING) Severity.WARNING else Severity.ERROR,
        code = code,
        message = message,
    )
}
