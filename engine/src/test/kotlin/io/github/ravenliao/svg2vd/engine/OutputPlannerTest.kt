package io.github.ravenliao.svg2vd.engine

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class OutputPlannerTest {
    private val planner = OutputPlanner()

    @Test
    fun `plans a single SVG into a drawable XML output`() {
        val root = Files.createTempDirectory("output-planner")
        val input = svg(root.resolve("ic_add.svg"))
        val output = root.resolveSibling("output-planner-out")

        val planned = planner.plan(ConvertRequest(listOf(input), output))

        assertEquals(1, planned.size)
        assertEquals(output.resolve("ic_add.xml"), planned.single().output)
        assertTrue(planned.single().diagnostics.isEmpty())
    }

    @Test
    fun `directory planning is non-recursive unless explicitly requested`() {
        val root = Files.createTempDirectory("output-planner")
        svg(root.resolve("top.svg"))
        svg(root.resolve("nested").resolve("child.svg"))
        val output = root.resolveSibling("output-planner-directory-out")

        val direct = planner.plan(ConvertRequest(listOf(root), output))
        val recursive = planner.plan(ConvertRequest(listOf(root), output, recursive = true))

        assertEquals(listOf("top.svg"), direct.map { it.input.name })
        assertEquals(listOf("child.svg", "top.svg"), recursive.map { it.input.name }.sorted())
    }

    @Test
    fun `planner reports invalid inputs and conflicting output names independently`() {
        val root = Files.createTempDirectory("output-planner")
        val first = svg(root.resolve("one").resolve("icon.svg"))
        val second = svg(root.resolve("two").resolve("ICON.svg"))
        val invalid = svg(root.resolve("bad-name.svg"))
        val text = root.resolve("note.txt").also { it.writeText("not svg") }

        val planned = planner.plan(ConvertRequest(listOf(first, second, invalid, text), root.resolve("out")))

        assertEquals(4, planned.size)
        assertTrue(planned.all { it.diagnostics.isNotEmpty() })
        assertTrue(planned.flatMap { it.diagnostics }.any { it.code == "duplicate_output" })
        assertTrue(planned.flatMap { it.diagnostics }.any { it.code == "invalid_drawable_name" })
        assertTrue(planned.flatMap { it.diagnostics }.any { it.code == "not_svg" })
    }

    @Test
    fun `planner rejects an output directory inside a directory input tree`() {
        val root = Files.createTempDirectory("output-planner")
        svg(root.resolve("icon.svg"))

        val planned = planner.plan(ConvertRequest(listOf(root), root.resolve("generated"), recursive = true))

        assertEquals(1, planned.size)
        assertEquals("unsafe_output_directory", planned.single().diagnostics.single().code)
    }

    @Test
    fun `unsafe directory input does not hide other planned inputs`() {
        val unsafeRoot = Files.createTempDirectory("output-planner")
        val independentRoot = Files.createTempDirectory("output-planner")
        svg(unsafeRoot.resolve("unsafe.svg"))
        val independent = svg(independentRoot.resolve("independent.svg"))
        val output = unsafeRoot.resolve("generated")

        val planned = planner.plan(ConvertRequest(listOf(unsafeRoot, independent), output, recursive = true))

        assertEquals(2, planned.size)
        assertTrue(planned.any { it.input == unsafeRoot && it.diagnostics.single().code == "unsafe_output_directory" })
        assertTrue(planned.any { it.input == independent && it.output == output.resolve("independent.xml") })
    }

    @Test
    fun `planner does not follow a directory symlink loop`() {
        val root = Files.createTempDirectory("output-planner")
        svg(root.resolve("icon.svg"))
        try {
            Files.createSymbolicLink(root.resolve("loop"), root)
        } catch (_: UnsupportedOperationException) {
            return
        }

        val planned = planner.plan(ConvertRequest(listOf(root), root.resolveSibling("out"), recursive = true))

        assertEquals(listOf("icon.svg"), planned.map { it.input.name })
    }

    @Test
    fun `planner rejects a symlinked SVG input without reading its external target`() {
        val root = Files.createTempDirectory("output-planner")
        val external = svg(Files.createTempDirectory("external-svg").resolve("outside.svg"))
        val link = root.resolve("linked.svg")
        try {
            Files.createSymbolicLink(link, external)
        } catch (_: UnsupportedOperationException) {
            return
        }

        val planned = planner.plan(ConvertRequest(listOf(link), root.resolve("out")))

        assertEquals(1, planned.size)
        assertEquals("unsafe_symlink", planned.single().diagnostics.single().code)
        assertEquals(null, planned.single().output)
    }

    @Test
    fun `planner rejects an input reached through a symlinked directory ancestor`() {
        val root = Files.createTempDirectory("output-planner")
        val external = Files.createTempDirectory("external-svg")
        svg(external.resolve("outside.svg"))
        val link = root.resolve("linked-directory")
        try {
            Files.createSymbolicLink(link, external)
        } catch (_: UnsupportedOperationException) {
            return
        }

        val planned = planner.plan(ConvertRequest(listOf(link.resolve("outside.svg")), root.resolve("out")))

        assertEquals("unsafe_symlink", planned.single().diagnostics.single().code)
    }

    @Test
    fun `planner rejects output through a symlinked ancestor without creating an external target`() {
        val root = Files.createTempDirectory("output-planner")
        val input = svg(root.resolve("icon.svg"))
        val external = Files.createTempDirectory("external-output")
        val link = root.resolve("out-link")
        try {
            Files.createSymbolicLink(link, external)
        } catch (_: UnsupportedOperationException) {
            return
        }

        val planned = planner.plan(ConvertRequest(listOf(input), link.resolve("nested")))

        assertEquals(1, planned.size)
        assertEquals("unsafe_symlink", planned.single().diagnostics.single().code)
        assertFalse(Files.exists(external.resolve("nested").resolve("icon.xml")))
    }

    @Test
    fun `atomic writer refuses existing output by default and replaces only with overwrite`() {
        val directory = Files.createTempDirectory("atomic-writer")
        val target = directory.resolve("icon.xml")
        target.writeText("old")
        val writer = AtomicFileWriter()

        val refused = writer.writeAtomically(target, "new".toByteArray(StandardCharsets.UTF_8), overwrite = false)
        val overwritten = writer.writeAtomically(target, "new".toByteArray(StandardCharsets.UTF_8), overwrite = true)

        assertFalse(refused.succeeded)
        assertEquals("output_exists", refused.diagnostics.single().code)
        assertTrue(overwritten.succeeded)
        assertEquals("new", Files.readString(target))
        assertTrue(Files.list(directory).use { it.noneMatch { path -> path.name.contains(".tmp") } })
    }

    @Test
    fun `atomic writer racing default writes publishes exactly one complete file`() {
        val directory = Files.createTempDirectory("atomic-writer")
        val target = directory.resolve("icon.xml")
        val writer = AtomicFileWriter()
        val payloads = listOf("first".repeat(1024), "second".repeat(1024)).map { it.toByteArray(StandardCharsets.UTF_8) }
        val executor = Executors.newFixedThreadPool(2)

        val results = try {
            executor.invokeAll(payloads.map { payload -> Callable { writer.writeAtomically(target, payload, overwrite = false) } })
                .map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it.succeeded })
        assertTrue(Files.readAllBytes(target).contentEquals(payloads[0]) || Files.readAllBytes(target).contentEquals(payloads[1]))
    }

    @Test
    fun `atomic writer reports a cleanup warning when pre-commit cleanup fails`() {
        val directory = Files.createTempDirectory("atomic-writer")
        val target = directory.resolve("icon.xml")
        val operations = object : FileOperations by NioFileOperations {
            override fun createLink(target: Path, existing: Path) = throw IOException("link failed")
            override fun deleteIfExists(path: Path): Boolean = throw IOException("cleanup failed")
        }

        val result = AtomicFileWriter(operations).writeAtomically(target, "content".toByteArray(StandardCharsets.UTF_8), overwrite = false)

        assertFalse(result.succeeded)
        assertTrue(result.diagnostics.any { it.code == "filesystem_error" })
        assertTrue(result.diagnostics.any { it.code == "temp_cleanup_failed" && it.severity == EngineDiagnosticSeverity.WARNING })
        assertFalse(Files.exists(target))
    }

    @Test
    fun `atomic writer cleans a temporary symbolic link rejected before writing`() {
        val directory = Files.createTempDirectory("atomic-writer")
        val target = directory.resolve("icon.xml")
        val external = Files.createTempDirectory("atomic-writer-external").resolve("sentinel").also { it.writeText("sentinel") }
        val probe = directory.resolve("symbolic-link-probe")
        val linksSupported = try {
            Files.createSymbolicLink(probe, external)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } finally {
            Files.deleteIfExists(probe)
        }
        assumeTrue(linksSupported, "symbolic links are not supported by this file system")
        var temporary: Path? = null
        var cleanupAttempted = false
        val operations = object : FileOperations by NioFileOperations {
            override fun createTempFile(directory: Path, prefix: String, suffix: String): Path {
                return NioFileOperations.createTempFile(directory, prefix, suffix).also { path ->
                    Files.delete(path)
                    Files.createSymbolicLink(path, external)
                    temporary = path
                }
            }

            override fun deleteIfExists(path: Path): Boolean {
                cleanupAttempted = true
                return NioFileOperations.deleteIfExists(path)
            }
        }

        val result = AtomicFileWriter(operations).writeAtomically(target, "content".toByteArray(StandardCharsets.UTF_8), overwrite = false)

        assertFalse(result.succeeded)
        assertEquals("unsafe_symlink", result.diagnostics.single().code)
        assertTrue(cleanupAttempted)
        assertFalse(Files.exists(requireNotNull(temporary), NOFOLLOW_LINKS))
        assertEquals("sentinel", Files.readString(external))
        assertFalse(Files.exists(target))
    }

    private fun svg(path: Path): Path = path.also {
        it.parent.createDirectories()
        it.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M1,1 L2,2\"/></svg>")
    }
}
