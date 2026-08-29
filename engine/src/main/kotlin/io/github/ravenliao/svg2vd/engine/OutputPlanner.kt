package io.github.ravenliao.svg2vd.engine

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

data class ConvertRequest(
    val inputs: List<Path>,
    val output: Path,
    val recursive: Boolean = false,
    val overwrite: Boolean = false,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val addAospHeader: Boolean = false,
)

data class PlannedOutput(
    val input: Path,
    val output: Path?,
    val diagnostics: List<EngineDiagnostic> = emptyList(),
)

class OutputPlanner {
    fun plan(request: ConvertRequest): List<PlannedOutput> {
        val unsafeOutput = outputHasSymlink(request.output)
        val outputRoot = request.output.toAbsolutePath().normalize()
        val candidates = request.inputs.flatMap { input ->
            val inputRoot = input.toAbsolutePath().normalize()
            if (pathHasSymlink(input) || unsafeOutput || (Files.isDirectory(input) && outputRoot.startsWith(inputRoot))) {
                listOf(input)
            } else {
                expand(input, request.recursive)
            }
        }

        val initial = candidates.map { input -> planInput(input, request) }
        val duplicateKeys = initial.filter { it.output != null }
            .groupBy { it.output.toString().lowercase(Locale.ROOT) }
            .filterValues { it.size > 1 }
            .keys

        return initial.map { planned ->
            if (planned.output?.toString()?.lowercase(Locale.ROOT) in duplicateKeys) {
                planned.copy(diagnostics = planned.diagnostics + error("duplicate_output", "Multiple inputs map to the same drawable output."))
            } else {
                planned
            }
        }
    }

    fun revalidateInput(input: Path): List<EngineDiagnostic> = when {
        pathHasSymlink(input) -> listOf(error("unsafe_symlink", "Symbolic links are not accepted as conversion inputs."))
        !Files.isRegularFile(input) || !Files.isReadable(input) -> listOf(error("unreadable_input", "Input is not a readable regular file."))
        else -> emptyList()
    }

    private fun expand(input: Path, recursive: Boolean): List<Path> = try {
        when {
            Files.isRegularFile(input) -> listOf(input)
            Files.isDirectory(input) -> if (recursive) recursiveFiles(input) else directFiles(input)
            else -> listOf(input)
        }
    } catch (_: IOException) {
        listOf(input)
    }

    private fun directFiles(directory: Path): List<Path> = Files.newDirectoryStream(directory).use { entries ->
        entries.filter { Files.isRegularFile(it) }.sortedBy(Path::toString)
    }

    private fun recursiveFiles(directory: Path): List<Path> {
        val files = mutableListOf<Path>()
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (attributes.isRegularFile) files.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return files.sortedBy(Path::toString)
    }

    private fun planInput(input: Path, request: ConvertRequest): PlannedOutput {
        if (pathHasSymlink(input)) {
            return PlannedOutput(input, null, revalidateInput(input))
        }
        if (outputHasSymlink(request.output)) {
            return failed(input, "unsafe_symlink", "Output directory must not contain a symbolic-link ancestor.")
        }
        if (Files.isDirectory(input) && request.output.toAbsolutePath().normalize().startsWith(input.toAbsolutePath().normalize())) {
            return failed(input, "unsafe_output_directory", "Output directory must not be inside an input tree.")
        }
        revalidateInput(input).takeIf { it.isNotEmpty() }?.let { return PlannedOutput(input, null, it) }
        val fileName = input.fileName.toString()
        if (!fileName.endsWith(".svg", ignoreCase = true)) {
            return failed(input, "not_svg", "Input is not an SVG file.")
        }
        val drawableName = fileName.substring(0, fileName.length - 4)
        val target = request.output.resolve("$drawableName.xml")
        if (!DRAWABLE_NAME.matches(drawableName)) {
            return PlannedOutput(input, target, listOf(error("invalid_drawable_name", "Input filename is not a valid Android drawable resource name.")))
        }
        return if (!request.overwrite && Files.exists(target)) {
            PlannedOutput(input, target, listOf(error("output_exists", "Output already exists.")))
        } else {
            PlannedOutput(input, target)
        }
    }

    private fun failed(input: Path, code: String, message: String) = PlannedOutput(input, null, listOf(error(code, message)))

    private fun error(code: String, message: String) = EngineDiagnostic(EngineDiagnosticSeverity.ERROR, code, message)

    private fun outputHasSymlink(output: Path): Boolean {
        return pathHasSymlink(output)
    }

    private fun pathHasSymlink(path: Path): Boolean {
        var current: Path? = path.toAbsolutePath().normalize()
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

    private companion object {
        val DRAWABLE_NAME = Regex("[a-z][a-z0-9_]*")
    }
}
