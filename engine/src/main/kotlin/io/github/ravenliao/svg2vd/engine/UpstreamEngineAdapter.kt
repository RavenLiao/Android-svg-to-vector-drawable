package io.github.ravenliao.svg2vd.engine

import com.android.ide.common.vectordrawable.Svg2Vector
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path

interface ConversionEngine {
    fun convert(input: Path, options: ConversionOptions): EngineConversion
    fun validate(input: Path): EngineValidation
}

data class ConversionOptions(
    val widthDp: Int? = null,
    val heightDp: Int? = null,
)

data class EngineConversion(
    val xml: String?,
    val diagnostics: List<EngineDiagnostic> = emptyList(),
)

data class EngineValidation(
    val isValid: Boolean,
    val diagnostics: List<EngineDiagnostic> = emptyList(),
)

data class EngineDiagnostic(
    val severity: EngineDiagnosticSeverity,
    val code: String,
    val message: String,
)

enum class EngineDiagnosticSeverity {
    WARNING,
    ERROR,
}

class UpstreamEngineAdapter(
    private val parser: (Path, ByteArrayOutputStream) -> String = Svg2Vector::parseSvgToXml,
) : ConversionEngine {
    override fun convert(input: Path, options: ConversionOptions): EngineConversion {
        val parsed = parse(input, options)
        return EngineConversion(xml = parsed.xml, diagnostics = parsed.diagnostics)
    }

    override fun validate(input: Path): EngineValidation {
        val parsed = parse(input, ConversionOptions())
        return EngineValidation(isValid = parsed.xml != null, diagnostics = parsed.diagnostics)
    }

    private fun parse(input: Path, options: ConversionOptions): ParsedSvg = try {
        val output = ByteArrayOutputStream()
        val log = parser(input, output)
        val xml = output.toString(StandardCharsets.UTF_8)
        when {
            xml.isEmpty() -> ParsedSvg(null, classifyDiagnostics(log, renderedXml = false) + engineError("Upstream parser produced no VectorDrawable XML."))
            else -> ParsedSvg(applyDimensions(xml, options), classifyDiagnostics(log, renderedXml = true))
        }
    } catch (_: Exception) {
        ParsedSvg(null, listOf(engineError("Upstream SVG conversion failed.")))
    }

    private fun classifyDiagnostics(log: String, renderedXml: Boolean): List<EngineDiagnostic> = log
        .lineSequence()
        .filter(String::isNotBlank)
        .map { line ->
            when {
                renderedXml || line.startsWith("WARNING") -> EngineDiagnostic(EngineDiagnosticSeverity.WARNING, "engine_warning", line)
                else -> EngineDiagnostic(EngineDiagnosticSeverity.ERROR, "engine_error", line)
            }
        }
        .toList()

    private fun engineError(message: String) = EngineDiagnostic(EngineDiagnosticSeverity.ERROR, "engine_error", message)

    private fun applyDimensions(xml: String, options: ConversionOptions): String = xml
        .replaceDimension("width", options.widthDp)
        .replaceDimension("height", options.heightDp)

    private fun String.replaceDimension(name: String, value: Int?): String = if (value == null) this else {
        replace(Regex("android:$name=\\\"[^\\\"]*\\\""), "android:$name=\\\"${value}dp\\\"")
    }

    private data class ParsedSvg(val xml: String?, val diagnostics: List<EngineDiagnostic>)
}
