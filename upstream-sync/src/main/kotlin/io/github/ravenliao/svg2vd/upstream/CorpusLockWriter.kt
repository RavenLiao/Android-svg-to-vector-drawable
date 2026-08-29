package io.github.ravenliao.svg2vd.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest

fun writeCorpusLockFromManifest(manifestPath: Path, output: Path): Path {
    val manifest = manifestPath.toAbsolutePath().normalize()
    require(Files.isRegularFile(manifest, NOFOLLOW_LINKS) && !Files.isSymbolicLink(manifest) && manifest.fileName.toString() == "manifest.json") {
        "corpus manifest must be a regular manifest.json file: $manifest"
    }
    val realManifest = manifest.toRealPath()
    require(!Files.isSymbolicLink(realManifest)) { "corpus manifest must not be symbolic: $manifest" }
    val root = realManifest.parent
    require(!Files.isSymbolicLink(root)) { "corpus manifest parent must be a non-symbolic materialization root" }
    val bytes = Files.readAllBytes(realManifest)
    require(root.fileName.toString() == sha256Hex(bytes)) { "corpus materialization root does not match manifest canonical bytes SHA-256" }
    val parsed = parseCanonicalCorpusManifest(bytes)
    val assetsRoot = root.resolve("assets")
    require(Files.isDirectory(assetsRoot, NOFOLLOW_LINKS) && !Files.isSymbolicLink(assetsRoot)) { "corpus materialization has no non-symbolic assets directory" }
    val assets = parsed.assets.associateBy(CorpusAsset::path)
    require(assets.size == parsed.assets.size && assets.isNotEmpty()) { "corpus manifest assets are empty or duplicated" }
    parsed.assets.forEach { asset ->
        safeCorpusPath(asset.path)
        require(asset.objectId.matches(Regex("[0-9a-f]{40}")) && asset.sha256.matches(Regex("[0-9a-f]{64}"))) {
            "corpus manifest asset identity is invalid: ${asset.path}"
        }
        val file = assetsRoot.resolve(asset.path).normalize()
        require(file.startsWith(assetsRoot) && Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) {
            "corpus manifest asset is missing or unsafe: ${asset.path}"
        }
        val assetBytes = Files.readAllBytes(file)
        require(sha256Hex(assetBytes) == asset.sha256) { "corpus manifest asset hash does not match: ${asset.path}" }
        require(gitBlobObjectId(assetBytes) == asset.objectId) { "corpus manifest asset blob object id does not match: ${asset.path}" }
    }
    val lock = CorpusLock(
        schemaVersion = parsed.schemaVersion,
        tag = parsed.tag,
        corpusManifestSha256 = sha256Hex(bytes),
        assets = parsed.assets,
        renderableCases = parsed.renderableCases.map { case ->
            CorpusLockedCase(
                id = case.id,
                input = requireNotNull(assets[case.inputPath]) { "corpus case input is not a declared asset: ${case.id}" },
                inputType = case.inputType,
                golden = requireNotNull(assets[case.goldenPngPath]) { "corpus case golden is not a declared asset: ${case.id}" },
                goldenWidth = case.goldenWidth,
                goldenHeight = case.goldenHeight,
                renderSize = case.renderSize,
            )
        },
    )
    validateCorpusLock(lock)
    return writeFixedCorpusLock(lock, output)
}

private fun gitBlobObjectId(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
    .digest("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8) + bytes)
    .joinToString("") { "%02x".format(it) }

private fun parseCanonicalCorpusManifest(bytes: ByteArray): CorpusManifest {
    val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    fun string(name: String) = root.getValue(name).jsonPrimitive.content
    val tagJson = root.getValue("tag").jsonObject
    val manifest = CorpusManifest(
        schemaVersion = string("schema_version").toInt(),
        tag = UpstreamTag(tagJson.getValue("name").jsonPrimitive.content, tagJson.getValue("tag_object").jsonPrimitive.content, tagJson.getValue("peeled_commit").jsonPrimitive.content),
        license = string("license"),
        assets = root.getValue("assets").jsonArray.map { value -> value.jsonObject.toCorpusAsset() },
        renderableCases = root.getValue("renderable_cases").jsonArray.map { value ->
            value.jsonObject.let { case ->
                CorpusRenderableCase(
                    id = case.getValue("id").jsonPrimitive.content,
                    inputPath = case.getValue("input_path").jsonPrimitive.content,
                    inputType = CorpusInputType.entries.single { it.name.lowercase() == case.getValue("input_type").jsonPrimitive.content },
                    goldenPngPath = case.getValue("golden_png_path").jsonPrimitive.content,
                    goldenWidth = case.getValue("golden_width").jsonPrimitive.content.toInt(),
                    goldenHeight = case.getValue("golden_height").jsonPrimitive.content.toInt(),
                    renderSize = case.getValue("render_size").jsonPrimitive.content.toInt(),
                )
            }
        },
        unpairedAssets = root.getValue("unpaired_assets").jsonArray.map { value ->
            value.jsonObject.let { CorpusUnpairedAsset(it.getValue("path").jsonPrimitive.content, it.getValue("reason").jsonPrimitive.content) }
        },
    )
    require(canonicalCorpusManifestBytes(manifest).contentEquals(bytes)) { "corpus manifest is not canonical" }
    return manifest
}

private fun writeFixedCorpusLock(lock: CorpusLock, output: Path): Path {
    val bytes = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), lock.toCanonicalJsonForWriter()).plus("\n").toByteArray(Charsets.UTF_8)
    val target = output.toAbsolutePath().normalize()
    val parent = requireNotNull(target.parent) { "corpus lock output has no parent: $output" }
    ensureNonSymbolicDirectoryPath(parent)
    try {
        Files.write(target, bytes, CREATE_NEW)
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        check(Files.isRegularFile(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target) && Files.readAllBytes(target).contentEquals(bytes)) {
            "corpus lock output already exists with different bytes: $target"
        }
    }
    return target
}

private fun ensureNonSymbolicDirectoryPath(path: Path) {
    Files.createDirectories(path)
    require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "corpus lock output parent is not a non-symbolic directory: $path" }
}

private fun kotlinx.serialization.json.JsonObject.toCorpusAsset() = CorpusAsset(
    path = getValue("path").jsonPrimitive.content,
    objectId = getValue("object_id").jsonPrimitive.content,
    sha256 = getValue("sha256").jsonPrimitive.content,
)

private fun CorpusLock.toCanonicalJsonForWriter() = kotlinx.serialization.json.JsonObject(sortedMapOf(
    "assets" to kotlinx.serialization.json.JsonArray(assets.sortedBy(CorpusAsset::path).map { asset ->
        kotlinx.serialization.json.JsonObject(sortedMapOf(
            "object_id" to kotlinx.serialization.json.JsonPrimitive(asset.objectId),
            "path" to kotlinx.serialization.json.JsonPrimitive(asset.path),
            "sha256" to kotlinx.serialization.json.JsonPrimitive(asset.sha256),
        ))
    }),
    "corpus_manifest_sha256" to kotlinx.serialization.json.JsonPrimitive(corpusManifestSha256),
    "renderable_cases" to kotlinx.serialization.json.JsonArray(renderableCases.sortedBy(CorpusLockedCase::id).map { case ->
        kotlinx.serialization.json.JsonObject(sortedMapOf(
            "golden" to case.golden.toJson(),
            "golden_height" to kotlinx.serialization.json.JsonPrimitive(case.goldenHeight),
            "golden_width" to kotlinx.serialization.json.JsonPrimitive(case.goldenWidth),
            "id" to kotlinx.serialization.json.JsonPrimitive(case.id),
            "input" to case.input.toJson(),
            "input_type" to kotlinx.serialization.json.JsonPrimitive(case.inputType.name.lowercase()),
            "render_size" to kotlinx.serialization.json.JsonPrimitive(case.renderSize),
        ))
    }),
    "schema_version" to kotlinx.serialization.json.JsonPrimitive(schemaVersion),
    "tag" to kotlinx.serialization.json.JsonObject(sortedMapOf(
        "name" to kotlinx.serialization.json.JsonPrimitive(tag.name),
        "peeled_commit" to kotlinx.serialization.json.JsonPrimitive(tag.peeledCommit),
        "tag_object" to kotlinx.serialization.json.JsonPrimitive(tag.tagObject),
    )),
))

private fun CorpusAsset.toJson() = kotlinx.serialization.json.JsonObject(sortedMapOf(
    "object_id" to kotlinx.serialization.json.JsonPrimitive(objectId),
    "path" to kotlinx.serialization.json.JsonPrimitive(path),
    "sha256" to kotlinx.serialization.json.JsonPrimitive(sha256),
))
