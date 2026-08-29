package io.github.ravenliao.svg2vd.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPOutputStream
import kotlin.io.path.exists
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorpusSynchronizerTest {
    @Test
    fun `corpus client uses the shared Gitiles transport contract for blob reads`() {
        var seenUri: java.net.URI? = null
        var seenTimeout: Duration? = null
        val client = GitilesCorpusClient(
            transport = GitilesBytesTransport { uri, timeout ->
                seenUri = uri
                seenTimeout = timeout
                GitilesBytesResponse(200, gzipTar(mapOf("icon.svg" to "<svg/>".toByteArray())))
            },
            timeout = Duration.ofSeconds(7),
        )

        assertEquals("<svg/>", client.blob(COMMIT, "icon.svg").toString(Charsets.UTF_8))
        assertEquals(gitilesArchiveUri(COMMIT, CORPUS_PATH), seenUri)
        assertEquals(Duration.ofSeconds(7), seenTimeout)
    }

    @Test
    fun `corpus client retries transient Gitiles failures`() {
        var attempts = 0
        val client = GitilesCorpusClient(
            transport = GitilesBytesTransport { _, _ ->
                attempts += 1
                if (attempts < 3) GitilesBytesResponse(503, byteArrayOf())
                else GitilesBytesResponse(200, gzipTar(mapOf("icon.svg" to "<svg/>".toByteArray())))
            },
            retryDelay = Duration.ZERO,
        )

        assertEquals("<svg/>", client.blob(COMMIT, "icon.svg").toString(Charsets.UTF_8))
        assertEquals(3, attempts)
    }

    @Test
    fun `corpus client reads a fixed corpus tree from one Gitiles archive`() {
        val svg = "<svg/>".toByteArray()
        val expectedTree = gitilesObjectUri(COMMIT, CORPUS_PATH)
        val expectedArchive = gitilesArchiveUri(COMMIT, CORPUS_PATH)
        val calls = mutableListOf<java.net.URI>()
        val client = GitilesCorpusClient(
            transport = GitilesBytesTransport { uri, _ ->
                calls += uri
                when (uri) {
                    expectedTree -> GitilesBytesResponse(
                        200,
                        ")]}'\n{\"entries\":[{\"name\":\"icon.svg\",\"type\":\"blob\",\"id\":\"${objectId("svg")}\"}]}".toByteArray(),
                    )
                    expectedArchive -> GitilesBytesResponse(200, gzipTar(mapOf("icon.svg" to svg)))
                    else -> error("unexpected Gitiles request: $uri")
                }
            },
        )

        assertEquals(listOf(CorpusTreeEntry("icon.svg", objectId("svg"))), client.tree(COMMIT, CORPUS_PATH))
        assertEquals(svg.toList(), client.blob(COMMIT, "icon.svg").toList())
        assertEquals(svg.toList(), client.blob(COMMIT, "icon.svg").toList())
        assertEquals(listOf(expectedTree, expectedArchive), calls)
    }

    @Test
    fun `corpus client ignores Gitiles PAX metadata before reading an archive blob`() {
        val svg = "<svg/>".toByteArray()
        val expectedArchive = gitilesArchiveUri(COMMIT, CORPUS_PATH)
        val client = GitilesCorpusClient(
            transport = GitilesBytesTransport { uri, _ ->
                assertEquals(expectedArchive, uri)
                GitilesBytesResponse(
                    200,
                    gzipTarEntries(
                        listOf(
                            TarEntry("./PaxHeaders.X/icon.svg", 'x', "22 mtime=0\n".toByteArray()),
                            TarEntry("icon.svg", '0', svg),
                        ),
                    ),
                )
            },
        )

        assertEquals(svg.toList(), client.blob(COMMIT, "icon.svg").toList())
    }

    @Test
    fun `synchronizer pairs only uniquely named static SVG XML and PNG assets`() {
        val source = FakeCorpusSource(
            mapOf(
                "alpha.svg" to "<svg/>",
                "alpha.png" to png(16, 16),
                "beta.xml" to "<vector/>",
                "beta.png" to png(32, 64),
                "orphan.png" to png(64, 64),
                "lonely.svg" to "<svg/>",
                "zeta.xml" to "<vector/>",
            ),
        )
        val destination = Files.createTempDirectory("corpus-output")

        val manifest = CorpusSynchronizer(source).synchronizeCorpus(candidateManifest(), destination)

        val bytes = Files.readAllBytes(manifest)
        assertEquals(manifest.parent.fileName.toString(), sha256Hex(bytes))
        assertTrue(bytes.toString(Charsets.UTF_8).endsWith("\n"))
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals(TAG_OBJECT, root.getValue("tag").jsonObject.getValue("tag_object").jsonPrimitive.content)
        assertEquals(COMMIT, root.getValue("tag").jsonObject.getValue("peeled_commit").jsonPrimitive.content)
        assertEquals("Apache-2.0", root.getValue("license").jsonPrimitive.content)
        val cases = root.getValue("renderable_cases").jsonArray.map { it.jsonObject }
        assertEquals(listOf("alpha", "beta"), cases.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf(16, 64), cases.map { it.getValue("render_size").jsonPrimitive.content.toInt() })
        assertEquals(listOf("svg", "xml"), cases.map { it.getValue("input_type").jsonPrimitive.content })
        assertEquals(
            listOf("lonely.svg:no_matching_png", "orphan.png:orphan_png", "zeta.xml:no_matching_png"),
            root.getValue("unpaired_assets").jsonArray.map { item ->
                item.jsonObject.let { "${it.getValue("path").jsonPrimitive.content}:${it.getValue("reason").jsonPrimitive.content}" }
            },
        )
        assertEquals(png(16, 16).toList(), Files.readAllBytes(manifest.parent.resolve("assets/alpha.png")).toList())
    }

    @Test
    fun `synchronizer rejects ambiguous unsafe and invalid static asset trees`() {
        val invalidSources: List<Pair<String, Map<String, Any>>> = listOf(
            "duplicate basename" to mapOf("a/icon.svg" to "<svg/>", "b/icon.png" to png(16, 16), "c/icon.png" to png(16, 16)),
            "case folding collision" to mapOf("Icon.svg" to "<svg/>", "icon.png" to png(16, 16), "ICON.PNG" to png(16, 16)),
            "svg xml input collision" to mapOf("icon.svg" to "<svg/>", "icon.xml" to "<vector/>", "icon.png" to png(16, 16)),
            "unknown extension" to mapOf("icon.svg" to "<svg/>", "icon.png" to png(16, 16), "notes.txt" to "no"),
            "undecodable png" to mapOf("icon.svg" to "<svg/>", "icon.png" to "not a PNG"),
            "nonpositive png dimension" to mapOf("icon.svg" to "<svg/>", "icon.png" to zeroWidthPng()),
            "undecodable orphan png" to mapOf("icon.svg" to "<svg/>", "orphan.png" to "not a PNG"),
        )
        invalidSources.forEach { (name, files) ->
            val failure = assertFailsWith<CorpusSynchronizationException>(name) {
                CorpusSynchronizer(FakeCorpusSource(files)).synchronizeCorpus(candidateManifest(), Files.createTempDirectory("corpus-output"))
            }
            assertTrue(failure.message.orEmpty().isNotBlank())
        }
        val traversal = FakeCorpusSource(mapOf("icon.svg" to "<svg/>", "icon.png" to png(1, 1))).also {
            it.treeEntries = listOf(CorpusTreeEntry("../escape.png", objectId("escape")))
        }
        assertFailsWith<CorpusSynchronizationException> {
            CorpusSynchronizer(traversal).synchronizeCorpus(candidateManifest(), Files.createTempDirectory("corpus-output"))
        }
        val unsafeCandidate = writeCandidateManifest(
            CandidateManifest(1, 1, "scope", UpstreamTag("studio-2026.1.2", TAG_OBJECT, COMMIT), fingerprint("scope", listOf(ScopeEntry("../escape", objectId("escape"))), "runtime").sha256, listOf(ScopeEntry("../escape", objectId("escape"))), listOf(SourceArchive("../escape", objectId("escape"), "0".repeat(64))), "runtime", "locks"),
            Files.createTempDirectory("candidate"),
        )
        assertFailsWith<CorpusSynchronizationException> {
            CorpusSynchronizer(FakeCorpusSource(mapOf("icon.svg" to "<svg/>", "icon.png" to png(1, 1)))).synchronizeCorpus(unsafeCandidate, Files.createTempDirectory("corpus-output"))
        }
    }

    @Test
    fun `synchronizer rejects tree identity manifest identity and nonpositive PNG dimensions`() {
        val wrongObject = FakeCorpusSource(mapOf("icon.svg" to "<svg/>", "icon.png" to png(16, 16))).also {
            it.treeEntries = it.treeEntries.map { entry -> entry.copy(objectId = objectId("different-${entry.path}")) }
        }
        assertFailsWith<CorpusSynchronizationException> {
            CorpusSynchronizer(wrongObject).synchronizeCorpus(candidateManifest(), Files.createTempDirectory("corpus-output"))
        }

        val wrongHash = FakeCorpusSource(mapOf("icon.svg" to "<svg/>", "icon.png" to png(16, 16))).also {
            it.blobBytes["icon.png"] = png(32, 32)
        }
        assertFailsWith<CorpusSynchronizationException> {
            CorpusSynchronizer(wrongHash).synchronizeCorpus(candidateManifest(), Files.createTempDirectory("corpus-output"))
        }
    }

    @Test
    fun `canonical corpus lock creates a candidate without refs and candidate feeds synchronizer`() {
        val corpusSource = FakeCorpusSource(mapOf("icon.svg" to "<svg/>", "icon.png" to png(64, 64)))
        val lockDirectory = Files.createTempDirectory("corpus-lock")
        val expectedManifest = CorpusSynchronizer(corpusSource).synchronizeCorpus(candidateManifest(), Files.createTempDirectory("expected-corpus"))
        val input = CorpusAsset("icon.svg", corpusSource.objectIdAt(COMMIT, "icon.svg"), sha256Hex(corpusSource.blob(COMMIT, "icon.svg")))
        val golden = CorpusAsset("icon.png", corpusSource.objectIdAt(COMMIT, "icon.png"), sha256Hex(corpusSource.blob(COMMIT, "icon.png")))
        val lock = writeCorpusLock(
            CorpusLock(
                schemaVersion = 1,
                tag = UpstreamTag("studio-2026.1.2", TAG_OBJECT, COMMIT),
                corpusManifestSha256 = sha256Hex(Files.readAllBytes(expectedManifest)),
                assets = listOf(input, golden),
                renderableCases = listOf(CorpusLockedCase("icon", input, CorpusInputType.SVG, golden, 64, 64, 64)),
            ),
            lockDirectory,
        )
        val fixedLock = lockDirectory.resolve("corpus.lock.json")
        Files.move(lock, fixedLock)
        val scope = UpstreamScope(1, listOf("scope"), KNOWN_LEGACY_TAGS)
        val candidateSource = FakeCandidateSource(mapOf("scope" to "source".toByteArray()))
        val candidates = Files.createTempDirectory("locked-candidates")

        val readLock = readCorpusLock(fixedLock)
        assertEquals("icon.svg", readLock.renderableCases.single().input.path)
        assertEquals("icon.png", readLock.renderableCases.single().golden.path)
        assertEquals(64, readLock.renderableCases.single().renderSize)
        val candidate = LockedCandidateDiscoverer(scope, candidateSource, "runtime", "locks").discoverLockedCandidate(fixedLock, candidates)
        val corpus = CorpusSynchronizer(corpusSource).synchronizeCorpus(candidate, Files.createTempDirectory("corpus-output"))

        assertFalse(candidateSource.refsWereRead)
        assertEquals(TAG_OBJECT, readCandidateManifest(candidate).tag.tagObject)
        assertEquals(COMMIT, readCandidateManifest(candidate).tag.peeledCommit)
        assertTrue(Files.isRegularFile(candidate.resolveSibling("${candidate.fileName.toString().removeSuffix(".json")}.materials/${candidateSource.objectIdAt(COMMIT, "scope")}-${sha256Hex("source".toByteArray())}")))
        assertTrue(Files.isRegularFile(corpus))
        assertEquals(readLock.corpusManifestSha256, sha256Hex(Files.readAllBytes(corpus)))
    }

    @Test
    fun `repository corpus lock is canonical and records the complete paired asset contract`() {
        val projectRoot = Path.of("").toAbsolutePath().normalize().parent
        val repositoryLock = projectRoot.resolve("corpus.lock.json")

        assertTrue(repositoryLock.exists(), "A materialized upstream corpus lock is required: $repositoryLock")
        val bytes = Files.readAllBytes(repositoryLock)
        assertTrue(bytes.isNotEmpty())
        assertFalse(bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())))
        assertFalse(bytes.contains('\r'.code.toByte()))
        assertTrue(bytes.last() == '\n'.code.toByte())

        val lock = readCorpusLock(repositoryLock)
        assertEquals("studio-2026.1.2", lock.tag.name)
        assertTrue(lock.tag.tagObject.matches(Regex("[0-9a-f]{40}")))
        assertTrue(lock.tag.peeledCommit.matches(Regex("[0-9a-f]{40}")))
        assertTrue(lock.corpusManifestSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(lock.assets.isNotEmpty())
        assertTrue(lock.renderableCases.isNotEmpty())
        lock.renderableCases.forEach { case ->
            assertTrue(lock.assets.contains(case.input), "Missing input asset for ${case.id}")
            assertTrue(lock.assets.contains(case.golden), "Missing golden asset for ${case.id}")
            assertTrue(case.renderSize == maxOf(case.goldenWidth, case.goldenHeight))
        }
    }

    @Test
    fun `writer creates a fixed canonical lock from a validated materialized manifest`() {
        val source = FakeCorpusSource(mapOf("icon.svg" to "<svg/>", "icon.png" to png(16, 24)))
        val manifest = CorpusSynchronizer(source).synchronizeCorpus(candidateManifest(), Files.createTempDirectory("materialized-corpus"))
        val target = Files.createTempDirectory("corpus-lock-output").resolve("corpus.lock.json")

        assertEquals(target.toAbsolutePath().normalize(), writeCorpusLockFromManifest(manifest, target))
        assertEquals(target.toAbsolutePath().normalize(), writeCorpusLockFromManifest(manifest, target))

        val lock = readCorpusLock(target)
        assertEquals(sha256Hex(Files.readAllBytes(manifest)), lock.corpusManifestSha256)
        assertEquals(listOf("icon.png", "icon.svg"), lock.assets.map(CorpusAsset::path))
        assertEquals("icon.svg", lock.renderableCases.single().input.path)
        assertEquals("icon.png", lock.renderableCases.single().golden.path)
        assertEquals(24, lock.renderableCases.single().renderSize)
    }

    @Test
    fun `writer rejects a fabricated blob object ID in an otherwise valid materialization`() {
        val source = FakeCorpusSource(mapOf("icon.svg" to "<svg/>", "icon.png" to png(16, 16)))
        val svg = source.blob(COMMIT, "icon.svg")
        val golden = source.blob(COMMIT, "icon.png")
        val manifest = writeCorpusManifest(
            CorpusManifest(
                schemaVersion = 1,
                tag = UpstreamTag("studio-2026.1.2", TAG_OBJECT, COMMIT),
                license = "Apache-2.0",
                assets = listOf(
                    CorpusAsset("icon.svg", "f".repeat(40), sha256Hex(svg)),
                    CorpusAsset("icon.png", source.objectIdAt(COMMIT, "icon.png"), sha256Hex(golden)),
                ),
                renderableCases = listOf(CorpusRenderableCase("icon", "icon.svg", CorpusInputType.SVG, "icon.png", 16, 16, 16)),
                unpairedAssets = emptyList(),
            ),
            Files.createTempDirectory("materialized-corpus"),
            mapOf("icon.svg" to svg, "icon.png" to golden),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            writeCorpusLockFromManifest(manifest, Files.createTempDirectory("corpus-lock-output").resolve("corpus.lock.json"))
        }

        assertTrue(failure.message.orEmpty().contains("blob object id"))
    }

    @Test
    fun `writer rejects tampered materialization and mismatched existing target`() {
        fun materialization() = CorpusSynchronizer(FakeCorpusSource(mapOf("icon.svg" to "<svg/>", "icon.png" to png(16, 16))))
            .synchronizeCorpus(candidateManifest(), Files.createTempDirectory("materialized-corpus"))

        val assetTampered = materialization()
        Files.writeString(assetTampered.parent.resolve("assets/icon.svg"), "tampered")
        assertFailsWith<IllegalArgumentException> {
            writeCorpusLockFromManifest(assetTampered, Files.createTempDirectory("corpus-lock-output").resolve("corpus.lock.json"))
        }

        val manifestTampered = materialization()
        Files.writeString(manifestTampered, Files.readString(manifestTampered) + " ")
        assertFailsWith<IllegalArgumentException> {
            writeCorpusLockFromManifest(manifestTampered, Files.createTempDirectory("corpus-lock-output").resolve("corpus.lock.json"))
        }

        val valid = materialization()
        val target = Files.createTempDirectory("corpus-lock-output").resolve("corpus.lock.json")
        Files.writeString(target, "different\n")
        assertFailsWith<IllegalStateException> { writeCorpusLockFromManifest(valid, target) }
    }

    private fun candidateManifest(): Path = writeCandidateManifest(
        CandidateManifest(
            schemaVersion = 1,
            scopeVersion = 1,
            scopeSha256 = "scope",
            tag = UpstreamTag("studio-2026.1.2", TAG_OBJECT, COMMIT),
            engineFingerprint = fingerprint("scope", emptyList(), "runtime").sha256,
            scopeEntries = emptyList(),
            sourceArchives = emptyList(),
            engineRuntimeClosureSha256 = "runtime",
            distributionLockSha256 = "locks",
        ),
        Files.createTempDirectory("candidate"),
    )

    private class FakeCorpusSource(files: Map<String, Any>) : CorpusGitilesSource {
        val blobBytes = files.mapValues { (_, value) -> if (value is String) value.toByteArray() else value as ByteArray }.toMutableMap()
        private val objectIds = blobBytes.mapValues { (_, bytes) -> blobObjectId(bytes) }
        var treeEntries = blobBytes.entries.map { (path, bytes) -> CorpusTreeEntry(path, objectIds.getValue(path), sha256Hex(bytes)) }
        override fun tree(commit: String, path: String): List<CorpusTreeEntry> {
            assertEquals(COMMIT, commit)
            assertEquals(CORPUS_PATH, path)
            return treeEntries
        }
        override fun blob(commit: String, path: String): ByteArray {
            assertEquals(COMMIT, commit)
            return blobBytes.getValue(path)
        }
        fun objectIdAt(commit: String, path: String): String = objectIds.getValue(path)

        private fun blobObjectId(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes).joinToString("") { "%02x".format(it) }
    }

    private class FakeCandidateSource(private val files: Map<String, ByteArray>) : CandidateSource {
        var refsWereRead = false
        override fun objectIdAt(commit: String, path: String): String = sha256Hex(path.toByteArray()).take(40)
        override fun archive(commit: String, path: String): ByteArray = files.getValue(path)
        override fun blob(commit: String, path: String): ByteArray = files.getValue(path)
    }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { output ->
        assertTrue(ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output))
    }.toByteArray()

    private fun zeroWidthPng(): ByteArray {
        val valid = png(1, 1)
        valid[16] = 0
        valid[17] = 0
        valid[18] = 0
        valid[19] = 0
        return valid
    }

    private fun objectId(value: String): String = sha256Hex(value.toByteArray()).take(40)

    private fun gzipTar(files: Map<String, ByteArray>): ByteArray = gzipTarEntries(files.map { (path, bytes) -> TarEntry(path, '0', bytes) })

    private fun gzipTarEntries(entries: List<TarEntry>): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { gzip ->
            entries.forEach { (path, type, bytes) ->
                val header = ByteArray(512)
                path.toByteArray().copyInto(header)
                "%011o\u0000".format(bytes.size).toByteArray().copyInto(header, destinationOffset = 124)
                header[156] = type.code.toByte()
                "ustar\u0000".toByteArray().copyInto(header, destinationOffset = 257)
                header.fill(' '.code.toByte(), 148, 156)
                "%06o\u0000 ".format(header.sumOf { it.toInt() and 0xff }).toByteArray().copyInto(header, destinationOffset = 148)
                gzip.write(header)
                gzip.write(bytes)
                gzip.write(ByteArray((512 - bytes.size % 512) % 512))
            }
            gzip.write(ByteArray(1024))
        }
    }.toByteArray()

    private data class TarEntry(val path: String, val type: Char, val bytes: ByteArray)

    private companion object {
        const val TAG_OBJECT = "1111111111111111111111111111111111111111"
        const val COMMIT = "2222222222222222222222222222222222222222"
        const val CORPUS_PATH = "sdk-common/src/test/resources/testData/vectordrawable"
    }
}
