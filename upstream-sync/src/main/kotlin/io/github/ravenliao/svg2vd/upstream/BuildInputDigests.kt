package io.github.ravenliao.svg2vd.upstream

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.streams.asSequence

data class RuntimeArtifact(val coordinate: String, val file: Path)

fun runtimeClosureSha256(artifacts: Iterable<RuntimeArtifact>): String {
    val records = artifacts.map { artifact ->
        require(artifact.coordinate.isNotBlank()) { "runtime artifact coordinate must not be blank" }
        require(Files.isRegularFile(artifact.file)) { "runtime artifact is not a regular file: ${artifact.file}" }
        RuntimeRecord(
            artifact.coordinate.toByteArray(Charsets.UTF_8),
            artifact.file.fileName.toString().toByteArray(Charsets.UTF_8),
            sha256Bytes(Files.readAllBytes(artifact.file)),
        )
    }.sortedWith { left, right ->
        compareUnsigned(left.coordinate, right.coordinate)
            .takeIf { it != 0 }
            ?: compareUnsigned(left.fileName, right.fileName).takeIf { it != 0 }
            ?: compareUnsigned(left.sha256, right.sha256)
    }
    val canonical = ByteArrayOutputStream()
    records.forEachIndexed { index, record ->
        if (index > 0) canonical.write(0)
        canonical.write(record.coordinate)
        canonical.write(0)
        canonical.write(record.fileName)
        canonical.write(0)
        canonical.write(record.sha256)
    }
    return sha256Hex(canonical.toByteArray())
}

private data class RuntimeRecord(val coordinate: ByteArray, val fileName: ByteArray, val sha256: ByteArray)

fun distributionLockSha256(workspace: Path, moduleRoots: Set<String>): String {
    val root = workspace.toAbsolutePath().normalize()
    val lockFiles = controlledDistributionLockfiles(root, moduleRoots).map { path ->
        val relative = root.relativize(path)
        relative to relative.toString().replace('\\', '/').toByteArray(Charsets.UTF_8)
    }.sortedWith { left, right -> compareUnsigned(left.second, right.second) }
    val canonical = ByteArrayOutputStream()
    lockFiles.forEach { (relative, name) ->
        canonical.write(name)
        canonical.write(0)
        canonical.write(sha256Bytes(Files.readAllBytes(root.resolve(relative))))
        canonical.write(0)
    }
    return sha256Hex(canonical.toByteArray())
}

private fun controlledDistributionLockfiles(workspace: Path, moduleRoots: Set<String>): List<Path> {
    val moduleLocks = moduleRoots.mapNotNull { moduleRoot ->
        val relative = Path.of(moduleRoot).normalize()
        require(!relative.isAbsolute && !relative.startsWith("..")) { "module root escapes workspace: $moduleRoot" }
        val module = workspace.resolve(relative).normalize()
        if (!module.startsWith(workspace) || Files.isSymbolicLink(module)) return@mapNotNull null
        val lockfile = module.resolve("gradle.lockfile")
        lockfile.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
    }
    val dependencyLocks = workspace.resolve("gradle/dependency-locks")
    val dependencyLockfiles = if (Files.isDirectory(dependencyLocks, NOFOLLOW_LINKS) && !Files.isSymbolicLink(dependencyLocks)) {
        Files.walk(dependencyLocks).use { paths ->
            paths.asSequence()
                .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .filter { path -> path.fileName.toString().endsWith(".lockfile") }
                .filter { path -> workspace.relativize(path).none { it.toString() == "build" } }
                .toList()
        }
    } else {
        emptyList()
    }
    return (moduleLocks + dependencyLockfiles).distinct()
}

private fun sha256Bytes(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

private fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
    first.zip(second).forEach { (left, right) ->
        val difference = (left.toInt() and 0xff) - (right.toInt() and 0xff)
        if (difference != 0) return difference
    }
    return first.size.compareTo(second.size)
}
