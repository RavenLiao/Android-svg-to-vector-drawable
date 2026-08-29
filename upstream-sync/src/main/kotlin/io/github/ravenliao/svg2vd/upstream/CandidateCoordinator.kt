package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class CandidateManifestCoordinator(
    private val refs: () -> GitilesRefs,
    private val source: CandidateSource,
) {
    fun discover(
        tagName: String,
        scope: UpstreamScope,
        engineRuntimeClosureSha256: String,
        distributionLockSha256: String,
        candidatesDirectory: Path,
    ): Path {
        val discoveredRefs = refs()
        val classification = classifyTag(tagName, discoveredRefs.refs["refs/tags/$tagName"]
            ?: throw DiscoveryBlockedException("requested tag is absent from the one Gitiles refs response: $tagName"))
        val accepted = classification as? TagClassification.AcceptedStable
            ?: throw DiscoveryBlockedException("requested tag is not an accepted stable tag: $tagName")
        val entries = scope.paths.sorted().map { path -> ScopeEntry(path, source.objectIdAt(accepted.tag.peeledCommit, path)) }
        val acquired = linkedMapOf<SourceArchive, ByteArray>()
        val archives = entries.map { entry ->
            val bytes = if (entry.path in scope.blobPaths) source.blob(accepted.tag.peeledCommit, entry.path) else source.archive(accepted.tag.peeledCommit, entry.path)
            SourceArchive(entry.path, entry.objectId, sha256Hex(bytes)).also { acquired[it] = bytes }
        }
        val candidate = CandidateManifest(
            schemaVersion = 1,
            scopeVersion = scope.version,
            scopeSha256 = scope.sha256,
            tag = accepted.tag,
            engineFingerprint = fingerprint(scope.sha256, entries, engineRuntimeClosureSha256).sha256,
            scopeEntries = entries,
            sourceArchives = archives,
            engineRuntimeClosureSha256 = engineRuntimeClosureSha256,
            distributionLockSha256 = distributionLockSha256,
        )
        return writeCandidateManifest(candidate, candidatesDirectory).also { manifest -> writeCandidateMaterials(manifest, acquired) }
    }
}

fun candidateMaterialPath(manifestPath: Path, archive: SourceArchive): Path {
    val name = manifestPath.fileName.toString()
    require(name.endsWith(".json")) { "candidate manifest must end in .json" }
    return manifestPath.resolveSibling("${name.removeSuffix(".json")}.materials").resolve("${archive.objectId}-${archive.sha256}")
}

fun writeCandidateMaterials(manifestPath: Path, materials: Map<SourceArchive, ByteArray>) {
    materials.forEach { (archive, bytes) ->
        require(sha256Hex(bytes) == archive.sha256) { "candidate material hash does not match archive entry for ${archive.path}" }
        val destination = candidateMaterialPath(manifestPath, archive)
        Files.createDirectories(destination.parent)
        try {
            Files.write(destination, bytes, CREATE_NEW)
        } catch (alreadyExists: java.nio.file.FileAlreadyExistsException) {
            require(Files.readAllBytes(destination).contentEquals(bytes)) { "candidate material collision for ${archive.path}" }
        }
    }
}

class CandidateMaterialStore(
    private val manifestPath: Path,
    private val scope: UpstreamScope,
)
{
    fun materialFor(archive: SourceArchive): SourceMaterial {
        val material = candidateMaterialPath(manifestPath, archive)
        if (!Files.isRegularFile(material)) throw SourceSynchronizationException("candidate material is missing for ${archive.path}")
        if (sha256Hex(Files.readAllBytes(material)) != archive.sha256) {
            throw SourceSynchronizationException("candidate material hash does not match candidate for ${archive.path}")
        }
        return if (archive.path in scope.blobPaths) SourceMaterial.Blob(material) else SourceMaterial.Archive(material)
    }
}

fun validateCandidateInputs(
    manifestPath: Path,
    scope: UpstreamScope,
    engineRuntimeClosureSha256: String,
    distributionLockSha256: String,
): CandidateManifest {
    val bytes = try {
        Files.readAllBytes(manifestPath)
    } catch (error: Exception) {
        throw SourceSynchronizationException("candidate manifest cannot be read: ${error.message}")
    }
    val name = manifestPath.fileName.toString()
    if (!name.matches(Regex("[0-9a-f]{64}\\.json")) || name.removeSuffix(".json") != sha256Hex(bytes)) {
        throw SourceSynchronizationException("candidate manifest filename does not match its canonical SHA-256")
    }
    val candidate = try {
        readCandidateManifest(manifestPath)
    } catch (error: IllegalArgumentException) {
        throw SourceSynchronizationException("candidate manifest is invalid: ${error.message}")
    }
    if (candidate.schemaVersion != 1) {
        throw SourceSynchronizationException("candidate manifest has unsupported schema version: ${candidate.schemaVersion}")
    }
    val tag = classifyTag(candidate.tag.name, GitilesRef(candidate.tag.tagObject, candidate.tag.peeledCommit))
    if (tag !is TagClassification.AcceptedStable || !candidate.tag.tagObject.matches(GIT_OBJECT_ID) || !candidate.tag.peeledCommit.matches(GIT_OBJECT_ID)) {
        throw SourceSynchronizationException("candidate manifest has an invalid accepted stable tag")
    }
    if (candidate.scopeVersion != scope.version || candidate.scopeSha256 != scope.sha256) {
        throw SourceSynchronizationException("candidate scope does not match the current controlled scope")
    }
    val scopeEntries = candidate.scopeEntries.associateBy(ScopeEntry::path)
    if (scopeEntries.size != candidate.scopeEntries.size || scopeEntries.keys != scope.paths.toSet() || scopeEntries.values.any { !it.objectId.matches(GIT_OBJECT_ID) }) {
        throw SourceSynchronizationException("candidate scope entries do not exactly match the controlled scope")
    }
    val archives = candidate.sourceArchives.associateBy(SourceArchive::path)
    if (archives.size != candidate.sourceArchives.size || archives.keys != scopeEntries.keys || archives.values.any { !it.objectId.matches(GIT_OBJECT_ID) || !it.sha256.matches(SHA256) }) {
        throw SourceSynchronizationException("candidate source archives are invalid or do not exactly match the controlled scope")
    }
    if (archives.any { (path, archive) -> scopeEntries.getValue(path).objectId != archive.objectId }) {
        throw SourceSynchronizationException("candidate source archive object ids do not match scope entries")
    }
    if (candidate.engineRuntimeClosureSha256 != engineRuntimeClosureSha256) {
        throw SourceSynchronizationException("candidate engine runtime closure does not match the current locked runtime")
    }
    if (candidate.distributionLockSha256 != distributionLockSha256) {
        throw SourceSynchronizationException("candidate distribution locks do not match the current controlled locks")
    }
    return candidate
}

private val GIT_OBJECT_ID = Regex("[0-9a-f]{40}")
private val SHA256 = Regex("[0-9a-f]{64}")

class SyncCandidateCoordinator(
    private val workspaceRoot: Path,
    private val scope: UpstreamScope,
    private val engineBuildDirectory: Path,
    private val materialFor: (SourceArchive) -> SourceMaterial,
    private val engineRuntimeClosureSha256: String,
    private val distributionLockSha256: String,
) {
    fun sync(manifestPath: Path): Path {
        val workspace = workspaceRoot.toRealPath()
        val candidate = manifestPath.toRealPath()
        if (candidate.startsWith(workspace)) throw SourceSynchronizationException("candidate manifest must be outside the workspace: $candidate")
        validateCandidateInputs(candidate, scope, engineRuntimeClosureSha256, distributionLockSha256)
        val target = SourceSynchronizer(
            workspaceRoot = workspaceRoot.toAbsolutePath().normalize(),
            materialFor = materialFor,
        ).synchronize(candidate, scope, engineBuildDirectory)
        val inputs = engineBuildDirectory.resolve("sync-candidate")
        Files.createDirectories(inputs)
        Files.copy(candidate, inputs.resolve(candidate.fileName), REPLACE_EXISTING, COPY_ATTRIBUTES)
        return target
    }
}
