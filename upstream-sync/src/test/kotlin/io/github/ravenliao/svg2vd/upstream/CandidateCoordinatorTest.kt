package io.github.ravenliao.svg2vd.upstream

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CandidateCoordinatorTest {
    @Test
    fun `sync validation rejects non canonical filename unsupported schema and invalid local tag`() {
        val scope = scope("AssetUtil.java", blobs = setOf("AssetUtil.java"))
        val valid = candidate(scope)
        val candidates = Files.createTempDirectory("external-candidates")
        val validPath = writeCandidateManifest(valid, candidates)

        val wrongName = candidates.resolve("candidate.json")
        Files.copy(validPath, wrongName)
        assertFailsWith<SourceSynchronizationException> { validateCandidateInputs(wrongName, scope, "runtime", "locks") }

        val unsupportedSchema = writeCandidateManifest(valid.copy(schemaVersion = 2), candidates)
        assertFailsWith<SourceSynchronizationException> { validateCandidateInputs(unsupportedSchema, scope, "runtime", "locks") }

        val invalidTag = writeCandidateManifest(valid.copy(tag = valid.tag.copy(name = "studio-2026.1.2-rc1")), candidates)
        assertFailsWith<SourceSynchronizationException> { validateCandidateInputs(invalidTag, scope, "runtime", "locks") }
    }

    @Test
    fun `sync validation requires unique matching lowercase object ids and archive hashes`() {
        val scope = scope("AssetUtil.java", blobs = setOf("AssetUtil.java"))
        val valid = candidate(scope)
        val candidates = Files.createTempDirectory("external-candidates")
        val entry = valid.scopeEntries.single()
        val archive = valid.sourceArchives.single()
        val invalid = listOf(
            valid.copy(scopeEntries = listOf(entry.copy(objectId = "A".repeat(40)))).withFingerprint(scope),
            valid.copy(sourceArchives = listOf(archive.copy(objectId = TREE))).withFingerprint(scope),
            valid.copy(sourceArchives = listOf(archive.copy(sha256 = archive.sha256.uppercase()))).withFingerprint(scope),
            valid.copy(scopeEntries = listOf(entry, entry)).withFingerprint(scope),
            valid.copy(sourceArchives = listOf(archive, archive)).withFingerprint(scope),
        )

        invalid.forEach { candidate ->
            val manifest = writeCandidateManifest(candidate, candidates)
            assertFailsWith<SourceSynchronizationException> { validateCandidateInputs(manifest, scope, "runtime", "locks") }
        }
    }

    @Test
    fun `candidate inputs fail closed when current scope runtime closure or distribution locks drift`() {
        val scope = scope("AssetUtil.java", blobs = setOf("AssetUtil.java"))
        val bytes = "class AssetUtil {}".toByteArray()
        val archive = SourceArchive("AssetUtil.java", BLOB, sha256Hex(bytes))
        val entries = listOf(ScopeEntry("AssetUtil.java", BLOB))
        val manifest = writeCandidateManifest(
            CandidateManifest(
                schemaVersion = 1,
                scopeVersion = scope.version,
                scopeSha256 = scope.sha256,
                tag = UpstreamTag("studio-2026.1.2", TAG_OBJECT, COMMIT),
                engineFingerprint = fingerprint(scope.sha256, entries, "runtime").sha256,
                scopeEntries = entries,
                sourceArchives = listOf(archive),
                engineRuntimeClosureSha256 = "runtime",
                distributionLockSha256 = "locks",
            ),
            Files.createTempDirectory("external-candidates"),
        )

        assertEquals("studio-2026.1.2", validateCandidateInputs(manifest, scope, "runtime", "locks").tag.name)
        assertFailsWith<SourceSynchronizationException> { validateCandidateInputs(manifest, scope, "changed-runtime", "locks") }
        assertFailsWith<SourceSynchronizationException> { validateCandidateInputs(manifest, scope, "runtime", "changed-locks") }
        assertFailsWith<SourceSynchronizationException> { validateCandidateInputs(manifest, scope.copy(version = scope.version + 1), "runtime", "locks") }
    }

    @Test
    fun `candidate discovery reads refs once and writes exact tag object paths and acquired hashes`() {
        val scope = scope("directory", "AssetUtil.java", blobs = setOf("AssetUtil.java"))
        var refsCalls = 0
        val source = object : CandidateSource {
            override fun objectIdAt(commit: String, path: String): String = if (path == "directory") TREE else BLOB
            override fun archive(commit: String, path: String): ByteArray = archive("Source.java" to "class Source {}")
            override fun blob(commit: String, path: String): ByteArray = "class AssetUtil {}".toByteArray()
        }
        val coordinator = CandidateManifestCoordinator(
            refs = {
                refsCalls += 1
                GitilesRefs(mapOf("refs/tags/studio-2026.1.2" to GitilesRef(TAG_OBJECT, COMMIT)))
            },
            source = source,
        )

        val candidatePath = coordinator.discover(
            tagName = "studio-2026.1.2",
            scope = scope,
            engineRuntimeClosureSha256 = "runtime",
            distributionLockSha256 = "locks",
            candidatesDirectory = Files.createTempDirectory("external-candidates"),
        )
        val candidate = readCandidateManifest(candidatePath)

        assertEquals(1, refsCalls)
        assertEquals(UpstreamTag("studio-2026.1.2", TAG_OBJECT, COMMIT), candidate.tag)
        assertEquals(listOf("AssetUtil.java", "directory"), candidate.scopeEntries.map(ScopeEntry::path))
        assertEquals(sha256Hex("class AssetUtil {}".toByteArray()), candidate.sourceArchives.single { it.path == "AssetUtil.java" }.sha256)
        assertEquals(sha256Hex(archive("Source.java" to "class Source {}")), candidate.sourceArchives.single { it.path == "directory" }.sha256)
        assertEquals("class AssetUtil {}", Files.readString(candidateMaterialPath(candidatePath, candidate.sourceArchives.single { it.path == "AssetUtil.java" })))
    }

    @Test
    fun `fixed Gitiles content client acquires exact tree archives and blobs with fail closed transport`() {
        val requests = mutableListOf<URI>()
        val archiveBytes = archive("Source.java" to "class Source {}")
        val client = GitilesContentClient(
            transport = GitilesBytesTransport { uri, timeout ->
                requests += uri
                assertEquals(Duration.ofSeconds(9), timeout)
                when (uri) {
                    gitilesObjectUri(COMMIT, "directory") -> GitilesBytesResponse(200, ")]}'\n{\"id\":\"$TREE\"}".toByteArray())
                    gitilesArchiveUri(COMMIT, "directory") -> GitilesBytesResponse(200, archiveBytes)
                    gitilesBlobUri(COMMIT, "AssetUtil.java") -> GitilesBytesResponse(200, Base64.getEncoder().encode("blob bytes".toByteArray()))
                    else -> error("unexpected URI $uri")
                }
            },
            timeout = Duration.ofSeconds(9),
        )

        assertEquals(TREE, client.objectIdAt(COMMIT, "directory"))
        assertEquals(archiveBytes.toList(), client.archive(COMMIT, "directory").toList())
        assertEquals("blob bytes", client.blob(COMMIT, "AssetUtil.java").toString(Charsets.UTF_8))
        assertEquals(listOf(gitilesObjectUri(COMMIT, "directory"), gitilesArchiveUri(COMMIT, "directory"), gitilesBlobUri(COMMIT, "AssetUtil.java")), requests)

        assertFailsWith<GitilesTransportException> {
            GitilesContentClient(GitilesBytesTransport { _, _ -> GitilesBytesResponse(404, byteArrayOf()) }).archive(COMMIT, "directory")
        }
        assertFailsWith<IllegalArgumentException> {
            GitilesContentClient(GitilesBytesTransport { _, _ -> GitilesBytesResponse(200, "{}".toByteArray()) }).objectIdAt(COMMIT, "directory")
        }
    }

    @Test
    fun `Gitiles content retries transient failures but not permanent failures`() {
        var transientAttempts = 0
        val recovered = GitilesContentClient(
            transport = GitilesBytesTransport { _, _ ->
                transientAttempts += 1
                if (transientAttempts < 3) {
                    GitilesBytesResponse(503, byteArrayOf())
                } else {
                    GitilesBytesResponse(200, "payload".toByteArray())
                }
            },
            retryDelay = Duration.ZERO,
        )

        assertEquals("payload", recovered.archive(COMMIT, "directory").toString(Charsets.UTF_8))
        assertEquals(3, transientAttempts)

        var rateLimitAttempts = 0
        val rateLimitRecovered = GitilesContentClient(
            transport = GitilesBytesTransport { _, _ ->
                rateLimitAttempts += 1
                if (rateLimitAttempts == 1) GitilesBytesResponse(429, byteArrayOf())
                else GitilesBytesResponse(200, "rate-limit-recovered".toByteArray())
            },
            retryDelay = Duration.ZERO,
        )
        assertEquals("rate-limit-recovered", rateLimitRecovered.archive(COMMIT, "directory").toString(Charsets.UTF_8))
        assertEquals(2, rateLimitAttempts)

        var permanentAttempts = 0
        val permanentFailure = GitilesContentClient(
            transport = GitilesBytesTransport { _, _ ->
                permanentAttempts += 1
                GitilesBytesResponse(404, byteArrayOf())
            },
            retryDelay = Duration.ZERO,
        )
        assertFailsWith<GitilesTransportException> { permanentFailure.archive(COMMIT, "directory") }
        assertEquals(1, permanentAttempts)

        var ioAttempts = 0
        val ioFailure = GitilesContentClient(
            transport = GitilesBytesTransport { _, _ ->
                ioAttempts += 1
                throw IOException("temporary network failure")
            },
            retryDelay = Duration.ZERO,
        )
        assertFailsWith<GitilesTransportException> { ioFailure.archive(COMMIT, "directory") }
        assertEquals(5, ioAttempts)
    }

    @Test
    fun `sync coordinator accepts only external immutable candidate and never queries refs`() {
        val workspace = Files.createTempDirectory("workspace")
        val externalCandidates = Files.createTempDirectory("external-candidates")
        val scope = scope("AssetUtil.java", blobs = setOf("AssetUtil.java"))
        val bytes = "class AssetUtil {}".toByteArray()
        val manifest = CandidateManifest(
            schemaVersion = 1,
            scopeVersion = scope.version,
            scopeSha256 = scope.sha256,
            tag = UpstreamTag("studio-2026.1.2", TAG_OBJECT, COMMIT),
            engineFingerprint = fingerprint(scope.sha256, listOf(ScopeEntry("AssetUtil.java", BLOB)), "runtime").sha256,
            scopeEntries = listOf(ScopeEntry("AssetUtil.java", BLOB)),
            sourceArchives = listOf(SourceArchive("AssetUtil.java", BLOB, sha256Hex(bytes))),
            engineRuntimeClosureSha256 = "runtime",
            distributionLockSha256 = "locks",
        )
        val externalManifest = writeCandidateManifest(manifest, externalCandidates)
        val coordinator = SyncCandidateCoordinator(
            workspaceRoot = workspace,
            scope = scope,
            engineBuildDirectory = workspace.resolve("engine/build"),
            materialFor = { SourceMaterial.Blob(Files.write(Files.createTempFile("material", ".blob"), bytes)) },
            engineRuntimeClosureSha256 = "runtime",
            distributionLockSha256 = "locks",
        )

        val target = coordinator.sync(externalManifest)

        assertEquals("class AssetUtil {}", Files.readString(target.resolve("AssetUtil.java")))
        val insideManifest = writeCandidateManifest(manifest, workspace.resolve("work/candidates"))
        assertFailsWith<SourceSynchronizationException> { coordinator.sync(insideManifest) }
    }

    @Test
    fun `material store consumes acquisition sidecar without a second Gitiles download`() {
        val candidates = Files.createTempDirectory("external-candidates")
        val scope = scope("AssetUtil.java", blobs = setOf("AssetUtil.java"))
        val bytes = "class AssetUtil {}".toByteArray()
        val archive = SourceArchive("AssetUtil.java", BLOB, sha256Hex(bytes))
        val manifest = CandidateManifest(
            schemaVersion = 1,
            scopeVersion = scope.version,
            scopeSha256 = scope.sha256,
            tag = UpstreamTag("studio-2026.1.2", TAG_OBJECT, COMMIT),
            engineFingerprint = fingerprint(scope.sha256, listOf(ScopeEntry("AssetUtil.java", BLOB)), "runtime").sha256,
            scopeEntries = listOf(ScopeEntry("AssetUtil.java", BLOB)),
            sourceArchives = listOf(archive),
            engineRuntimeClosureSha256 = "runtime",
            distributionLockSha256 = "locks",
        )
        val path = writeCandidateManifest(manifest, candidates)
        writeCandidateMaterials(path, mapOf(archive to bytes))

        val material = CandidateMaterialStore(path, scope).materialFor(archive)

        assertTrue(material is SourceMaterial.Blob)
        assertEquals(bytes.toList(), Files.readAllBytes((material as SourceMaterial.Blob).path).toList())
    }

    @Test
    fun `material store rejects missing or altered sidecar bytes`() {
        val candidates = Files.createTempDirectory("external-candidates")
        val scope = scope("AssetUtil.java", blobs = setOf("AssetUtil.java"))
        val bytes = "class AssetUtil {}".toByteArray()
        val archive = SourceArchive("AssetUtil.java", BLOB, sha256Hex(bytes))
        val manifest = CandidateManifest(
            schemaVersion = 1,
            scopeVersion = scope.version,
            scopeSha256 = scope.sha256,
            tag = UpstreamTag("studio-2026.1.2", TAG_OBJECT, COMMIT),
            engineFingerprint = fingerprint(scope.sha256, listOf(ScopeEntry("AssetUtil.java", BLOB)), "runtime").sha256,
            scopeEntries = listOf(ScopeEntry("AssetUtil.java", BLOB)),
            sourceArchives = listOf(archive),
            engineRuntimeClosureSha256 = "runtime",
            distributionLockSha256 = "locks",
        )
        val path = writeCandidateManifest(manifest, candidates)
        assertFailsWith<SourceSynchronizationException> { CandidateMaterialStore(path, scope).materialFor(archive) }
        writeCandidateMaterials(path, mapOf(archive to bytes))
        Files.writeString(candidateMaterialPath(path, archive), "tampered")

        assertFailsWith<SourceSynchronizationException> { CandidateMaterialStore(path, scope).materialFor(archive) }
    }

    @Test
    fun `sync coordinator accepts Gitiles PAX archive metadata without relaxing path validation`() {
        val candidates = Files.createTempDirectory("external-candidates")
        val workspace = Files.createTempDirectory("workspace")
        val scope = scope("directory")
        val bytes = paxArchive("Source.java" to "class Source {}")
        val archive = SourceArchive("directory", TREE, sha256Hex(bytes))
        val manifest = CandidateManifest(
            schemaVersion = 1,
            scopeVersion = scope.version,
            scopeSha256 = scope.sha256,
            tag = UpstreamTag("studio-2026.1.2", TAG_OBJECT, COMMIT),
            engineFingerprint = fingerprint(scope.sha256, listOf(ScopeEntry("directory", TREE)), "runtime").sha256,
            scopeEntries = listOf(ScopeEntry("directory", TREE)),
            sourceArchives = listOf(archive),
            engineRuntimeClosureSha256 = "runtime",
            distributionLockSha256 = "locks",
        )
        val path = writeCandidateManifest(manifest, candidates)
        writeCandidateMaterials(path, mapOf(archive to bytes))

        val result = SyncCandidateCoordinator(
            workspace,
            scope,
            workspace.resolve("engine/build"),
            CandidateMaterialStore(path, scope)::materialFor,
            "runtime",
            "locks",
        ).sync(path)

        assertEquals("class Source {}", Files.readString(result.resolve("directory/Source.java")))
    }

    private fun scope(vararg paths: String, blobs: Set<String> = emptySet()) = UpstreamScope(1, paths.toList(), KNOWN_LEGACY_TAGS, blobs)

    private fun candidate(scope: UpstreamScope): CandidateManifest {
        val bytes = "class AssetUtil {}".toByteArray()
        val entries = listOf(ScopeEntry("AssetUtil.java", BLOB))
        return CandidateManifest(
            schemaVersion = 1,
            scopeVersion = scope.version,
            scopeSha256 = scope.sha256,
            tag = UpstreamTag("studio-2026.1.2", TAG_OBJECT, COMMIT),
            engineFingerprint = fingerprint(scope.sha256, entries, "runtime").sha256,
            scopeEntries = entries,
            sourceArchives = listOf(SourceArchive("AssetUtil.java", BLOB, sha256Hex(bytes))),
            engineRuntimeClosureSha256 = "runtime",
            distributionLockSha256 = "locks",
        )
    }

    private fun CandidateManifest.withFingerprint(scope: UpstreamScope) = copy(
        engineFingerprint = fingerprint(scope.sha256, scopeEntries, engineRuntimeClosureSha256).sha256,
    )

    private fun archive(vararg files: Pair<String, String>): ByteArray {
        val tar = ByteArrayOutputStream()
        files.forEach { (name, content) ->
            val bytes = content.toByteArray()
            val header = ByteArray(512)
            name.toByteArray().copyInto(header, endIndex = name.length)
            bytes.size.toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
            header[135] = 0
            header[156] = '0'.code.toByte()
            tar.write(header)
            tar.write(bytes)
            tar.write(ByteArray((512 - bytes.size % 512) % 512))
        }
        tar.write(ByteArray(1024))
        return ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(tar.toByteArray()) } }.toByteArray()
    }

    private fun paxArchive(file: Pair<String, String>): ByteArray {
        val tar = ByteArrayOutputStream()
        writeTarEntry(tar, "./PaxHeaders.X/${file.first}", "27 mtime=0\n", 'x')
        writeTarEntry(tar, file.first, file.second, '0')
        tar.write(ByteArray(1024))
        return ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(tar.toByteArray()) } }.toByteArray()
    }

    private fun writeTarEntry(output: ByteArrayOutputStream, name: String, content: String, type: Char) {
        val bytes = content.toByteArray()
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, endIndex = name.length)
        bytes.size.toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
        header[135] = 0
        header[156] = type.code.toByte()
        output.write(header)
        output.write(bytes)
        output.write(ByteArray((512 - bytes.size % 512) % 512))
    }

    private companion object {
        const val TAG_OBJECT = "1111111111111111111111111111111111111111"
        const val COMMIT = "2222222222222222222222222222222222222222"
        const val TREE = "3333333333333333333333333333333333333333"
        const val BLOB = "4444444444444444444444444444444444444444"
    }
}
