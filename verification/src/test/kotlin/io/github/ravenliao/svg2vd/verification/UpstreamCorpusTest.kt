package io.github.ravenliao.svg2vd.verification

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assumptions.assumeTrue
import io.github.ravenliao.svg2vd.upstream.CorpusAsset
import io.github.ravenliao.svg2vd.upstream.CorpusInputType
import io.github.ravenliao.svg2vd.upstream.CorpusManifest as MaterializedCorpusManifest
import io.github.ravenliao.svg2vd.upstream.CorpusRenderableCase
import io.github.ravenliao.svg2vd.upstream.UpstreamTag
import io.github.ravenliao.svg2vd.upstream.writeCorpusManifest
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpstreamCorpusTest {
    @Test
    fun `release jar renders every minimal corpus case`() {
        val corpus = CorpusConfiguration.fromProperties(System.getProperties().stringProperties())
        corpus.validate()

        corpus.manifest.renderableCases.forEach { case ->
            assertCorpusCase(corpus, case)
        }
    }

    @Test
    fun `fatal SVG exits with conversion failure and publishes no output`() {
        val corpus = CorpusConfiguration.fromProperties(System.getProperties().stringProperties())
        corpus.validate()
        val input = corpus.assetRoot.resolve("fatal.svg")
        assumeTrue(
            sha256(Files.readAllBytes(corpus.manifestPath)) == MINIMAL_MANIFEST_SHA,
            "fatal.svg is verified only against the known minimal corpus manifest",
        )
        assertTrue(Files.isRegularFile(input), "known minimal corpus must contain fatal.svg")
        val output = Files.createTempDirectory("svg2vd-fatal").resolve("output")

        val response = runJar(
            corpus.javaExecutable,
            corpus.jar,
            listOf("convert", "--input", input.toString(), "--output", output.toString()),
        )

        assertEquals(3, response.exitCode)
        assertEquals("", response.stderr)
        assertEquals("convert", response.result.command)
        assertEquals("partial_failure", response.result.outcome)
        assertTrue(!Files.exists(output) || Files.list(output).use { !it.findAny().isPresent })
    }

    @Test
    fun `rejects Java 17 executables`() {
        val java17 = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath()

        assertFailsWith<IllegalArgumentException> { requireJava11(java17) }
    }

    @Test
    fun `accepts the configured Java 11 executable`() {
        requireJava11(Path.of(requireNotNull(System.getProperty("svg2vd.javaExecutable"))))
    }

    @Test
    fun `rejects a missing Java executable`() {
        assertFailsWith<IllegalArgumentException> { requireJava11(Path.of("/missing/java")) }
    }

    @Test
    fun `requires every corpus task property`() {
        assertFailsWith<IllegalArgumentException> { CorpusConfiguration.fromProperties(emptyMap()) }
    }

    @Test
    fun `rejects a corpus asset whose hash differs from its manifest`() {
        val manifest = MaterializedCorpusManifest(
            schemaVersion = 1,
            tag = UpstreamTag("studio-2026.1.2", "1".repeat(40), "2".repeat(40)),
            license = "Apache-2.0",
            assets = listOf(CorpusAsset("icon.svg", "3".repeat(40), sha256("<svg/>".toByteArray()))),
            renderableCases = emptyList(),
            unpairedAssets = emptyList(),
        )
        val path = writeCorpusManifest(manifest, Files.createTempDirectory("svg2vd-invalid-corpus"), mapOf("icon.svg" to "<svg/>".toByteArray()))
        path.parent.resolve("assets/icon.svg").writeText("tampered")

        val failure = assertFailsWith<IllegalArgumentException> { readCorpusManifest(path, path.parent) }

        assertTrue(failure.message.orEmpty().contains("asset hash"))
    }

    @Test
    fun `rejects a missing release jar`() {
        assertFailsWith<IllegalArgumentException> { requireReleaseJar(Path.of("/missing/svg2vd.jar")) }
    }

    @Test
    fun `rejects manifests without renderable cases`() {
        val bytes = "<svg/>".toByteArray()
        val manifest = MaterializedCorpusManifest(
            schemaVersion = 1,
            tag = UpstreamTag("studio-2026.1.2", "1".repeat(40), "2".repeat(40)),
            license = "Apache-2.0",
            assets = listOf(CorpusAsset("icon.svg", "3".repeat(40), sha256(bytes))),
            renderableCases = emptyList(),
            unpairedAssets = emptyList(),
        )
        val path = writeCorpusManifest(manifest, Files.createTempDirectory("svg2vd-empty-corpus"), mapOf("icon.svg" to bytes))

        val failure = assertFailsWith<IllegalArgumentException> { readCorpusManifest(path, path.parent) }

        assertTrue(failure.message.orEmpty().contains("no unique renderable cases"))
    }

    @Test
    fun `accepts Task 4 materialization with no fatal cases`() {
        val output = Files.createTempDirectory("svg2vd-task4-materialization")
        val xml = resource("plain.xml")
        val golden = resource("plain-xml.png")
        val assets = listOf(
            CorpusAsset("plain.xml", "1".repeat(40), sha256(xml)),
            CorpusAsset("plain-xml.png", "2".repeat(40), sha256(golden)),
        )
        val materialized = writeCorpusManifest(
            MaterializedCorpusManifest(
                schemaVersion = 1,
                tag = UpstreamTag("studio-2026.1.2", "3".repeat(40), "4".repeat(40)),
                license = "Apache-2.0",
                assets = assets,
                renderableCases = listOf(
                    CorpusRenderableCase("plain-xml", "plain.xml", CorpusInputType.XML, "plain-xml.png", 32, 32, 32),
                ),
                unpairedAssets = emptyList(),
            ),
            output,
            mapOf("plain.xml" to xml, "plain-xml.png" to golden),
        )

        val parsed = readCorpusManifest(materialized, materialized.parent)

        assertEquals(1, parsed.renderableCases.size)
        val configured = CorpusConfiguration.fromProperties(System.getProperties().stringProperties())
        val materializedCorpus = CorpusConfiguration(
            manifestPath = materialized,
            root = materialized.parent,
            jar = configured.jar,
            javaExecutable = configured.javaExecutable,
            artifactRoot = configured.artifactRoot,
            manifest = parsed,
        )
        materializedCorpus.validate()
        assertCorpusCase(materializedCorpus, parsed.renderableCases.single())
    }

    @Test
    fun `rejects a materialization root whose name is not its manifest hash`() {
        val output = Files.createTempDirectory("svg2vd-task4-tamper")
        val xml = resource("plain.xml")
        val golden = resource("plain-xml.png")
        val assets = listOf(
            CorpusAsset("plain.xml", "1".repeat(40), sha256(xml)),
            CorpusAsset("plain-xml.png", "2".repeat(40), sha256(golden)),
        )
        val materialized = writeCorpusManifest(
            MaterializedCorpusManifest(1, UpstreamTag("studio-2026.1.2", "3".repeat(40), "4".repeat(40)), "Apache-2.0", assets, listOf(CorpusRenderableCase("plain-xml", "plain.xml", CorpusInputType.XML, "plain-xml.png", 32, 32, 32)), emptyList()),
            output,
            mapOf("plain.xml" to xml, "plain-xml.png" to golden),
        )
        val relocatedRoot = materialized.parent.resolveSibling("not-the-manifest-hash")
        Files.move(materialized.parent, relocatedRoot)

        val failure = assertFailsWith<IllegalArgumentException> {
            readCorpusManifest(relocatedRoot.resolve("manifest.json"), relocatedRoot)
        }

        assertTrue(failure.message.orEmpty().contains("materialization root"))
    }

    @Test
    fun `writes a delta artifact when a visual comparison fails`() {
        val root = Files.createTempDirectory("svg2vd-delta")
        val artifact = root.resolve("artifacts")
        val golden = image(1, 1, 0xff000000.toInt())
        val actual = image(1, 1, 0xffffffff.toInt())

        val failure = assertFailsWith<ImageComparisonFailure> {
            assertImageSimilar("delta", golden, actual, maxPercentDifferent = 0.0)
        }
        val actualPng = root.resolve("actual.png")
        check(ImageIO.write(actual, "PNG", actualPng.toFile()))
        artifact.resolve("delta").also(Files::createDirectories).resolve("stale.txt").writeText("stale")
        writeFailureArtifacts(artifact, "delta", "{}", root.resolve("svg2vd.jar").also { it.writeText("release jar") }, "render stdout", "render stderr", null, actualPng, failure.comparison.deltaPng)

        assertTrue(Files.isRegularFile(artifact.resolve("delta/delta.png")))
        assertTrue(Files.isRegularFile(artifact.resolve("delta/actual.png")))
        assertTrue(!Files.exists(artifact.resolve("delta/stale.txt")))
    }

    private fun assertCorpusCase(corpus: CorpusConfiguration, case: CorpusCase) {
        val caseRoot = Files.createTempDirectory("svg2vd-corpus-${case.id}")
        val input = corpus.assetRoot.resolve(case.inputPath)
        val actualXml = caseRoot.resolve("actual.xml")
        val actualPng = caseRoot.resolve("actual.png")
        val responses = mutableListOf<Pair<String, JarResponse>>()
        fun invoke(name: String, arguments: List<String>): JarResponse = runJar(corpus.javaExecutable, corpus.jar, arguments).also {
            responses += name to it
        }
        try {
            if (case.inputType == "svg") {
                val convertOutput = caseRoot.resolve("converted")
                val convert = invoke(
                    "convert",
                    listOf("convert", "--input", input.toString(), "--output", convertOutput.toString()),
                )
                check(convert.exitCode == 0) { "conversion failed for ${case.id}: ${convert.stdout}" }
                check(convert.stderr.isEmpty() && convert.result.command == "convert" && convert.result.outcome == "success") {
                    "conversion did not return a successful JSON response for ${case.id}"
                }
                val generated = Files.list(convertOutput).use { files ->
                    files.findFirst().orElseThrow { IllegalStateException("conversion published no XML for ${case.id}") }
                }
                check(Files.isRegularFile(generated)) { "conversion did not publish XML for ${case.id}" }
                if (case.id == "warning-svg") {
                    check(convert.stdout.contains("engine_warning")) { "warning SVG did not preserve conversion diagnostics" }
                }
                Files.copy(generated, actualXml)
            }

            val renderedInput = if (case.inputType == "svg") actualXml else input
            val render = invoke(
                "render",
                listOf("render", "--input", renderedInput.toString(), "--output", actualPng.toString(), "--size", case.renderSize.toString()),
            )
            check(render.exitCode == 0) { "render failed for ${case.id}: ${render.stdout}" }
            check(render.stderr.isEmpty() && render.result.command == "render" && render.result.outcome == "success") {
                "render did not return a successful JSON response for ${case.id}"
            }
            val golden = readPng(corpus.assetRoot.resolve(case.goldenPngPath))
            val actual = readPng(actualPng)
            assertImageSimilar(case.id, golden, actual)
            writeSuccessArtifacts(
                corpus.artifactRoot,
                case.id,
                Files.readString(corpus.manifestPath),
                corpus.jar,
                responses,
                actualXml.takeIf(Files::isRegularFile),
                actualPng,
            )
        } catch (failure: Throwable) {
            writeFailureArtifacts(
                corpus.artifactRoot,
                case.id,
                Files.readString(corpus.manifestPath),
                corpus.jar,
                responses.joinToString("\n") { (name, response) -> "$name stdout:\n${response.stdout}" },
                responses.joinToString("\n") { (name, response) -> "$name stderr:\n${response.stderr}" },
                actualXml.takeIf(Files::exists),
                actualPng.takeIf(Files::exists),
                (failure as? ImageComparisonFailure)?.comparison?.deltaPng,
            )
            throw failure
        }
    }
}

private fun java.util.Properties.stringProperties(): Map<String, String> = stringPropertyNames().associateWith(::getProperty)

private fun image(width: Int, height: Int, vararg pixels: Int): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
        require(pixels.size == width * height)
        pixels.forEachIndexed { index, pixel -> image.setRGB(index % width, index / width, pixel) }
    }

private fun resource(name: String): ByteArray = requireNotNull(
    UpstreamCorpusTest::class.java.getResourceAsStream("/corpus/minimal/$MINIMAL_MANIFEST_SHA/assets/$name"),
).use { it.readBytes() }

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private const val MINIMAL_MANIFEST_SHA = "bfa9407b849c8019249e137b3384b4f987a47a3e00bb45e4b3207edf64938acf"
