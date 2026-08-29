package io.github.ravenliao.svg2vd.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO

const val CORPUS_GITILES_PATH = "sdk-common/src/test/resources/testData/vectordrawable"

data class CorpusTreeEntry(val path: String, val objectId: String, val sha256: String = "")

interface CorpusGitilesSource {
    fun tree(commit: String, path: String): List<CorpusTreeEntry>
    fun blob(commit: String, path: String): ByteArray
}

class CorpusSynchronizationException(message: String) : IllegalStateException(message)

class CorpusSynchronizer(private val source: CorpusGitilesSource) {
    fun synchronizeCorpus(candidateManifest: Path, destinationRoot: Path): Path {
        val candidate = checkedCandidate(candidateManifest)
        val entries = source.tree(candidate.tag.peeledCommit, CORPUS_GITILES_PATH)
        if (entries.isEmpty()) throw CorpusSynchronizationException("corpus tree is empty")
        val paths = entries.map(CorpusTreeEntry::path)
        if (paths.size != paths.toSet().size || paths.map(::asciiLower).size != paths.map(::asciiLower).toSet().size) {
            throw CorpusSynchronizationException("corpus tree has duplicate or case-folding paths")
        }
        val assets = entries.sortedBy(CorpusTreeEntry::path).map { entry ->
            try {
                safeCorpusPath(entry.path)
                val extension = entry.path.substringAfterLast('.', "").lowercase()
                if (extension !in setOf("svg", "xml", "png")) throw CorpusSynchronizationException("corpus asset has unsupported extension: ${entry.path}")
                require(entry.objectId.matches(Regex("[0-9a-f]{40}"))) { "corpus tree object id is invalid" }
                val bytes = source.blob(candidate.tag.peeledCommit, entry.path)
                if (gitBlobObjectId(bytes) != entry.objectId) throw CorpusSynchronizationException("corpus blob object id does not match tree: ${entry.path}")
                if (entry.sha256.isNotEmpty() && sha256Hex(bytes) != entry.sha256) throw CorpusSynchronizationException("corpus blob hash does not match tree: ${entry.path}")
                CorpusAsset(entry.path, entry.objectId, sha256Hex(bytes)) to bytes
            } catch (error: CorpusSynchronizationException) {
                throw error
            } catch (error: Exception) {
                throw CorpusSynchronizationException("corpus asset is invalid: ${entry.path}: ${error.message}")
            }
        }
        val assetsByPath = assets.associate { (asset, bytes) -> asset.path to bytes }
        val pngMetadata = assets.filter { it.first.path.lowercase().endsWith(".png") }.associate { (asset, bytes) ->
            asset.path to decodePng(asset.path, bytes)
        }
        val grouped = assets.groupBy { asciiLower(it.first.path.substringAfterLast('/').substringBeforeLast('.')) }
        val cases = mutableListOf<CorpusRenderableCase>()
        val unpaired = mutableListOf<CorpusUnpairedAsset>()
        grouped.forEach { (id, group) ->
            val inputs = group.filter { it.first.path.substringAfterLast('.').lowercase() in setOf("svg", "xml") }
            val goldens = group.filter { it.first.path.substringAfterLast('.').lowercase() == "png" }
            if (inputs.size > 1) throw CorpusSynchronizationException("corpus input basename is ambiguous: $id")
            if (goldens.size > 1) throw CorpusSynchronizationException("corpus PNG basename is ambiguous: $id")
            val input = inputs.singleOrNull()
            val golden = goldens.singleOrNull()
            when {
                input != null && golden != null -> {
                    val image = pngMetadata.getValue(golden.first.path)
                    cases += CorpusRenderableCase(
                        id = id,
                        inputPath = input.first.path,
                        inputType = if (input.first.path.lowercase().endsWith(".svg")) CorpusInputType.SVG else CorpusInputType.XML,
                        goldenPngPath = golden.first.path,
                        goldenWidth = image.width,
                        goldenHeight = image.height,
                        renderSize = maxOf(image.width, image.height),
                    )
                }
                input != null -> unpaired += CorpusUnpairedAsset(input.first.path, "no_matching_png")
                golden != null -> unpaired += CorpusUnpairedAsset(golden.first.path, "orphan_png")
            }
        }
        return writeCorpusManifest(
            CorpusManifest(1, candidate.tag, "Apache-2.0", assets.map { it.first }, cases, unpaired),
            destinationRoot,
            assetsByPath,
        )
    }
}

private data class PngMetadata(val width: Int, val height: Int)

private fun decodePng(path: String, bytes: ByteArray): PngMetadata {
    val image = try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (error: Exception) {
        throw CorpusSynchronizationException("corpus PNG cannot be decoded: $path: ${error.message}")
    } ?: throw CorpusSynchronizationException("corpus PNG cannot be decoded: $path")
    if (image.width <= 0 || image.height <= 0) throw CorpusSynchronizationException("corpus PNG has non-positive dimensions: $path")
    return PngMetadata(image.width, image.height)
}

fun synchronizeCorpus(candidateManifest: Path, destinationRoot: Path): Path =
    CorpusSynchronizer(GitilesCorpusClient()).synchronizeCorpus(candidateManifest, destinationRoot)

class LockedCandidateDiscoverer(
    private val scope: UpstreamScope,
    private val source: CandidateSource,
    private val engineRuntimeClosureSha256: String,
    private val distributionLockSha256: String,
) {
    fun discoverLockedCandidate(corpusLock: Path, candidateOutputDirectory: Path): Path {
        val lock = try { readCorpusLock(corpusLock) } catch (error: Exception) {
            throw CorpusSynchronizationException("corpus lock is invalid: ${error.message}")
        }
        val entries = scope.paths.sorted().map { ScopeEntry(it, source.objectIdAt(lock.tag.peeledCommit, it)) }
        val material = linkedMapOf<SourceArchive, ByteArray>()
        val archives = entries.map { entry ->
            val bytes = if (entry.path in scope.blobPaths) source.blob(lock.tag.peeledCommit, entry.path) else source.archive(lock.tag.peeledCommit, entry.path)
            SourceArchive(entry.path, entry.objectId, sha256Hex(bytes)).also { material[it] = bytes }
        }
        val candidate = CandidateManifest(
            schemaVersion = 1,
            scopeVersion = scope.version,
            scopeSha256 = scope.sha256,
            tag = lock.tag,
            engineFingerprint = fingerprint(scope.sha256, entries, engineRuntimeClosureSha256).sha256,
            scopeEntries = entries,
            sourceArchives = archives,
            engineRuntimeClosureSha256 = engineRuntimeClosureSha256,
            distributionLockSha256 = distributionLockSha256,
        )
        return writeCandidateManifest(candidate, candidateOutputDirectory).also { writeCandidateMaterials(it, material) }
    }
}

fun discoverLockedCandidate(corpusLock: Path, candidateOutputDirectory: Path): Path {
    val workspace = Path.of("").toAbsolutePath().normalize()
    val digests = workspace.resolve("upstream-sync/build/upstream-inputs/digests.txt")
    val values = java.nio.file.Files.readAllLines(digests).filter(String::isNotBlank)
    require(values.size == 2) { "build input digest file is invalid: $digests" }
    return LockedCandidateDiscoverer(loadScope(workspace.resolve("upstream-scope.yaml")), GitilesContentClient(), values[0], values[1])
        .discoverLockedCandidate(corpusLock, candidateOutputDirectory)
}

private fun checkedCandidate(path: Path): CandidateManifest {
    val bytes = try { java.nio.file.Files.readAllBytes(path) } catch (error: Exception) {
        throw CorpusSynchronizationException("candidate manifest cannot be read: ${error.message}")
    }
    if (path.fileName.toString() != "${sha256Hex(bytes)}.json") throw CorpusSynchronizationException("candidate manifest filename does not match canonical SHA-256")
    val candidate = try { readCandidateManifest(path) } catch (error: Exception) {
        throw CorpusSynchronizationException("candidate manifest is invalid: ${error.message}")
    }
    if (classifyTag(candidate.tag.name, GitilesRef(candidate.tag.tagObject, candidate.tag.peeledCommit)) !is TagClassification.AcceptedStable ||
        !candidate.tag.tagObject.matches(Regex("[0-9a-f]{40}")) || !candidate.tag.peeledCommit.matches(Regex("[0-9a-f]{40}"))) {
        throw CorpusSynchronizationException("candidate manifest has an invalid fixed tag identity")
    }
    (candidate.scopeEntries.map(ScopeEntry::path) + candidate.sourceArchives.map(SourceArchive::path)).forEach {
        try { safeCorpusPath(it) } catch (error: Exception) { throw CorpusSynchronizationException("candidate manifest has unsafe path: $it") }
    }
    return candidate
}

private fun asciiLower(value: String): String = buildString(value.length) {
    value.forEach { append(if (it in 'A'..'Z') it + ('a' - 'A') else it) }
}

private fun gitBlobObjectId(bytes: ByteArray): String {
    val prefix = "blob ${bytes.size}\u0000".toByteArray(StandardCharsets.UTF_8)
    return MessageDigest.getInstance("SHA-1").digest(prefix + bytes).joinToString("") { "%02x".format(it) }
}

class GitilesCorpusClient(
    private val transport: GitilesBytesTransport = DefaultGitilesBytesTransport,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val retryDelay: Duration = Duration.ofSeconds(1),
) : CorpusGitilesSource {
    private val archives = mutableMapOf<String, Map<String, ByteArray>>()

    override fun tree(commit: String, path: String): List<CorpusTreeEntry> = enumerate(commit, path, "")

    override fun blob(commit: String, path: String): ByteArray = archive(commit).getOrElse(path) {
        throw CorpusSynchronizationException("Gitiles corpus archive is missing blob: $path")
    }

    private fun enumerate(commit: String, fullPath: String, relative: String): List<CorpusTreeEntry> {
        val payload = contentJson(commit, fullPath)
        val entries = payload.getValue("entries").jsonArray
        return entries.flatMap { element ->
            val entry = element.jsonObject
            val name = entry.getValue("name").jsonPrimitive.content
            safeCorpusPath(name)
            val type = entry.getValue("type").jsonPrimitive.content
            val id = entry.getValue("id").jsonPrimitive.content
            val childRelative = listOf(relative, name).filter(String::isNotEmpty).joinToString("/")
            val childFullPath = "$fullPath/$name"
            when (type) {
                "tree" -> enumerate(commit, childFullPath, childRelative)
                "blob" -> listOf(CorpusTreeEntry(childRelative, id))
                else -> throw CorpusSynchronizationException("corpus tree contains unsupported entry type: $type")
            }
        }
    }

    private fun contentJson(commit: String, path: String) = try {
        val uri = gitilesObjectUri(commit, path)
        val text = get(uri).toString(Charsets.UTF_8)
        require(text.startsWith(")]}'\n")) { "Gitiles corpus tree is missing XSSI prefix" }
        Json.parseToJsonElement(text.removePrefix(")]}'\n")).jsonObject
    } catch (error: CorpusSynchronizationException) {
        throw error
    } catch (error: Exception) {
        throw CorpusSynchronizationException("Gitiles corpus tree is invalid: ${error.message}")
    }

    private fun archive(commit: String): Map<String, ByteArray> = archives.getOrPut(commit) {
        try {
            readTarGzip(get(gitilesArchiveUri(commit, CORPUS_GITILES_PATH)))
        } catch (error: CorpusSynchronizationException) {
            throw error
        } catch (error: Exception) {
            throw CorpusSynchronizationException("Gitiles corpus archive is invalid: ${error.message}")
        }
    }

    private fun get(uri: java.net.URI): ByteArray {
        return retryGitiles("corpus $uri", retryDelay, { transport.get(uri, timeout).asRetryResponse() }) { message ->
            throw CorpusSynchronizationException(message)
        }
    }
}

private fun GitilesBytesResponse.asRetryResponse() = GitilesResponse(statusCode, body, headers)

private fun readTarGzip(compressed: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { files ->
    GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
        while (true) {
            val header = input.readNBytes(512)
            if (header.isEmpty()) break
            if (header.size != 512) throw CorpusSynchronizationException("Gitiles corpus archive has a truncated tar header")
            if (header.all { it == 0.toByte() }) break
            val name = tarField(header, 0, 100)
            val prefix = tarField(header, 345, 155)
            val path = listOf(prefix, name).filter(String::isNotEmpty).joinToString("/")
            val size = tarOctal(header, 124, 12)
            if (size > Int.MAX_VALUE) throw CorpusSynchronizationException("Gitiles corpus archive entry is too large: $path")
            when (header[156].toInt().toChar()) {
                '\u0000', '0' -> {
                    safeCorpusPath(path)
                    val bytes = input.readNBytes(size.toInt())
                    if (bytes.size != size.toInt()) throw CorpusSynchronizationException("Gitiles corpus archive has a truncated entry: $path")
                    if (files.put(path, bytes) != null) throw CorpusSynchronizationException("Gitiles corpus archive has a duplicate entry: $path")
                }
                '5' -> {
                    safeCorpusPath(path)
                    if (size != 0L) throw CorpusSynchronizationException("Gitiles corpus archive directory has content: $path")
                }
                'g', 'x' -> {
                    if (input.readNBytes(size.toInt()).size != size.toInt()) {
                        throw CorpusSynchronizationException("Gitiles corpus archive has a truncated metadata entry")
                    }
                }
                else -> throw CorpusSynchronizationException("Gitiles corpus archive has an unsupported entry type: $path")
            }
            val padding = ((512 - size % 512) % 512).toInt()
            if (padding > 0 && input.readNBytes(padding).size != padding) {
                throw CorpusSynchronizationException("Gitiles corpus archive has a truncated padding block: $path")
            }
        }
    }
}

private fun tarField(header: ByteArray, offset: Int, length: Int): String =
    header.copyOfRange(offset, offset + length).toString(StandardCharsets.US_ASCII).trimEnd('\u0000')

private fun tarOctal(header: ByteArray, offset: Int, length: Int): Long {
    val value = tarField(header, offset, length).trim()
    return value.toLongOrNull(8) ?: throw CorpusSynchronizationException("Gitiles corpus archive has an invalid entry size")
}
