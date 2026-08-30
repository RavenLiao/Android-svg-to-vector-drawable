package io.github.ravenliao.svg2vd.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class VerifiedAnchor(val tag: String, val engineFingerprint: String)
data class ObservedAsset(val name: String, val bytes: ByteArray, val declaredSha256: String? = null)
enum class ReleaseVerification { VALID, INVALID }
data class ObservedRelease(
    val tag: String,
    val assets: List<ObservedAsset>,
    val provenanceBytes: ByteArray?,
    val verification: ReleaseVerification,
) {
    /** Test-only convenience constructor; production callers provide downloaded raw provenance. */
    constructor(tag: String, engineFingerprint: String, toolSourceCommit: String, complete: Boolean) : this(
        tag,
        if (complete) listOf(ObservedAsset("svg2vd.jar", "asset bytes".toByteArray())) else emptyList(),
        if (complete) "{\"artifacts\":[{\"name\":\"svg2vd.jar\",\"sha256\":\"${sha256Hex("asset bytes".toByteArray())}\"}],\"engine_fingerprint\":\"$engineFingerprint\",\"tool_source_commit\":\"$toolSourceCommit\"}".toByteArray() else null,
        if (complete) ReleaseVerification.VALID else ReleaseVerification.INVALID,
    )
}
data class GitTagTarget(val objectId: String, val peeledCommit: String?)
data class RemoteReleaseState(val releases: List<ObservedRelease>, val gitTags: Map<String, GitTagTarget>)
class RemoteStateException(message: String) : IllegalStateException(message)

private val releaseMetadataAssets = setOf("SHA256SUMS", "provenance.json")

fun deriveVerifiedAnchor(remote: RemoteReleaseState): VerifiedAnchor? {
    val verified = remote.releases.map { release -> release to verifiedProvenance(release) }
    val releasedTags = verified.flatMap { (release, provenance) ->
        listOfNotNull(release.tag, provenance.upstreamTag)
    }.toSet()
    val orphan = remote.gitTags.keys - releasedTags
    if (orphan.isNotEmpty()) throw RemoteStateException("orphan git tag(s): ${orphan.sorted().joinToString()}")
    if (remote.releases.isEmpty()) return null
    val complete = verified.map { (release, provenance) ->
        if (provenance.engineFingerprint.isBlank() || provenance.toolSourceCommit.isBlank()) {
            throw RemoteStateException("release ${release.tag} is missing complete assets or provenance")
        }
        val target = remote.gitTags[release.tag] ?: throw RemoteStateException("release ${release.tag} has no matching git tag")
        val commit = target.peeledCommit ?: target.objectId
        if (commit != provenance.toolSourceCommit) throw RemoteStateException("git tag ${release.tag} does not target tool_source_commit")
        release to provenance
    }
    return complete.maxByOrNull { stableReleaseVersion(it.second.upstreamTag ?: it.first.tag) }?.let {
        VerifiedAnchor(it.second.upstreamTag ?: it.first.tag, it.second.engineFingerprint)
    }
}

private fun stableReleaseVersion(tag: String): StableVersion =
    (classifyTag(tag, GitilesRef("release-tag")) as? TagClassification.AcceptedStable)?.version
        ?: throw RemoteStateException("release $tag is not an accepted stable tag")

private data class VerifiedReleaseProvenance(
    val engineFingerprint: String,
    val toolSourceCommit: String,
    val upstreamTag: String?,
)

private fun verifiedProvenance(release: ObservedRelease): VerifiedReleaseProvenance {
    if (release.verification != ReleaseVerification.VALID) throw RemoteStateException("release ${release.tag} failed verification")
    val bytes = release.provenanceBytes ?: throw RemoteStateException("release ${release.tag} has no provenance")
    val root = try {
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    } catch (error: Exception) {
        throw RemoteStateException("release ${release.tag} has invalid provenance: ${error.message}")
    }
    val expectedAssetHashes = try {
        root.getValue("artifacts").jsonArray.map { item ->
            val artifact = item.jsonObject
            artifact.getValue("name").jsonPrimitive.content to artifact.getValue("sha256").jsonPrimitive.content
        }.sortedBy { it.first }
    } catch (error: Exception) {
        throw RemoteStateException("release ${release.tag} provenance has invalid assets: ${error.message}")
    }
    val actualAssets = release.assets
        .filterNot { it.name in releaseMetadataAssets }
        .map { asset -> asset.name to sha256Hex(asset.bytes) }
        .sortedBy { it.first }
    if (expectedAssetHashes.isEmpty() || expectedAssetHashes != actualAssets || actualAssets.map { it.first }.distinct().size != actualAssets.size) {
        throw RemoteStateException("release ${release.tag} assets do not match provenance")
    }
    return try {
        VerifiedReleaseProvenance(
            root.getValue("engine_fingerprint").jsonPrimitive.content,
            root.getValue("tool_source_commit").jsonPrimitive.content,
            root["upstream_tag"]?.jsonPrimitive?.content,
        )
    } catch (error: Exception) {
        throw RemoteStateException("release ${release.tag} provenance is incomplete: ${error.message}")
    }
}
