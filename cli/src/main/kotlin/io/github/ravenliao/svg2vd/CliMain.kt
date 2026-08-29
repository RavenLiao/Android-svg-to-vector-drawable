package io.github.ravenliao.svg2vd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import io.github.ravenliao.svg2vd.contract.CommandResult
import io.github.ravenliao.svg2vd.contract.Diagnostic
import io.github.ravenliao.svg2vd.contract.ExitCode
import io.github.ravenliao.svg2vd.contract.HelpPayload
import io.github.ravenliao.svg2vd.contract.JsonEmitter
import io.github.ravenliao.svg2vd.contract.Outcome
import io.github.ravenliao.svg2vd.contract.Severity
import io.github.ravenliao.svg2vd.convert.ConvertCommand
import io.github.ravenliao.svg2vd.render.RenderCommand
import io.github.ravenliao.svg2vd.render.RenderRequest
import io.github.ravenliao.svg2vd.engine.ConvertRequest
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(CliRunner(System.out).run(args.toList()).value)
}

class CliRunner(
    private val output: Appendable,
    private val commandFactory: () -> ConvertCommand = ::ConvertCommand,
    private val renderCommandFactory: () -> RenderCommand = ::RenderCommand,
) {
    fun run(arguments: List<String>): ExitCode {
        var result: CommandResult? = null

        return try {
            val root = Svg2VdCommand()
            root.subcommands(
                ConvertCliktCommand(commandFactory()) { result = it },
                RenderCliktCommand(renderCommandFactory()) { result = it },
            )
            root.parse(arguments)
            JsonEmitter(output).emit(requireNotNull(result) { "No command result was produced." })
        } catch (help: PrintHelpMessage) {
            JsonEmitter(output).emit(helpResult(help.context?.command?.commandName ?: "svg2vd"))
        } catch (error: UsageError) {
            JsonEmitter(output).emit(usageResult(error.message ?: "Invalid command arguments."))
        } catch (_: Throwable) {
            JsonEmitter(output).emit(internalError())
        }
    }

    private fun helpResult(command: String) = when (command) {
        "convert" -> CommandResult(
            command = "convert",
            outcome = Outcome.SUCCESS,
            help = HelpPayload(
                usage = "svg2vd convert --input <file> --output <directory>",
                description = "Convert SVG files",
            ),
        )
        "render" -> CommandResult(
            command = "render",
            outcome = Outcome.SUCCESS,
            help = HelpPayload(
                usage = "svg2vd render --input <svg-or-vector-xml> --output <png> [--size <positive-int>] [--overwrite]",
                description = "Render a VectorDrawable as PNG",
            ),
        )
        else -> CommandResult(
            command = "svg2vd",
            outcome = Outcome.SUCCESS,
            help = HelpPayload(usage = "svg2vd <command>", description = "Convert SVG files"),
        )
    }

    private fun usageResult(message: String) = CommandResult(
        command = "svg2vd",
        outcome = Outcome.FAILURE,
        diagnostics = listOf(Diagnostic(Severity.ERROR, "usage_error", message)),
    )

    private fun internalError() = CommandResult(
        command = "svg2vd",
        outcome = Outcome.FAILURE,
        diagnostics = listOf(Diagnostic(Severity.ERROR, "internal_error", "Unexpected internal error.")),
    )
}

private class Svg2VdCommand : CliktCommand(
    name = "svg2vd",
    help = "Convert SVG files",
    invokeWithoutSubcommand = true,
    printHelpOnEmptyArgs = false,
) {
    init {
        context { helpOptionNames = setOf("--help") }
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            currentContext.fail("A subcommand is required.")
        }
    }
}

private class ConvertCliktCommand(
    private val convertCommand: ConvertCommand,
    private val respond: (CommandResult) -> Unit,
) : CliktCommand(name = "convert", help = "Convert SVG files") {
    private val inputs: List<String> by option("--input").multiple()
    private val output: String by option("--output").required()
    private val recursive: Boolean by option("--recursive").flag()
    private val overwrite: Boolean by option("--overwrite").flag()
    private val widthDp: Int? by option("--width-dp").int()
    private val heightDp: Int? by option("--height-dp").int()
    private val addAospHeader: Boolean by option("--add-aosp-header").flag()

    init {
        context { helpOptionNames = setOf("--help") }
    }

    override fun run() {
        respond(
            convertCommand.execute(
                ConvertRequest(
                    inputs = inputs.map(Path::of),
                    output = Path.of(output),
                    recursive = recursive,
                    overwrite = overwrite,
                    widthDp = widthDp,
                    heightDp = heightDp,
                    addAospHeader = addAospHeader,
                ),
            ),
        )
    }
}

private class RenderCliktCommand(
    private val renderCommand: RenderCommand,
    private val respond: (CommandResult) -> Unit,
) : CliktCommand(name = "render", help = "Render a VectorDrawable as PNG") {
    private val input: String by option("--input").required()
    private val output: String by option("--output").required()
    private val size: Int? by option("--size").int()
    private val overwrite: Boolean by option("--overwrite").flag()

    init {
        context { helpOptionNames = setOf("--help") }
    }

    override fun run() {
        respond(renderCommand.execute(RenderRequest(Path.of(input), Path.of(output), size ?: 64, overwrite)))
    }
}
