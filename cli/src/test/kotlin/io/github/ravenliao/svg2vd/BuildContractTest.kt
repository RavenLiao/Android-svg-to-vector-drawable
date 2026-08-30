package io.github.ravenliao.svg2vd

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.util.jar.JarFile
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.constructor.StandardConstructor
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class BuildContractTest {
    @Test
    fun `fat jar has no class newer than Java 11`() {
        val jar = findShadowJar()

        JarFile(jar.toFile()).use { archive ->
            val majors = archive.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { entry ->
                    archive.getInputStream(entry).use { input ->
                        val header = input.readNBytes(8)
                        require(header.copyOfRange(0, 4).contentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())))
                        ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
                    }
                }
                .toList()
            assertTrue(majors.isNotEmpty(), "Shadow JAR contains no class files")
            assertTrue(majors.all { it <= 55 }, "Found class major newer than Java 11: ${majors.maxOrNull()}")
        }
    }

    @Test
    fun `fat jar includes the engine adapter and synchronized Svg2Vector classes`() {
        JarFile(findShadowJar().toFile()).use { archive ->
            assertTrue(archive.getEntry("io/github/ravenliao/svg2vd/engine/UpstreamEngineAdapter.class") != null)
            assertTrue(archive.getEntry("io/github/ravenliao/svg2vd/engine/AtomicFileWriter.class") != null)
            assertTrue(archive.getEntry("com/android/ide/common/vectordrawable/Svg2Vector.class") != null)
        }
    }

    @Test
    fun `Kotlin rejects Java 12 API under JDK release 11`() {
        assertFailsWith<CompilationFailedException> {
            compileFixture("fun probe() = java.lang.String(\"x\").indent(2)")
        }
    }

    @Test
    fun `Kotlin accepts Java 11 API under JDK release 11`() {
        assertDoesNotThrow {
            compileFixture("import java.nio.file.Files\nimport java.nio.file.Path\nfun probe(path: Path) = Files.readString(path)")
        }
    }

    @Test
    fun `fixture compiler uses a complete Kotlin home`() {
        val kotlinHome = createKotlinHome()
        val stdlib = kotlinHome.resolve("lib/kotlin-stdlib.jar")
        val reflect = kotlinHome.resolve("lib/kotlin-reflect.jar")
        assertTrue(Files.isRegularFile(stdlib))
        assertTrue(Files.isRegularFile(kotlinHome.resolve("lib/kotlin-script-runtime.jar")))
        assertTrue(Files.isRegularFile(reflect))
        JarFile(reflect.toFile()).use { archive ->
            assertTrue(
                archive.getEntry("kotlin/reflect/full/KClasses.class") != null,
                "kotlin-reflect.jar must contain reflect API classes"
            )
        }
    }

    @Test
    fun `every module records resolved dependencies in its lockfile`() {
        val projectRoot = Path.of(requireNotNull(System.getProperty("svg2vd.projectRoot")))
        listOf("cli", "engine", "upstream-sync", "verification").forEach { module ->
            val lockfile = projectRoot.resolve(module).resolve("gradle.lockfile")
            assertTrue(Files.exists(lockfile), "Missing lockfile: $lockfile")
            assertTrue(
                Files.readAllLines(lockfile).any {
                    it.isNotBlank() && !it.startsWith("#") && !it.startsWith("empty=")
                },
                "Lockfile has no resolved dependencies: $lockfile"
            )
        }
    }

    @Test
    fun `tests run on a Java 17 or newer build JDK`() {
        assertTrue(Runtime.version().feature() >= 17)
    }

    @Test
    fun `CI workflows pin actions use the two Java runtimes and never publish`() {
        val projectRoot = Path.of(requireNotNull(System.getProperty("svg2vd.projectRoot")))
        val ci = workflow(projectRoot.resolve(".github/workflows/ci.yml"))
        val upstream = workflow(projectRoot.resolve(".github/workflows/upstream-corpus.yml"))
        val release = workflow(projectRoot.resolve(".github/workflows/release.yml"))
        val update = workflow(projectRoot.resolve(".github/workflows/upstream-update.yml"))

        assertPinnedActions(ci)
        assertPinnedActions(upstream)
        assertPinnedActions(release)
        assertPinnedActions(update)
        assertGradleRunsUseBuildJdk(ci)
        assertGradleRunsUseBuildJdk(upstream)
        assertReusableWorkflowConsumesCandidateArtifact(upstream)
        assertLinuxFullCorpusKeepsJava11Executable(ci)
        val ciText = Files.readString(projectRoot.resolve(".github/workflows/ci.yml"))
        assertTrue(Regex("""java-version:\s*['"]17['"]""").containsMatchIn(ciText))
        assertTrue("javaExecutable" in ciText)
        assertTrue("java.exe" in ciText)
        assertTrue("corpus.lock.json" in ciText)
        assertTrue("discoverLockedCandidate" in ciText)
        assertTrue("syncCorpus" in ciText)
        assertTrue("corpusTest" in ciText)
        assertFalse("JAVA_TOOL_OPTIONS" in ciText, "workflow-level JAVA_TOOL_OPTIONS pollutes CLI stderr")
        assertFalse(Regex("(?i)\\b(release|publish|upload-to-maven)\\b").containsMatchIn(ciText))
        assertFalse(ciText.contains("contents: write"))
        assertFalse(ciText.contains("packages: write"))
        assertFalse(ciText.contains("svg2vd-0.1.0-all.jar"), "CI must derive the JAR path from svg2vdVersion")

        val upstreamText = Files.readString(projectRoot.resolve(".github/workflows/upstream-corpus.yml"))
        assertTrue((upstream["on"] as? Map<*, *>)?.containsKey("workflow_call") == true)
        assertTrue("inputs.candidate_artifact" in upstreamText)
        assertTrue("RUNNER_TEMP" in upstreamText)
        assertTrue("corpus_manifest_sha256" in upstreamText)
        assertTrue("corpusTest" in upstreamText)
        assertFalse("JAVA_TOOL_OPTIONS" in upstreamText, "workflow-level JAVA_TOOL_OPTIONS pollutes CLI stderr")
        assertFalse(upstreamText.contains("svg2vd-0.1.0-all.jar"), "Reusable corpus gate must derive the JAR path from svg2vdVersion")

        val releaseText = Files.readString(projectRoot.resolve(".github/workflows/release.yml"))
        assertTrue((release["on"] as? Map<*, *>)?.containsKey("workflow_run") == true)
        assertTrue(releaseText.contains("types: [completed]"))
        assertTrue(releaseText.contains("workflow_run.head_sha"))
        assertFalse(releaseText.contains("GITHUB_SHA"), "Release must use workflow_run.head_sha for provenance")
        assertTrue(releaseText.contains("released_tag_count"))
        assertTrue(releaseText.contains("git config user.name"), "Release must configure an identity before creating an annotated tag")

        val updateText = Files.readString(projectRoot.resolve(".github/workflows/upstream-update.yml"))
        assertTrue(updateText.contains("bumpToolPatchVersion"))
        assertTrue(updateText.contains("match-head-commit"))
        assertTrue(updateText.contains("corpus.lock.json|gradle.properties"))
    }

    @Test
    fun `CLI runtime and shadow graphs exclude verification`() {
        val projectRoot = Path.of(requireNotNull(System.getProperty("svg2vd.projectRoot")))
        val buildFile = Files.readString(projectRoot.resolve("cli/build.gradle.kts"))
        assertFalse(Regex("(?:implementation|runtimeOnly|shadow)\\s*\\(\\s*project\\(\\\":verification\\\"\\)").containsMatchIn(buildFile))
        assertFalse(Regex("(?m)^.*verification.*(?:shadow|runtimeClasspath).*$").containsMatchIn(buildFile))
        JarFile(findShadowJar().toFile()).use { archive ->
            assertFalse(
                archive.entries().asSequence().any { entry -> entry.name.startsWith("io/github/ravenliao/svg2vd/verification/") },
                "Shadow JAR must not contain verification runner classes",
            )
        }
    }

    private fun workflow(path: Path): Map<*, *> {
        assertTrue(Files.isRegularFile(path), "Missing workflow: $path")
        val settings = LoadSettings.builder().setLabel(path.toString()).build()
        return requireNotNull(Load(settings, StandardConstructor(settings)).loadFromString(Files.readString(path)) as? Map<*, *>)
    }

    private fun assertPinnedActions(workflow: Map<*, *>) {
        fun visit(value: Any?) {
            when (value) {
                is Map<*, *> -> value.forEach { (key, child) ->
                    if (key == "uses") {
                        assertTrue(
                            child is String && child.matches(Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+@[0-9a-f]{40}")),
                            "Action is not pinned to a full commit SHA: $child",
                        )
                    }
                    visit(child)
                }
                is Iterable<*> -> value.forEach(::visit)
            }
        }
        visit(workflow)
    }

    private fun assertGradleRunsUseBuildJdk(workflow: Map<*, *>) {
        workflowNodes(workflow)
            .mapNotNull { it["run"] as? String }
            .filter { "./gradlew" in it }
            .forEach { run ->
                assertTrue(
                    "JAVA_HOME=\"${'$'}{{ steps.build-java.outputs.path }}\" ./gradlew" in run,
                    "Gradle must be forced to the build-java JDK after Java 11 setup: $run",
                )
            }
    }

    private fun assertReusableWorkflowConsumesCandidateArtifact(workflow: Map<*, *>) {
        val inputs = ((workflow["on"] as? Map<*, *>)?.get("workflow_call") as? Map<*, *>)?.get("inputs") as? Map<*, *>
        val candidateInput = inputs?.get("candidate_artifact") as? Map<*, *>
        assertTrue(candidateInput?.get("required") == true)
        val jobs = workflow["jobs"] as? Map<*, *>
        assertFalse(jobs?.containsKey("discovery") == true, "Reusable gate must consume, not rediscover, the candidate")
        val text = workflowNodes(workflow).joinToString("\n") { it["run"] as? String ?: "" }
        assertFalse("discoverLockedCandidate" in text)
        assertTrue("syncCorpus" in text)
        assertTrue("corpus.lock.json" in text)
        assertTrue("corpus_manifest_sha256" in text)
        val downloads = workflowNodes(workflow).filter { it["uses"] == "actions/download-artifact@d3f86a106a0bac45b974a628896c90dbdf5c8093" }
        assertTrue(downloads.any { (it["with"] as? Map<*, *>)?.get("name") == "${'$'}{{ inputs.candidate_artifact }}" })
    }

    private fun assertLinuxFullCorpusKeepsJava11Executable(workflow: Map<*, *>) {
        val fullStep = workflowNodes(workflow).single { it["name"] == "Materialize and verify the locked Linux corpus" }
        val environment = fullStep["env"] as? Map<*, *>
        assertTrue(environment?.get("JAVA_11_EXECUTABLE") == "${'$'}{{ steps.java11.outputs.path }}/bin/${'$'}{{ matrix.java_executable }}")
    }

    private fun workflowNodes(value: Any?): List<Map<*, *>> = when (value) {
        is Map<*, *> -> listOf(value) + value.values.flatMap(::workflowNodes)
        is Iterable<*> -> value.flatMap(::workflowNodes)
        else -> emptyList()
    }

    private fun compileFixture(source: String) {
        val directory = createTempDirectory("svg2vd-kotlin-fixture")
        val sourceFile = directory.resolve("Fixture.kt").also { it.writeText(source) }
        val output = directory.resolve("classes")
        Files.createDirectories(output)
        val diagnostics = ByteArrayOutputStream()
        val result = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            java.io.PrintStream(diagnostics),
            "-Xjdk-release=11",
            "-kotlin-home", createKotlinHome().toString(),
            "-no-stdlib",
            "-no-reflect",
            "-d", output.toString(),
            sourceFile.toString()
        )
        if (result != org.jetbrains.kotlin.cli.common.ExitCode.OK) {
            throw CompilationFailedException("Kotlin fixture compilation failed with $result")
        }
    }

    private fun findShadowJar(): Path = Path.of(
        requireNotNull(System.getProperty("svg2vd.shadowJar")) {
            "Missing exact Shadow JAR path"
        }
    ).also { jar -> assertTrue(Files.isRegularFile(jar), "Shadow JAR was not produced: $jar") }

    private fun createKotlinHome(): Path {
        val kotlinHome = createTempDirectory("svg2vd-kotlin-home")
        val libDirectory = Files.createDirectories(kotlinHome.resolve("lib"))
        copyRuntimeJar(kotlin.Unit::class.java, libDirectory.resolve("kotlin-stdlib.jar"))
        copyRuntimeJar(
            Class.forName("kotlin.script.dependencies.ScriptContents"),
            libDirectory.resolve("kotlin-script-runtime.jar")
        )
        copyRuntimeJar(
            Class.forName("kotlin.reflect.full.KClasses"),
            libDirectory.resolve("kotlin-reflect.jar")
        )
        return kotlinHome
    }

    private fun copyRuntimeJar(type: Class<*>, target: Path) {
        val source = Path.of(type.protectionDomain.codeSource.location.toURI())
        Files.copy(source, target)
    }

    private class CompilationFailedException(message: String) : RuntimeException(message)
}
