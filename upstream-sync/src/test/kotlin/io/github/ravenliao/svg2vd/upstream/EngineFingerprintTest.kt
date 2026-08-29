package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EngineFingerprintTest {
    private val entries = listOf(
        ScopeEntry("sdk-common/src/main/java/B.java", "object-b"),
        ScopeEntry("sdk-common/src/main/java/A.java", "object-a"),
    )

    @Test
    fun `fingerprint is canonical over scope entries and changes only with its three inputs`() {
        val baseline = fingerprint("scope-sha", entries, "runtime-sha")

        assertEquals("1500cb05385b401b75c40f0d62f0e61f2c29ae12c1bedd7397844a1a8a7d636c", baseline.sha256)
        assertEquals(baseline, fingerprint("scope-sha", entries.reversed(), "runtime-sha"))
        assertNotEquals(baseline, fingerprint("scope-sha", entries.dropLast(1) + ScopeEntry("sdk-common/src/main/java/A.java", "other-object"), "runtime-sha"))
        assertNotEquals(baseline, fingerprint("other-scope-sha", entries, "runtime-sha"))
        assertNotEquals(baseline, fingerprint("scope-sha", entries, "other-runtime-sha"))
    }

    @Test
    fun `archive and distribution lock hashes affect canonical manifest bytes but not engine fingerprint`() {
        val fingerprint = fingerprint("scope-sha", entries, "runtime-sha")
        val first = candidate("archive-a", "distribution-a", fingerprint.sha256)
        val second = candidate("archive-b", "distribution-b", fingerprint.sha256)
        val directory = Files.createTempDirectory("candidate-manifest-test")

        val firstPath = writeCandidateManifest(first, directory)
        val secondPath = writeCandidateManifest(second, directory)

        assertEquals(fingerprint.sha256, first.engineFingerprint)
        assertEquals(fingerprint.sha256, second.engineFingerprint)
        assertNotEquals(Files.readAllBytes(firstPath).toList(), Files.readAllBytes(secondPath).toList())
        assertNotEquals(firstPath.fileName.toString(), secondPath.fileName.toString())
        assertEquals(64, firstPath.fileName.toString().removeSuffix(".json").length)
    }

    private fun candidate(archiveHash: String, distributionLock: String, fingerprint: String) = CandidateManifest(
        schemaVersion = 1,
        scopeVersion = 1,
        scopeSha256 = "scope-sha",
        tag = UpstreamTag("studio-2026.1.2", "tag-object", "peeled-commit"),
        engineFingerprint = fingerprint,
        scopeEntries = entries,
        sourceArchives = listOf(SourceArchive("sdk-common", "tree-object", archiveHash)),
        engineRuntimeClosureSha256 = "runtime-sha",
        distributionLockSha256 = distributionLock,
    )
}
