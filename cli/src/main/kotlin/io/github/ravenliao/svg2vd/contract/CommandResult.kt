package io.github.ravenliao.svg2vd.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CommandResult(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("command") val command: String,
    @SerialName("outcome") val outcome: Outcome,
    @SerialName("summary") val summary: Summary? = null,
    @SerialName("results") val results: List<FileResult> = emptyList(),
    @SerialName("diagnostics") val diagnostics: List<Diagnostic> = emptyList(),
    @SerialName("help") val help: HelpPayload? = null,
)

@Serializable
enum class Outcome {
    @SerialName("success") SUCCESS,
    @SerialName("partial_failure") PARTIAL_FAILURE,
    @SerialName("failure") FAILURE,
}

@Serializable
data class Summary(
    @SerialName("total") val total: Int,
    @SerialName("succeeded") val succeeded: Int,
    @SerialName("failed") val failed: Int,
)

@Serializable
data class FileResult(
    @SerialName("input") val input: String,
    @SerialName("output") val output: String? = null,
    @SerialName("status") val status: FileStatus,
    @SerialName("diagnostics") val diagnostics: List<Diagnostic> = emptyList(),
)

@Serializable
enum class FileStatus {
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
}

@Serializable
data class Diagnostic(
    @SerialName("severity") val severity: Severity,
    @SerialName("code") val code: String,
    @SerialName("message") val message: String,
)

@Serializable
enum class Severity {
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

@Serializable
data class HelpPayload(
    @SerialName("usage") val usage: String,
    @SerialName("description") val description: String? = null,
)
