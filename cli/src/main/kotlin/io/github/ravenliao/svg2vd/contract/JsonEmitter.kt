package io.github.ravenliao.svg2vd.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Writes the controlled command response without touching process stdout or stderr. */
class JsonEmitter(private val output: Appendable) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    fun emit(result: CommandResult): ExitCode {
        output.append(json.encodeToString(result)).append('\n')
        return result.exitCode()
    }
}

private fun CommandResult.exitCode(): ExitCode = when (outcome) {
    Outcome.SUCCESS -> ExitCode.SUCCESS
    Outcome.PARTIAL_FAILURE -> ExitCode.CONVERSION_FAILURE
    Outcome.FAILURE -> when {
        diagnostics.any { it.code == "internal_error" } -> ExitCode.INTERNAL
        diagnostics.any { it.code == "environment_error" } -> ExitCode.ENVIRONMENT
        diagnostics.any { it.code == "usage_error" } -> ExitCode.USAGE
        else -> ExitCode.CONVERSION_FAILURE
    }
}
