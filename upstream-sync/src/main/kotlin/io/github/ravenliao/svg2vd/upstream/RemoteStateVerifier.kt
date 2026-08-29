package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Validates a fully downloaded GitHub release snapshot before discovery consumes its anchor.
 * The fetcher is deliberately outside this boundary so later CI code can persist the exact raw
 * response and assets before this verifier makes any release decision.
 */
class RemoteStateVerifier {
    fun verify(remote: RemoteReleaseState, output: Path): VerifiedAnchor? {
        val anchor = deriveVerifiedAnchor(remote)
        Files.createDirectories(output.parent)
        val bytes = canonicalRemoteState(remote).toByteArray(Charsets.UTF_8)
        try {
            Files.write(output, bytes, CREATE_NEW)
        } catch (alreadyExists: java.nio.file.FileAlreadyExistsException) {
            check(Files.readAllBytes(output).contentEquals(bytes)) { "remote state is immutable: $output" }
        }
        return anchor
    }

    private fun canonicalRemoteState(remote: RemoteReleaseState): String {
        val gitTags = remote.gitTags.toSortedMap().map { (name, target) ->
            JsonObject(sortedMapOf(
                "name" to JsonPrimitive(name),
                "object_id" to JsonPrimitive(target.objectId),
                "peeled_commit" to (target.peeledCommit?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?)),
            ))
        }
        val releases = remote.releases.sortedBy(ObservedRelease::tag).map { release ->
            JsonObject(sortedMapOf(
                "assets" to JsonArray(release.assets.sortedBy(ObservedAsset::name).map { asset ->
                    JsonObject(sortedMapOf("name" to JsonPrimitive(asset.name), "sha256" to JsonPrimitive(sha256Hex(asset.bytes))))
                }),
                "provenance_sha256" to JsonPrimitive(release.provenanceBytes?.let(::sha256Hex) ?: ""),
                "tag" to JsonPrimitive(release.tag),
                "verification" to JsonPrimitive(release.verification.name.lowercase()),
            ))
        }
        return Json.encodeToString(JsonObject.serializer(), JsonObject(sortedMapOf("git_tags" to JsonArray(gitTags), "releases" to JsonArray(releases)))) + "\n"
    }
}
