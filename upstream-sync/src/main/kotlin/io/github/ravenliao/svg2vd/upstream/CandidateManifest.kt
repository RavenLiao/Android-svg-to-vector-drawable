package io.github.ravenliao.svg2vd.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

data class SourceArchive(val path: String, val objectId: String, val sha256: String)

data class CandidateManifest(
    val schemaVersion: Int,
    val scopeVersion: Int,
    val scopeSha256: String,
    val tag: UpstreamTag,
    val engineFingerprint: String,
    val scopeEntries: List<ScopeEntry>,
    val sourceArchives: List<SourceArchive>,
    val engineRuntimeClosureSha256: String,
    val distributionLockSha256: String,
)

fun writeCandidateManifest(candidate: CandidateManifest, output: Path): Path {
    require(candidate.engineFingerprint == fingerprint(candidate.scopeSha256, candidate.scopeEntries, candidate.engineRuntimeClosureSha256).sha256) {
        "candidate engine_fingerprint does not match scope hash, scope entries, and engine runtime closure"
    }
    val bytes = canonicalManifestBytes(candidate)
    val destination = output.resolve("${sha256Hex(bytes)}.json")
    Files.createDirectories(output)
    try {
        Files.write(destination, bytes, CREATE_NEW)
    } catch (alreadyExists: java.nio.file.FileAlreadyExistsException) {
        check(Files.readAllBytes(destination).contentEquals(bytes)) { "candidate manifest hash collision at $destination" }
    }
    return destination
}

fun readCandidateManifest(path: Path): CandidateManifest {
    val bytes = Files.readAllBytes(path)
    val root = try {
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("candidate manifest is not JSON: ${error.message}")
    }
    fun string(name: String) = root.getValue(name).jsonPrimitive.content
    fun int(name: String) = root.getValue(name).jsonPrimitive.content.toInt()
    val tag = root.getValue("tag").jsonObject.let { value ->
        UpstreamTag(value.getValue("name").jsonPrimitive.content, value.getValue("tag_object").jsonPrimitive.content, value.getValue("peeled_commit").jsonPrimitive.content)
    }
    val entries = root.getValue("scope_entries").jsonArray.map { value ->
        value.jsonObject.let { ScopeEntry(it.getValue("path").jsonPrimitive.content, it.getValue("object_id").jsonPrimitive.content) }
    }
    val archives = root.getValue("source_archives").jsonArray.map { value ->
        value.jsonObject.let { SourceArchive(it.getValue("path").jsonPrimitive.content, it.getValue("object_id").jsonPrimitive.content, it.getValue("sha256").jsonPrimitive.content) }
    }
    val candidate = CandidateManifest(
        schemaVersion = int("schema_version"),
        scopeVersion = int("scope_version"),
        scopeSha256 = string("scope_sha256"),
        tag = tag,
        engineFingerprint = string("engine_fingerprint"),
        scopeEntries = entries,
        sourceArchives = archives,
        engineRuntimeClosureSha256 = string("engine_runtime_closure_sha256"),
        distributionLockSha256 = string("distribution_lock_sha256"),
    )
    require(canonicalManifestBytes(candidate).contentEquals(bytes)) { "candidate manifest is not canonical" }
    require(candidate.engineFingerprint == fingerprint(candidate.scopeSha256, candidate.scopeEntries, candidate.engineRuntimeClosureSha256).sha256) {
        "candidate engine_fingerprint does not match controlled inputs"
    }
    return candidate
}

internal fun canonicalManifestBytes(candidate: CandidateManifest): ByteArray =
    (Json.encodeToString(JsonObject.serializer(), candidate.toCanonicalJson()) + "\n").toByteArray(Charsets.UTF_8)

private fun CandidateManifest.toCanonicalJson(): JsonObject = JsonObject(
    sortedMapOf(
        "distribution_lock_sha256" to JsonPrimitive(distributionLockSha256),
        "engine_fingerprint" to JsonPrimitive(engineFingerprint),
        "engine_runtime_closure_sha256" to JsonPrimitive(engineRuntimeClosureSha256),
        "schema_version" to JsonPrimitive(schemaVersion),
        "scope_entries" to JsonArray(scopeEntries.sortedWith(compareBy<ScopeEntry> { it.path }.thenBy { it.objectId }).map(ScopeEntry::toCanonicalJson)),
        "scope_sha256" to JsonPrimitive(scopeSha256),
        "scope_version" to JsonPrimitive(scopeVersion),
        "source_archives" to JsonArray(sourceArchives.sortedWith(compareBy<SourceArchive> { it.path }.thenBy { it.objectId }.thenBy { it.sha256 }).map(SourceArchive::toCanonicalJson)),
        "tag" to tag.toCanonicalJson(),
    ),
)

private fun UpstreamTag.toCanonicalJson(): JsonObject = JsonObject(sortedMapOf(
    "name" to JsonPrimitive(name),
    "peeled_commit" to JsonPrimitive(peeledCommit),
    "tag_object" to JsonPrimitive(tagObject),
))

private fun ScopeEntry.toCanonicalJson(): JsonObject = JsonObject(sortedMapOf(
    "object_id" to JsonPrimitive(objectId),
    "path" to JsonPrimitive(path),
))

private fun SourceArchive.toCanonicalJson(): JsonObject = JsonObject(sortedMapOf(
    "object_id" to JsonPrimitive(objectId),
    "path" to JsonPrimitive(path),
    "sha256" to JsonPrimitive(sha256),
))
