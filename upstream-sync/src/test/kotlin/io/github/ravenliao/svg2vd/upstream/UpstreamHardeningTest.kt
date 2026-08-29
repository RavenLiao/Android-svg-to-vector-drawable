package io.github.ravenliao.svg2vd.upstream

import java.net.URI
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpstreamHardeningTest {
    @Test
    fun `null helper anchor uses bootstrap semantics instead of replaying all stable tags`() {
        val refs = GitilesRefs(
            mapOf(
                "refs/tags/studio-2025.1.1" to GitilesRef("a", "ca"),
                "refs/tags/studio-2025.1.2" to GitilesRef("b", "cb"),
            ),
        )

        assertEquals(listOf("studio-2025.1.2"), selectStableTagsAfter(refs, null).map(UpstreamTag::name))
    }

    @Test
    fun `remote verifier recomputes asset bytes instead of trusting declared hash and valid flag`() {
        val expectedSha = sha256Hex("expected bytes".toByteArray())
        val release = ObservedRelease(
            tag = "studio-2025.1.1",
            assets = listOf(ObservedAsset("svg2vd.jar", "tampered bytes".toByteArray(), declaredSha256 = expectedSha)),
            provenanceBytes = "{\"artifacts\":[{\"name\":\"svg2vd.jar\",\"sha256\":\"$expectedSha\"}],\"engine_fingerprint\":\"fp\",\"tool_source_commit\":\"tool\"}".toByteArray(),
            verification = ReleaseVerification.VALID,
        )

        assertFailsWith<RemoteStateException> {
            deriveVerifiedAnchor(RemoteReleaseState(listOf(release), mapOf("studio-2025.1.1" to GitTagTarget("tool", null))))
        }
    }

    @Test
    fun `candidate writer rejects a supplied engine fingerprint that does not match controlled inputs`() {
        val candidate = CandidateManifest(
            schemaVersion = 1,
            scopeVersion = 1,
            scopeSha256 = "scope",
            tag = UpstreamTag("studio-2026.1.2", "tag", "commit"),
            engineFingerprint = "forged",
            scopeEntries = listOf(ScopeEntry("a", "object-a")),
            sourceArchives = listOf(SourceArchive("a", "tree-a", "archive-a")),
            engineRuntimeClosureSha256 = "runtime",
            distributionLockSha256 = "distribution",
        )

        assertFailsWith<IllegalArgumentException> { writeCandidateManifest(candidate, Files.createTempDirectory("forged-candidate")) }
    }

    @Test
    fun `gitiles client uses one fixed URI and forwards a finite timeout`() {
        var seenUri: URI? = null
        var seenTimeout: Duration? = null
        val client = GitilesClient(
            transport = GitilesTransport { uri, timeout ->
                seenUri = uri
                seenTimeout = timeout
                GitilesHttpResponse(200, ")]}'\n{\"refs/tags/studio-2026.1.2\":{\"value\":\"tag\",\"peeled\":\"commit\"}}")
            },
            timeout = Duration.ofSeconds(7),
        )

        assertEquals("tag", client.fetchRefs().refs.getValue("refs/tags/studio-2026.1.2").value)
        assertEquals(GITILES_REFS_URI, seenUri)
        assertEquals(Duration.ofSeconds(7), seenTimeout)
    }

    @Test
    fun `gitiles rejects non success responses and missing exact XSSI prefix`() {
        val responseFailure = GitilesClient(GitilesTransport { _, _ -> GitilesHttpResponse(503, "unavailable") })
        assertFailsWith<GitilesTransportException> { responseFailure.fetchRefs() }

        val missingPrefix = GitilesClient(GitilesTransport { _, _ -> GitilesHttpResponse(200, "{}") })
        assertFailsWith<IllegalArgumentException> { missingPrefix.fetchRefs() }
    }

    @Test
    fun `gitiles refs retries transient responses`() {
        var attempts = 0
        val client = GitilesClient(
            transport = GitilesTransport { _, _ ->
                attempts += 1
                if (attempts < 3) {
                    GitilesHttpResponse(503, "")
                } else {
                    GitilesHttpResponse(200, ")]}'\n{\"refs/tags/studio-2026.1.2\":{\"value\":\"tag\",\"peeled\":\"commit\"}}")
                }
            },
            retryDelay = Duration.ZERO,
        )

        assertEquals("tag", client.fetchRefs().refs.getValue("refs/tags/studio-2026.1.2").value)
        assertEquals(3, attempts)
    }

    @Test
    fun `candidate manifest is complete canonical UTF8 JSON with sorted arrays one LF and no BOM`() {
        val candidate = CandidateManifest(
            schemaVersion = 1,
            scopeVersion = 1,
            scopeSha256 = "scope",
            tag = UpstreamTag("studio-2026.1.2", "tag", "commit"),
            engineFingerprint = fingerprint("scope", listOf(ScopeEntry("a", "a"), ScopeEntry("b", "b")), "runtime").sha256,
            scopeEntries = listOf(ScopeEntry("b", "b"), ScopeEntry("a", "a")),
            sourceArchives = listOf(SourceArchive("b", "tree-b", "archive-b"), SourceArchive("a", "tree-a", "archive-a")),
            engineRuntimeClosureSha256 = "runtime",
            distributionLockSha256 = "distribution",
        )
        val path = writeCandidateManifest(candidate, Files.createTempDirectory("canonical-candidate"))
        val bytes = Files.readAllBytes(path)
        val expected = "{\"distribution_lock_sha256\":\"distribution\",\"engine_fingerprint\":\"${candidate.engineFingerprint}\",\"engine_runtime_closure_sha256\":\"runtime\",\"schema_version\":1,\"scope_entries\":[{\"object_id\":\"a\",\"path\":\"a\"},{\"object_id\":\"b\",\"path\":\"b\"}],\"scope_sha256\":\"scope\",\"scope_version\":1,\"source_archives\":[{\"object_id\":\"tree-a\",\"path\":\"a\",\"sha256\":\"archive-a\"},{\"object_id\":\"tree-b\",\"path\":\"b\",\"sha256\":\"archive-b\"}],\"tag\":{\"name\":\"studio-2026.1.2\",\"peeled_commit\":\"commit\",\"tag_object\":\"tag\"}}\n"

        assertEquals(expected, bytes.toString(Charsets.UTF_8))
        assertTrue(bytes.take(3).toByteArray().contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())).not())
        assertEquals(1, bytes.count { it == '\n'.code.toByte() })
    }
}
