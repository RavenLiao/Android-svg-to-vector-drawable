package io.github.ravenliao.svg2vd.upstream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

@Serializable
data class CorpusAsset(val path: String, val objectId: String, val sha256: String)

@Serializable
enum class CorpusInputType { @SerialName("svg") SVG, @SerialName("xml") XML }

@Serializable
data class CorpusRenderableCase(
    val id: String,
    val inputPath: String,
    val inputType: CorpusInputType,
    val goldenPngPath: String,
    val goldenWidth: Int,
    val goldenHeight: Int,
    val renderSize: Int,
)

@Serializable
data class CorpusUnpairedAsset(val path: String, val reason: String)

data class CorpusManifest(
    val schemaVersion: Int,
    val tag: UpstreamTag,
    val license: String,
    val assets: List<CorpusAsset>,
    val renderableCases: List<CorpusRenderableCase>,
    val unpairedAssets: List<CorpusUnpairedAsset>,
)

data class CorpusLock(
    val schemaVersion: Int,
    val tag: UpstreamTag,
    val corpusManifestSha256: String,
    val assets: List<CorpusAsset>,
    val renderableCases: List<CorpusLockedCase>,
)

data class CorpusLockedCase(
    val id: String,
    val input: CorpusAsset,
    val inputType: CorpusInputType,
    val golden: CorpusAsset,
    val goldenWidth: Int,
    val goldenHeight: Int,
    val renderSize: Int,
)

fun writeCorpusManifest(manifest: CorpusManifest, destinationRoot: Path, assets: Map<String, ByteArray>): Path {
    val bytes = canonicalCorpusManifestBytes(manifest)
    val hash = sha256Hex(bytes)
    val root = destinationRoot.toAbsolutePath().normalize().resolve(hash)
    ensureCorpusDirectory(root)
    val assetsRoot = root.resolve("assets")
    ensureCorpusDirectory(assetsRoot)
    manifest.assets.forEach { asset ->
        val value = assets.getValue(asset.path)
        require(sha256Hex(value) == asset.sha256) { "corpus asset bytes do not match manifest: ${asset.path}" }
        val output = assetsRoot.resolve(safeCorpusPath(asset.path)).normalize()
        require(output.startsWith(assetsRoot)) { "corpus asset path escapes destination: ${asset.path}" }
        Files.createDirectories(output.parent)
        writeImmutable(output, value, "corpus asset")
    }
    val output = root.resolve("manifest.json")
    writeImmutable(output, bytes, "corpus manifest")
    return output
}

fun writeCorpusLock(lock: CorpusLock, output: Path): Path {
    val bytes = canonicalCorpusLockBytes(lock)
    val destination = output.resolve("${sha256Hex(bytes)}.json")
    Files.createDirectories(output)
    try {
        Files.write(destination, bytes, CREATE_NEW)
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        check(Files.readAllBytes(destination).contentEquals(bytes)) { "corpus lock hash collision: $destination" }
    }
    return destination
}

fun readCorpusLock(path: Path): CorpusLock {
    val bytes = Files.readAllBytes(path)
    val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    fun string(name: String) = root.getValue(name).jsonPrimitive.content
    val tagJson = root.getValue("tag").jsonObject
    val tag = UpstreamTag(tagJson.getValue("name").jsonPrimitive.content, tagJson.getValue("tag_object").jsonPrimitive.content, tagJson.getValue("peeled_commit").jsonPrimitive.content)
    val lock = CorpusLock(
        schemaVersion = string("schema_version").toInt(),
        tag = tag,
        corpusManifestSha256 = string("corpus_manifest_sha256"),
        assets = root.getValue("assets").jsonArray.map { item ->
            item.jsonObject.let { CorpusAsset(it.getValue("path").jsonPrimitive.content, it.getValue("object_id").jsonPrimitive.content, it.getValue("sha256").jsonPrimitive.content) }
        },
        renderableCases = root.getValue("renderable_cases").jsonArray.map { item ->
            item.jsonObject.let { value ->
                CorpusLockedCase(
                    id = value.getValue("id").jsonPrimitive.content,
                    input = value.getValue("input").jsonObject.toCorpusAsset(),
                    inputType = CorpusInputType.entries.single { it.name.lowercase() == value.getValue("input_type").jsonPrimitive.content },
                    golden = value.getValue("golden").jsonObject.toCorpusAsset(),
                    goldenWidth = value.getValue("golden_width").jsonPrimitive.content.toInt(),
                    goldenHeight = value.getValue("golden_height").jsonPrimitive.content.toInt(),
                    renderSize = value.getValue("render_size").jsonPrimitive.content.toInt(),
                )
            }
        },
    )
    require(canonicalCorpusLockBytes(lock).contentEquals(bytes)) { "corpus lock is not canonical" }
    validateCorpusLock(lock)
    return lock
}

internal fun canonicalCorpusManifestBytes(manifest: CorpusManifest): ByteArray =
    (Json.encodeToString(JsonObject.serializer(), manifest.toCanonicalJson()) + "\n").toByteArray(Charsets.UTF_8)

private fun canonicalCorpusLockBytes(lock: CorpusLock): ByteArray =
    (Json.encodeToString(JsonObject.serializer(), lock.toCanonicalJson()) + "\n").toByteArray(Charsets.UTF_8)

private fun CorpusManifest.toCanonicalJson() = JsonObject(sortedMapOf(
    "assets" to JsonArray(assets.sortedBy(CorpusAsset::path).map(CorpusAsset::toCanonicalJson)),
    "license" to JsonPrimitive(license),
    "renderable_cases" to JsonArray(renderableCases.sortedBy(CorpusRenderableCase::id).map(CorpusRenderableCase::toCanonicalJson)),
    "schema_version" to JsonPrimitive(schemaVersion),
    "tag" to tag.toCorpusJson(),
    "unpaired_assets" to JsonArray(unpairedAssets.sortedBy(CorpusUnpairedAsset::path).map(CorpusUnpairedAsset::toCanonicalJson)),
))

private fun CorpusLock.toCanonicalJson() = JsonObject(sortedMapOf(
    "assets" to JsonArray(assets.sortedBy(CorpusAsset::path).map(CorpusAsset::toCanonicalJson)),
    "corpus_manifest_sha256" to JsonPrimitive(corpusManifestSha256),
    "renderable_cases" to JsonArray(renderableCases.sortedBy(CorpusLockedCase::id).map(CorpusLockedCase::toCanonicalJson)),
    "schema_version" to JsonPrimitive(schemaVersion),
    "tag" to tag.toCorpusJson(),
))

private fun UpstreamTag.toCorpusJson() = JsonObject(sortedMapOf(
    "name" to JsonPrimitive(name),
    "peeled_commit" to JsonPrimitive(peeledCommit),
    "tag_object" to JsonPrimitive(tagObject),
))

private fun CorpusAsset.toCanonicalJson() = JsonObject(sortedMapOf(
    "object_id" to JsonPrimitive(objectId),
    "path" to JsonPrimitive(path),
    "sha256" to JsonPrimitive(sha256),
))

private fun CorpusRenderableCase.toCanonicalJson() = JsonObject(sortedMapOf(
    "golden_height" to JsonPrimitive(goldenHeight),
    "golden_png_path" to JsonPrimitive(goldenPngPath),
    "golden_width" to JsonPrimitive(goldenWidth),
    "id" to JsonPrimitive(id),
    "input_path" to JsonPrimitive(inputPath),
    "input_type" to JsonPrimitive(inputType.name.lowercase()),
    "render_size" to JsonPrimitive(renderSize),
))

private fun CorpusUnpairedAsset.toCanonicalJson() = JsonObject(sortedMapOf(
    "path" to JsonPrimitive(path),
    "reason" to JsonPrimitive(reason),
))

private fun CorpusLockedCase.toCanonicalJson() = JsonObject(sortedMapOf(
    "golden" to golden.toCanonicalJson(),
    "golden_height" to JsonPrimitive(goldenHeight),
    "golden_width" to JsonPrimitive(goldenWidth),
    "id" to JsonPrimitive(id),
    "input" to input.toCanonicalJson(),
    "input_type" to JsonPrimitive(inputType.name.lowercase()),
    "render_size" to JsonPrimitive(renderSize),
))

private fun JsonObject.toCorpusAsset() = CorpusAsset(
    path = getValue("path").jsonPrimitive.content,
    objectId = getValue("object_id").jsonPrimitive.content,
    sha256 = getValue("sha256").jsonPrimitive.content,
)

internal fun validateCorpusLock(lock: CorpusLock) {
    require(lock.schemaVersion == 1) { "unsupported corpus lock schema: ${lock.schemaVersion}" }
    require(lock.corpusManifestSha256.matches(Regex("[0-9a-f]{64}"))) { "corpus lock manifest SHA-256 is invalid" }
    require(classifyTag(lock.tag.name, GitilesRef(lock.tag.tagObject, lock.tag.peeledCommit)) is TagClassification.AcceptedStable) { "corpus lock tag is not an accepted stable tag" }
    require(lock.tag.tagObject.matches(Regex("[0-9a-f]{40}")) && lock.tag.peeledCommit.matches(Regex("[0-9a-f]{40}"))) { "corpus lock tag identity is invalid" }
    require(lock.assets.map(CorpusAsset::path).size == lock.assets.map(CorpusAsset::path).toSet().size) { "corpus lock assets are duplicated" }
    lock.assets.forEach { asset ->
        safeCorpusPath(asset.path)
        require(asset.objectId.matches(Regex("[0-9a-f]{40}")) && asset.sha256.matches(Regex("[0-9a-f]{64}"))) { "corpus lock asset identity is invalid: ${asset.path}" }
    }
    val assets = lock.assets.associateBy(CorpusAsset::path)
    require(lock.renderableCases.map(CorpusLockedCase::id).size == lock.renderableCases.map(CorpusLockedCase::id).toSet().size) { "corpus lock case ids are duplicated" }
    lock.renderableCases.forEach { case ->
        require(case.id.isNotBlank()) { "corpus lock case id is blank" }
        require(assets[case.input.path] == case.input && assets[case.golden.path] == case.golden) { "corpus lock case assets are not exact declared corpus assets: ${case.id}" }
        require(case.inputType == if (case.input.path.lowercase().endsWith(".svg")) CorpusInputType.SVG else CorpusInputType.XML) { "corpus lock case input type is invalid: ${case.id}" }
        require(case.input.path.lowercase().endsWith(".svg") || case.input.path.lowercase().endsWith(".xml")) { "corpus lock case input extension is invalid: ${case.id}" }
        require(case.golden.path.lowercase().endsWith(".png")) { "corpus lock case golden extension is invalid: ${case.id}" }
        require(case.goldenWidth > 0 && case.goldenHeight > 0 && case.renderSize == maxOf(case.goldenWidth, case.goldenHeight)) { "corpus lock case render dimensions are invalid: ${case.id}" }
    }
}

internal fun safeCorpusPath(path: String): Path {
    require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.split('/').any { it in setOf("", ".", "..") }) { "corpus path is unsafe: $path" }
    return Path.of(path)
}

private fun ensureCorpusDirectory(path: Path) {
    if (Files.exists(path) && (!Files.isDirectory(path) || Files.isSymbolicLink(path))) {
        throw CorpusSynchronizationException("corpus destination must be a non-symbolic directory: $path")
    }
    Files.createDirectories(path)
}

private fun writeImmutable(path: Path, bytes: ByteArray, description: String) {
    try {
        Files.write(path, bytes, CREATE_NEW)
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        check(Files.isRegularFile(path) && !Files.isSymbolicLink(path) && Files.readAllBytes(path).contentEquals(bytes)) {
            "$description collision: $path"
        }
    }
}
