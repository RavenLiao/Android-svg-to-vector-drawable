package io.github.ravenliao.svg2vd.upstream

import java.io.EOFException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import java.util.zip.GZIPInputStream

sealed interface SourceMaterial {
    data class Archive(val path: Path) : SourceMaterial
    data class Blob(val path: Path) : SourceMaterial
}

class SourceSynchronizationException(message: String) : IllegalStateException(message)

private fun moveAtomically(source: Path, target: Path): Path =
    try {
        Files.move(source, target, ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(source, target)
    }

class SourceSynchronizer(
    private val move: (Path, Path) -> Path = ::moveAtomically,
    private val workspaceRoot: Path? = null,
    private val materialFor: (SourceArchive) -> SourceMaterial,
) {
    fun synchronize(manifestPath: Path, scope: UpstreamScope, buildDirectory: Path): Path {
        val manifest = readCandidateManifest(manifestPath)
        verifyScope(manifest, scope)
        val build = buildDirectory.toAbsolutePath().normalize()
        ensureNoSymbolicLinksInExistingPathChain(build)
        val upstream = build.resolve("upstream").normalize()
        val target = upstream.resolve(manifest.tag.name).normalize()
        if (!target.startsWith(upstream) || manifest.tag.name.contains('/') || manifest.tag.name.contains('\\')) {
            throw SourceSynchronizationException("candidate tag is not a safe build directory name")
        }
        ensureDirectory(upstream, "upstream build directory")
        ensureDirectoryOrAbsent(target, "candidate target")
        val staging = Files.createTempDirectory(upstream, ".${manifest.tag.name}.staging-")
        ensureDirectory(staging, "candidate staging directory")
        try {
            manifest.sourceArchives.sortedBy(SourceArchive::path).forEach { archive ->
                val output = staging.resolve(safeRelativePath(archive.path)).normalize()
                if (!output.startsWith(staging)) throw SourceSynchronizationException("source archive escapes build directory: ${archive.path}")
                when (val material = materialFor(archive)) {
                    is SourceMaterial.Archive -> {
                        if (archive.path in scope.blobPaths) throw SourceSynchronizationException("scope requires blob materialization: ${archive.path}")
                        extractArchive(material.path, archive, output)
                    }
                    is SourceMaterial.Blob -> {
                        if (archive.path !in scope.blobPaths) throw SourceSynchronizationException("scope requires archive materialization: ${archive.path}")
                        copyBlob(material.path, archive, output)
                    }
                }
            }
            replaceTarget(staging, target)
        } catch (error: Exception) {
            if (Files.exists(staging, NOFOLLOW_LINKS)) deleteRecursively(staging)
            throw error
        }
        return target
    }

    private fun replaceTarget(staging: Path, target: Path) {
        var backup: Path? = null
        var backupConfirmed = false
        var installed = false
        try {
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                backup = target.resolveSibling(".${target.fileName}.backup-${UUID.randomUUID()}")
                ensureDirectoryOrAbsent(backup, "candidate backup")
                move(target, backup)
                if (!isNonSymbolicDirectory(backup) || Files.exists(target, NOFOLLOW_LINKS)) {
                    throw SourceSynchronizationException("candidate backup move did not produce a confirmed backup")
                }
                backupConfirmed = true
            }
            ensureDirectoryOrAbsent(target, "candidate target")
            move(staging, target)
            ensureDirectory(target, "candidate target")
            installed = true
            backup?.let(::deleteRecursively)
        } catch (error: Exception) {
            if (installed || backup == null) {
                throw replacementFailure(error, cleanupStaging(staging))
            }
            val backupPath = backup
            val backupCanRestore = isNonSymbolicDirectory(backupPath) &&
                (backupConfirmed || !Files.exists(target, NOFOLLOW_LINKS))
            if (!backupCanRestore) {
                throw replacementFailure(error, cleanupStaging(staging))
            }
            val cleanupError = runCatching {
                if (Files.exists(target, NOFOLLOW_LINKS)) deleteRecursively(target)
            }.exceptionOrNull()
            if (cleanupError != null) {
                throw replacementFailure(error, cleanupError, cleanupStaging(staging))
            }
            val restoreError = runCatching {
                move(backupPath, target)
                ensureDirectory(target, "candidate target")
            }.exceptionOrNull()
            val stagingCleanupError = cleanupStaging(staging)
            if (restoreError != null) throw replacementFailure(error, restoreError, stagingCleanupError)
            throw replacementFailure(error, stagingCleanupError)
        }
    }

    private fun cleanupStaging(staging: Path): Throwable? = runCatching {
        if (Files.exists(staging, NOFOLLOW_LINKS)) deleteRecursively(staging)
    }.exceptionOrNull()

    private fun replacementFailure(primary: Exception, vararg recovery: Throwable?): SourceSynchronizationException =
        SourceSynchronizationException("candidate target replacement failed: ${primary.message}").also { failure ->
            failure.addSuppressed(primary)
            recovery.filterNotNull().forEach(failure::addSuppressed)
        }

    private fun deleteRecursively(path: Path) {
        if (Files.isSymbolicLink(path)) throw SourceSynchronizationException("refusing to delete symbolic link: $path")
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun ensureDirectory(path: Path, description: String) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS)) {
                throw SourceSynchronizationException("$description must be a non-symbolic directory: $path")
            }
            return
        }
        Files.createDirectories(path)
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw SourceSynchronizationException("$description must be a non-symbolic directory: $path")
        }
    }

    private fun ensureNoSymbolicLinksInExistingPathChain(path: Path) {
        var current: Path? = path
        val boundary = workspaceRoot?.toAbsolutePath()?.normalize()
        if (boundary != null && !path.startsWith(boundary)) {
            throw SourceSynchronizationException("build directory escapes the workspace: $path")
        }
        do {
            if (Files.exists(current!!, NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw SourceSynchronizationException("build directory path contains a symbolic link: $current")
            }
            if (current == boundary || boundary == null && Files.exists(current, NOFOLLOW_LINKS)) return
            current = current.parent
        } while (current != null)
        if (boundary != null) throw SourceSynchronizationException("build directory is not below the workspace: $path")
    }

    private fun isNonSymbolicDirectory(path: Path): Boolean =
        Files.exists(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.isDirectory(path, NOFOLLOW_LINKS)

    private fun ensureDirectoryOrAbsent(path: Path, description: String) {
        if (Files.exists(path, NOFOLLOW_LINKS) && (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS))) {
            throw SourceSynchronizationException("$description must be absent or a non-symbolic directory: $path")
        }
    }

    private fun verifyScope(manifest: CandidateManifest, scope: UpstreamScope) {
        if (manifest.scopeVersion != scope.version || manifest.scopeSha256 != scope.sha256) {
            throw SourceSynchronizationException("candidate scope does not match the current controlled scope")
        }
        val expected = scope.paths.toSet()
        val entries = manifest.scopeEntries.map(ScopeEntry::path)
        val archives = manifest.sourceArchives.map(SourceArchive::path)
        if (entries.size != expected.size || archives.size != expected.size || entries.toSet() != expected || archives.toSet() != expected) {
            throw SourceSynchronizationException("candidate does not declare exactly the current source scope")
        }
    }

    private fun copyBlob(blob: Path, archive: SourceArchive, output: Path) {
        verifyHash(blob, archive)
        if (output.fileName == null) throw SourceSynchronizationException("blob target must be a file: ${archive.path}")
        Files.createDirectories(output.parent)
        Files.newInputStream(blob).use { input -> Files.newOutputStream(output).use(input::copyTo) }
    }

    private fun extractArchive(archivePath: Path, archive: SourceArchive, output: Path) {
        verifyHash(archivePath, archive)
        Files.createDirectories(output)
        GZIPInputStream(Files.newInputStream(archivePath)).use { input ->
            while (true) {
                val header = readBlock(input) ?: return
                if (header.all { it == 0.toByte() }) {
                    readBlock(input)
                    return
                }
                val name = header.string(0, 100).let { base ->
                    header.string(345, 155).let { prefix -> if (prefix.isBlank()) base else "$prefix/$base" }
                }
                val size = header.octal(124, 12)
                val type = header[156].toInt().toChar()
                if (type in setOf('x', 'g')) {
                    // Gitiles archives carry POSIX PAX metadata before regular files.
                    skipExact(input, size)
                    skipExact(input, (512 - size % 512) % 512)
                    continue
                }
                if (name.isBlank() || type !in setOf('\u0000', '0', '5')) throw SourceSynchronizationException("unsupported tar entry in ${archive.path}: $name")
                val file = output.resolve(safeRelativePath(name)).normalize()
                if (!file.startsWith(output)) throw SourceSynchronizationException("archive entry escapes source path: $name")
                if (Files.exists(file)) throw SourceSynchronizationException("duplicate archive entry: $name")
                if (type == '5') {
                    Files.createDirectories(file)
                    skipExact(input, size)
                    skipExact(input, (512 - size % 512) % 512)
                    continue
                }
                Files.createDirectories(file.parent)
                Files.newOutputStream(file).use { stream -> copyExact(input, stream, size) }
                skipExact(input, (512 - size % 512) % 512)
            }
        }
    }

    private fun verifyHash(path: Path, archive: SourceArchive) {
        if (!Files.isRegularFile(path)) throw SourceSynchronizationException("material is not a regular file for ${archive.path}")
        if (sha256Hex(Files.readAllBytes(path)) != archive.sha256) throw SourceSynchronizationException("material hash does not match candidate for ${archive.path}")
    }

    private fun safeRelativePath(value: String): Path {
        val path = Path.of(value).normalize()
        if (path.isAbsolute || path.startsWith("..") || path.toString() in setOf("", ".")) {
            throw SourceSynchronizationException("path escapes controlled source scope: $value")
        }
        return path
    }

    private fun readBlock(input: InputStream): ByteArray? {
        val block = ByteArray(512)
        var offset = 0
        while (offset < block.size) {
            val count = input.read(block, offset, block.size - offset)
            if (count < 0) return if (offset == 0) null else throw EOFException("truncated tar header")
            offset += count
        }
        return block
    }

    private fun copyExact(input: InputStream, output: java.io.OutputStream, length: Int) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) throw EOFException("truncated tar entry")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun skipExact(input: InputStream, length: Int) {
        var remaining = length
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong()).toInt()
            if (skipped == 0) {
                if (input.read() < 0) throw EOFException("truncated tar padding")
                remaining -= 1
            } else remaining -= skipped
        }
    }

    private fun ByteArray.string(offset: Int, length: Int): String =
        copyOfRange(offset, offset + length).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)

    private fun ByteArray.octal(offset: Int, length: Int): Int =
        string(offset, length).trim().toIntOrNull(8) ?: throw SourceSynchronizationException("invalid tar size")
}
