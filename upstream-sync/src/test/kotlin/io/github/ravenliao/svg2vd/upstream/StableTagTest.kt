package io.github.ravenliao.svg2vd.upstream

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class StableTagTest {
    @Test
    fun `annotated refs preserve tag object and peeled commit while stable patches sort numerically`() {
        val refs = GitilesRefs.fromJson(")]}'\n" + fixture("refs.json"))

        val selected = selectStableTagsAfter(refs, StableVersion(StableCore(2024, 1, 1), 1, "studio-2024.1.1-patch01"))

        assertEquals(
            listOf("studio-2025.1.1-patch1", "studio-2025.1.1-patch2", "studio-2025.1.1-patch10", "studio-2026.1.2", "studio-2026.1.3"),
            selected.map(UpstreamTag::name),
        )
        assertEquals("tag-2024-p01", classifyTag(refs, "refs/tags/studio-2024.1.1-patch01").tagObject)
        assertEquals("commit-2024-p01", classifyTag(refs, "refs/tags/studio-2024.1.1-patch01").peeledCommit)
    }

    @Test
    fun `recognized prereleases legacy tags and earlier unknowns are nonblocking but stay auditable`() {
        val refs = refs(
            "studio-2026.1.2" to ref("stable", "commit-stable"),
            "studio-2026.1.3-alpha" to ref("a", "ca"),
            "studio-2026.1.3-alpha01" to ref("a1", "ca1"),
            "studio-2026.1.3-beta" to ref("b", "cb"),
            "studio-2026.1.3-beta02" to ref("b2", "cb2"),
            "studio-2026.1.3-canary" to ref("c", "cc"),
            "studio-2026.1.3-canary03" to ref("c3", "cc3"),
            "studio-2026.1.3-rc" to ref("r", "cr"),
            "studio-2026.1.3-rc04" to ref("r4", "cr4"),
            "studio-master-dev_before_123" to ref("master", "cm"),
            "studio-1.4" to ref("legacy", "cl"),
            "studio-2025.9.9-hotfix1" to ref("historical", "ch"),
        )

        val result = discoverStableTags(refs, StableVersion(StableCore(2026, 1, 2), 0, "studio-2026.1.2"))

        val success = assertIs<DiscoveryResult.Success>(result)
        assertEquals(emptyList(), success.candidates)
        assertEquals(listOf("studio-2025.9.9-hotfix1"), success.nonblockingHistoricalUnknowns.map(TagClassification.UnknownVersionLike::rawTag))
    }

    @Test
    fun `unknown current future and malformed studio tags fail closed with full evidence`() {
        val anchor = StableVersion(StableCore(2026, 1, 2), 0, "studio-2026.1.2")
        listOf("studio-2027.1.1-hotfix1", "studio-2027.1.1.1", "studio-2027.1.1-stable", "studio-2027.1").forEach { unknown ->
            val error = assertFailsWith<DiscoveryBlockedException> {
                discoverStableTags(refs("studio-2026.1.2" to ref("stable-tag", "stable-commit"), unknown to ref("unknown-tag", "unknown-commit")), anchor)
            }
            assertContains(error.message.orEmpty(), unknown)
            assertContains(error.message.orEmpty(), "unknown-tag")
            assertContains(error.message.orEmpty(), "unknown-commit")
            assertContains(error.message.orEmpty(), ACCEPTED_STABLE_SYNTAX)
        }
    }

    @Test
    fun `same numeric core unknown blocks regardless of patch anchor or bootstrap patch`() {
        val refs = refs(
            "studio-2025.1.1-patch10" to ref("p10", "cp10"),
            "studio-2025.1.1-hotfix1" to ref("unknown", "cu"),
        )

        assertFailsWith<DiscoveryBlockedException> {
            discoverStableTags(refs, StableVersion(StableCore(2025, 1, 1), 10, "studio-2025.1.1-patch10"))
        }
        assertFailsWith<DiscoveryBlockedException> { discoverStableTags(refs, null) }
    }

    @Test
    fun `conflicting raw spellings for the same patch revision fail closed`() {
        val error = assertFailsWith<DiscoveryBlockedException> {
            discoverStableTags(
                refs(
                    "studio-2025.1.1-patch1" to ref("p1", "cp1"),
                    "studio-2025.1.1-patch01" to ref("p01", "cp01"),
                ),
                null,
            )
        }
        assertContains(error.message.orEmpty(), "patch1")
        assertContains(error.message.orEmpty(), "patch01")
    }

    @Test
    fun `bootstrap picks only the highest accepted stable and anchored discovery returns every later stable`() {
        val refs = refs(
            "studio-2025.1.1" to ref("a", "ca"),
            "studio-2025.1.2" to ref("b", "cb"),
            "studio-2025.1.3" to ref("c", "cc"),
        )

        assertEquals(listOf("studio-2025.1.3"), assertIs<DiscoveryResult.Success>(discoverStableTags(refs, null)).candidates.map(UpstreamTag::name))
        assertEquals(
            listOf("studio-2025.1.2", "studio-2025.1.3"),
            assertIs<DiscoveryResult.Success>(discoverStableTags(refs, StableVersion(StableCore(2025, 1, 1), 0, "studio-2025.1.1"))).candidates.map(UpstreamTag::name),
        )
    }

    @Test
    fun `only complete matching releases produce an anchor and remote corruption fails closed`() {
        val good = observedRelease("studio-2025.1.1", "fingerprint-1", complete = true)
        val newer = observedRelease("studio-2025.1.2", "fingerprint-2", complete = true)
        val state = RemoteReleaseState(
            releases = listOf(good, newer),
            gitTags = mapOf(
                "studio-2025.1.1" to GitTagTarget("tool-1", null),
                "studio-2025.1.2" to GitTagTarget("tag-object-2", "tool-2"),
            ),
        )
        assertEquals(VerifiedAnchor("studio-2025.1.2", "fingerprint-2"), deriveVerifiedAnchor(state))
        assertNull(deriveVerifiedAnchor(RemoteReleaseState(emptyList(), emptyMap())))

        assertFailsWith<RemoteStateException> {
            deriveVerifiedAnchor(RemoteReleaseState(listOf(observedRelease("studio-2025.1.3", "f", complete = false)), emptyMap()))
        }
        assertFailsWith<RemoteStateException> {
            deriveVerifiedAnchor(RemoteReleaseState(listOf(good), emptyMap()))
        }
        assertFailsWith<RemoteStateException> {
            deriveVerifiedAnchor(RemoteReleaseState(emptyList(), mapOf("studio-2025.1.1" to GitTagTarget("orphan", null))))
        }
    }

    private fun fixture(name: String) = javaClass.classLoader.getResourceAsStream(name)!!.readBytes().toString(StandardCharsets.UTF_8)

    private fun refs(vararg tags: Pair<String, GitilesRef>) = GitilesRefs(tags.associate { "refs/tags/${it.first}" to it.second })

    private fun ref(tagObject: String, peeledCommit: String) = GitilesRef(tagObject, peeledCommit)

    private fun observedRelease(tag: String, fingerprint: String, complete: Boolean): ObservedRelease =
        ObservedRelease(tag, fingerprint, "tool-${tag.takeLast(1)}", complete)

    private fun classifyTag(refs: GitilesRefs, name: String): UpstreamTag =
        assertIs<TagClassification.AcceptedStable>(classifyTag(name.removePrefix("refs/tags/"), refs.refs.getValue(name))).tag
}
