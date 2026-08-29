package io.github.ravenliao.svg2vd.render

import io.github.ravenliao.svg2vd.engine.EngineDiagnostic
import io.github.ravenliao.svg2vd.engine.EngineDiagnosticSeverity
import java.nio.file.Files
import java.nio.file.Path

data class RenderPlan(
    val input: Path,
    val output: Path?,
    val diagnostics: List<EngineDiagnostic> = emptyList(),
)

/** Plans one regular SVG or VectorDrawable XML file for publication to one PNG path. */
open class RenderFilePlanner {
    open fun plan(input: Path, output: Path): RenderPlan {
        revalidateInput(input).takeIf { it.isNotEmpty() }?.let { return RenderPlan(input, null, it) }
        if (!input.isRenderableInput()) {
            return failed(input, "unsupported_input", "Input must be an .svg or .xml file.")
        }
        if (!output.isPngTarget()) {
            return failed(input, "invalid_output", "Output must be a .png file.")
        }
        if (hasRelativeTraversalSegment(output)) {
            return failed(input, "unsafe_path", "Output paths containing . or .. segments are not accepted.")
        }
        if (pathHasSymlink(output)) {
            return failed(input, "unsafe_symlink", "Output target or an ancestor is a symbolic link; refusing publication.")
        }
        if (Files.exists(output) && Files.isDirectory(output)) {
            return failed(input, "invalid_output", "Output must be a PNG file path, not a directory.")
        }
        return RenderPlan(input, output)
    }

    open fun revalidateInput(input: Path): List<EngineDiagnostic> = when {
        hasRelativeTraversalSegment(input) -> listOf(error("unsafe_path", "Input paths containing . or .. segments are not accepted."))
        pathHasSymlink(input) -> listOf(error("unsafe_symlink", "Symbolic links are not accepted as render inputs."))
        !Files.isRegularFile(input) || !Files.isReadable(input) -> listOf(error("unreadable_input", "Input is not a readable regular file."))
        else -> emptyList()
    }

    private fun Path.isRenderableInput(): Boolean = fileName.toString().let { name ->
        name.endsWith(".svg", ignoreCase = true) || name.endsWith(".xml", ignoreCase = true)
    }

    private fun Path.isPngTarget(): Boolean = fileName.toString().endsWith(".png", ignoreCase = true)

    private fun failed(input: Path, code: String, message: String) = RenderPlan(input, null, listOf(error(code, message)))

    private fun error(code: String, message: String) = EngineDiagnostic(EngineDiagnosticSeverity.ERROR, code, message)

    private fun hasRelativeTraversalSegment(path: Path): Boolean = path.any { segment ->
        segment.toString() == "." || segment.toString() == ".."
    }

    private fun pathHasSymlink(path: Path): Boolean {
        var current: Path? = path.toAbsolutePath()
        while (current != null) {
            if (Files.isSymbolicLink(current) && !isSystemRootAlias(current)) return true
            current = current.parent
        }
        return false
    }

    private fun isSystemRootAlias(path: Path): Boolean {
        val parent = path.parent ?: return false
        val privateAlias = parent.resolve("private").resolve(path.fileName)
        return parent == path.root && Files.exists(privateAlias) && runCatching { Files.isSameFile(path, privateAlias) }.getOrDefault(false)
    }
}
