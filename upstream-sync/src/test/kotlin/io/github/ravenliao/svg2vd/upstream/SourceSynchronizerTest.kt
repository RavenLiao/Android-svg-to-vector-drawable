package io.github.ravenliao.svg2vd.upstream

import java.io.ByteArrayOutputStream
import java.nio.file.Files.createSymbolicLink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceSynchronizerTest {
    @Test
    fun `synchronizer materializes only manifest archives below the Gradle build directory`() {
        val archives = Files.createTempDirectory("archives")
        val first = writeArchive(archives.resolve("vectors.tar.gz"), mapOf("Svg2Vector.java" to "class Svg2Vector {}"))
        val second = writeBlob(archives.resolve("asset.blob"), "class AssetUtil {}")
        val scope = scope("vectors", "asset/AssetUtil.java")
        val manifest = writeManifest(scope, listOf(archive("vectors", first), archive("asset/AssetUtil.java", second)))
        val build = Files.createTempDirectory("gradle-build")

        val materialized = SourceSynchronizer { archive ->
            if (archive.path == "asset/AssetUtil.java") SourceMaterial.Blob(second)
            else SourceMaterial.Archive(archives.resolve("${archive.objectId}.tar.gz"))
        }
            .synchronize(manifest, scope, build)

        assertEquals(build.resolve("upstream/studio-2026.1.2").normalize(), materialized)
        assertEquals("class Svg2Vector {}", Files.readString(materialized.resolve("vectors/Svg2Vector.java")))
        assertEquals("class AssetUtil {}", Files.readString(materialized.resolve("asset/AssetUtil.java")))
        assertFalse(Files.exists(build.resolve("unexpected")))
    }

    @Test
    fun `synchronizer rejects archive traversal and manifest paths outside scope`() {
        val archives = Files.createTempDirectory("archives")
        val traversal = writeArchive(archives.resolve("vectors.tar.gz"), mapOf("../escaped.java" to "bad"))
        val scope = scope("vectors")
        val traversalManifest = writeManifest(scope, listOf(archive("vectors", traversal)))
        val build = Files.createTempDirectory("gradle-build")
        val synchronizer = SourceSynchronizer { archive -> SourceMaterial.Archive(archives.resolve("${archive.objectId}.tar.gz")) }

        assertFailsWith<SourceSynchronizationException> { synchronizer.synchronize(traversalManifest, scope, build) }

        val unknown = writeArchive(archives.resolve("outside.tar.gz"), mapOf("Source.java" to "class Source {}"))
        val outsideManifest = writeManifest(scope, listOf(archive("outside", unknown)))
        assertFailsWith<SourceSynchronizationException> { synchronizer.synchronize(outsideManifest, scope, build) }
        assertTrue(Files.list(build).use { it.noneMatch { path -> path.fileName.toString() == "escaped.java" } })
    }

    @Test
    fun `synchronizer accepts directory entries in Gitiles archives`() {
        val archives = Files.createTempDirectory("archives")
        val archivePath = writeArchiveWithDirectory(archives.resolve("vectors.tar.gz"))
        val scope = scope("vectors")
        val manifest = writeManifest(scope, listOf(archive("vectors", archivePath)))
        val build = Files.createTempDirectory("gradle-build")

        val materialized = SourceSynchronizer { SourceMaterial.Archive(archivePath) }.synchronize(manifest, scope, build)

        assertEquals("class Source {}", Files.readString(materialized.resolve("vectors/concurrency/Source.java")))
    }

    @Test
    fun `synchronizer replaces only its candidate tag output without retaining stale sources`() {
        val archives = Files.createTempDirectory("archives")
        val first = writeArchive(archives.resolve("first.tar.gz"), mapOf("Source.java" to "class First {}"))
        val second = writeArchive(archives.resolve("second.tar.gz"), mapOf("Source.java" to "class Second {}"))
        val scope = scope("vectors")
        val firstManifest = writeManifest(scope, listOf(archive("vectors", first)))
        val secondManifest = writeManifest(scope, listOf(archive("vectors", second)))
        val build = Files.createTempDirectory("gradle-build")
        val synchronizer = SourceSynchronizer { archive ->
            SourceMaterial.Archive(if (archive.sha256 == sha256Hex(Files.readAllBytes(first))) first else second)
        }

        synchronizer.synchronize(firstManifest, scope, build)
        val materialized = synchronizer.synchronize(secondManifest, scope, build)

        assertEquals("class Second {}", Files.readString(materialized.resolve("vectors/Source.java")))
    }

    @Test
    fun `synchronizer refuses a symbolic link upstream directory`() {
        val archives = Files.createTempDirectory("archives")
        val source = writeArchive(archives.resolve("vectors.tar.gz"), mapOf("Source.java" to "class Source {}"))
        val scope = scope("vectors")
        val manifest = writeManifest(scope, listOf(archive("vectors", source)))
        val build = Files.createTempDirectory("gradle-build")
        val outside = Files.createTempDirectory("outside-build")
        val upstream = build.resolve("upstream")
        val linked = runCatching { createSymbolicLink(upstream, outside) }.isSuccess
        assumeTrue(linked, "symbolic links are not supported by this file system")

        assertFailsWith<SourceSynchronizationException> { SourceSynchronizer { SourceMaterial.Archive(source) }.synchronize(manifest, scope, build) }
        assertFalse(Files.exists(outside.resolve("studio-2026.1.2")))
    }

    @Test
    fun `synchronizer refuses a symbolic link build directory`() {
        val archives = Files.createTempDirectory("archives")
        val source = writeArchive(archives.resolve("vectors.tar.gz"), mapOf("Source.java" to "class Source {}"))
        val scope = scope("vectors")
        val manifest = writeManifest(scope, listOf(archive("vectors", source)))
        val parent = Files.createTempDirectory("build-parent")
        val outside = Files.createTempDirectory("outside-build")
        val build = parent.resolve("build")
        val linked = runCatching { createSymbolicLink(build, outside) }.isSuccess
        assumeTrue(linked, "symbolic links are not supported by this file system")

        assertFailsWith<SourceSynchronizationException> { SourceSynchronizer { SourceMaterial.Archive(source) }.synchronize(manifest, scope, build) }
        assertFalse(Files.exists(outside.resolve("upstream")))
    }

    @Test
    fun `synchronizer restores prior output when the staging move fails`() {
        val archives = Files.createTempDirectory("archives")
        val first = writeArchive(archives.resolve("first.tar.gz"), mapOf("Source.java" to "class First {}"))
        val second = writeArchive(archives.resolve("second.tar.gz"), mapOf("Source.java" to "class Second {}"))
        val scope = scope("vectors")
        val firstManifest = writeManifest(scope, listOf(archive("vectors", first)))
        val secondManifest = writeManifest(scope, listOf(archive("vectors", second)))
        val build = Files.createTempDirectory("gradle-build")
        var failStagingMove = false
        val synchronizer = SourceSynchronizer(
            materialFor = { archive -> SourceMaterial.Archive(if (archive.sha256 == sha256Hex(Files.readAllBytes(first))) first else second) },
            move = { source, target ->
                if (failStagingMove && source.fileName.toString().contains(".staging-") && target.fileName.toString() == "studio-2026.1.2") {
                    throw java.nio.file.FileSystemException(source.toString(), target.toString(), "injected move failure")
                }
                Files.move(source, target, ATOMIC_MOVE)
            },
        )

        val target = synchronizer.synchronize(firstManifest, scope, build)
        failStagingMove = true

        assertFailsWith<SourceSynchronizationException> { synchronizer.synchronize(secondManifest, scope, build) }
        assertEquals("class First {}", Files.readString(target.resolve("vectors/Source.java")))
        assertTrue(Files.list(target.parent).use { paths -> paths.noneMatch { it.fileName.toString().contains(".staging-") || it.fileName.toString().contains(".backup-") } })
    }

    @Test
    fun `synchronizer restores old output after a failed staging move leaves a partial target`() {
        val fixture = replacementFixture()
        fixture.synchronizer.synchronize(fixture.firstManifest, fixture.scope, fixture.build)
        fixture.failStagingMove.value = true

        assertFailsWith<SourceSynchronizationException> { fixture.synchronizer.synchronize(fixture.secondManifest, fixture.scope, fixture.build) }

        assertEquals("class First {}", Files.readString(fixture.target.resolve("vectors/Source.java")))
        assertFalse(Files.exists(fixture.target.resolve("partial.txt")))
        assertTrue(Files.list(fixture.target.parent).use { paths -> paths.noneMatch { it.fileName.toString().contains(".staging-") || it.fileName.toString().contains(".backup-") } })
    }

    @Test
    fun `synchronizer retains backup when restore fails after a partial target`() {
        val fixture = replacementFixture()
        fixture.synchronizer.synchronize(fixture.firstManifest, fixture.scope, fixture.build)
        fixture.failStagingMove.value = true
        fixture.failRestoreMove.value = true

        assertFailsWith<SourceSynchronizationException> { fixture.synchronizer.synchronize(fixture.secondManifest, fixture.scope, fixture.build) }

        assertFalse(Files.exists(fixture.target.resolve("partial.txt")))
        val backup = Files.list(fixture.target.parent).use { paths ->
            paths.filter { it.fileName.toString().contains(".backup-") }.findFirst().orElseThrow()
        }
        assertEquals("class First {}", Files.readString(backup.resolve("vectors/Source.java")))
    }

    @Test
    fun `synchronizer keeps existing target when the initial backup move fails`() {
        val fixture = replacementFixture()
        fixture.synchronizer.synchronize(fixture.firstManifest, fixture.scope, fixture.build)
        fixture.failBackupMove.value = true

        assertFailsWith<SourceSynchronizationException> { fixture.synchronizer.synchronize(fixture.secondManifest, fixture.scope, fixture.build) }

        assertEquals("class First {}", Files.readString(fixture.target.resolve("vectors/Source.java")))
        assertTrue(Files.list(fixture.target.parent).use { paths -> paths.noneMatch { it.fileName.toString().contains(".staging-") || it.fileName.toString().contains(".backup-") } })
    }

    @Test
    fun `synchronizer restores target when backup move changes state before failing`() {
        val fixture = replacementFixture()
        fixture.synchronizer.synchronize(fixture.firstManifest, fixture.scope, fixture.build)
        fixture.failBackupAfterMove.value = true

        assertFailsWith<SourceSynchronizationException> { fixture.synchronizer.synchronize(fixture.secondManifest, fixture.scope, fixture.build) }

        assertEquals("class First {}", Files.readString(fixture.target.resolve("vectors/Source.java")))
        assertTrue(Files.list(fixture.target.parent).use { paths -> paths.noneMatch { it.fileName.toString().contains(".staging-") || it.fileName.toString().contains(".backup-") } })
    }

    private fun replacementFixture(): ReplacementFixture {
        val archives = Files.createTempDirectory("archives")
        val first = writeArchive(archives.resolve("first.tar.gz"), mapOf("Source.java" to "class First {}"))
        val second = writeArchive(archives.resolve("second.tar.gz"), mapOf("Source.java" to "class Second {}"))
        val scope = scope("vectors")
        val firstManifest = writeManifest(scope, listOf(archive("vectors", first)))
        val secondManifest = writeManifest(scope, listOf(archive("vectors", second)))
        val build = Files.createTempDirectory("gradle-build")
        val failStagingMove = Flag()
        val failRestoreMove = Flag()
        val failBackupMove = Flag()
        val failBackupAfterMove = Flag()
        val synchronizer = SourceSynchronizer(
            materialFor = { archive -> SourceMaterial.Archive(if (archive.sha256 == sha256Hex(Files.readAllBytes(first))) first else second) },
            move = { source, target ->
                if (failBackupMove.value && source.fileName.toString() == "studio-2026.1.2" && target.fileName.toString().contains(".backup-")) {
                    throw java.nio.file.FileSystemException(source.toString(), target.toString(), "injected backup failure")
                }
                if (failBackupAfterMove.value && source.fileName.toString() == "studio-2026.1.2" && target.fileName.toString().contains(".backup-")) {
                    Files.move(source, target, ATOMIC_MOVE)
                    throw java.nio.file.FileSystemException(source.toString(), target.toString(), "injected backup-after-move failure")
                }
                if (failStagingMove.value && source.fileName.toString().contains(".staging-") && target.fileName.toString() == "studio-2026.1.2") {
                    Files.createDirectories(target)
                    Files.writeString(target.resolve("partial.txt"), "partial")
                    throw java.nio.file.FileSystemException(source.toString(), target.toString(), "injected staging failure")
                }
                if (failRestoreMove.value && source.fileName.toString().contains(".backup-") && target.fileName.toString() == "studio-2026.1.2") {
                    throw java.nio.file.FileSystemException(source.toString(), target.toString(), "injected restore failure")
                }
                Files.move(source, target, ATOMIC_MOVE)
            },
        )
        return ReplacementFixture(synchronizer, scope, firstManifest, secondManifest, build, build.resolve("upstream/studio-2026.1.2"), failStagingMove, failRestoreMove, failBackupMove, failBackupAfterMove)
    }

    private data class ReplacementFixture(
        val synchronizer: SourceSynchronizer,
        val scope: UpstreamScope,
        val firstManifest: Path,
        val secondManifest: Path,
        val build: Path,
        val target: Path,
        val failStagingMove: Flag,
        val failRestoreMove: Flag,
        val failBackupMove: Flag,
        val failBackupAfterMove: Flag,
    )

    private class Flag(var value: Boolean = false)

    private fun scope(vararg paths: String) = UpstreamScope(7, paths.toList(), KNOWN_LEGACY_TAGS, paths.filterTo(mutableSetOf()) { it.endsWith(".java") })

    private fun archive(path: String, file: Path) = SourceArchive(path, file.fileName.toString().removeSuffix(".tar.gz"), sha256Hex(Files.readAllBytes(file)))

    private fun writeManifest(scope: UpstreamScope, archives: List<SourceArchive>): Path {
        val entries = archives.map { ScopeEntry(it.path, "object-${it.path}") }
        val candidate = CandidateManifest(
            schemaVersion = 1,
            scopeVersion = scope.version,
            scopeSha256 = scope.sha256,
            tag = UpstreamTag("studio-2026.1.2", "tag-object", "peeled-commit"),
            engineFingerprint = fingerprint(scope.sha256, entries, "runtime").sha256,
            scopeEntries = entries,
            sourceArchives = archives,
            engineRuntimeClosureSha256 = "runtime",
            distributionLockSha256 = "locks",
        )
        return writeCandidateManifest(candidate, Files.createTempDirectory("candidate"))
    }

    private fun writeArchive(path: Path, entries: Map<String, String>): Path {
        val tar = ByteArrayOutputStream()
        entries.forEach { (name, content) ->
            val bytes = content.toByteArray()
            val header = ByteArray(512)
            name.toByteArray().copyInto(header, endIndex = name.length)
            writeOctal(header, 100, 8, 33188)
            writeOctal(header, 124, 12, bytes.size)
            writeOctal(header, 136, 12, 0)
            header[156] = '0'.code.toByte()
            "ustar".toByteArray().copyInto(header, 257)
            header[262] = 0
            tar.write(header)
            tar.write(bytes)
            tar.write(ByteArray((512 - bytes.size % 512) % 512))
        }
        tar.write(ByteArray(1024))
        GZIPOutputStream(Files.newOutputStream(path)).use { it.write(tar.toByteArray()) }
        return path
    }

    private fun writeBlob(path: Path, content: String): Path {
        Files.writeString(path, content)
        return path
    }

    private fun writeArchiveWithDirectory(path: Path): Path {
        val tar = ByteArrayOutputStream()
        fun entry(name: String, content: String, type: Char) {
            val bytes = content.toByteArray()
            val header = ByteArray(512)
            name.toByteArray().copyInto(header, endIndex = name.length)
            writeOctal(header, 124, 12, bytes.size)
            header[156] = type.code.toByte()
            tar.write(header)
            tar.write(bytes)
            tar.write(ByteArray((512 - bytes.size % 512) % 512))
        }
        entry("concurrency/", "", '5')
        entry("concurrency/Source.java", "class Source {}", '0')
        tar.write(ByteArray(1024))
        GZIPOutputStream(Files.newOutputStream(path)).use { it.write(tar.toByteArray()) }
        return path
    }

    private fun writeOctal(target: ByteArray, offset: Int, length: Int, value: Int) {
        value.toString(8).padStart(length - 1, '0').toByteArray().copyInto(target, offset)
        target[offset + length - 1] = 0
    }
}
