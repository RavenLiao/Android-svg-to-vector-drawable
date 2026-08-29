package io.github.ravenliao.svg2vd.engine

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

data class WriteResult(
    val succeeded: Boolean,
    val diagnostics: List<EngineDiagnostic> = emptyList(),
)

interface FileOperations {
    fun createDirectories(directory: Path): Path
    fun createTempFile(directory: Path, prefix: String, suffix: String): Path
    fun writeAndForce(path: Path, bytes: ByteArray)
    fun createLink(target: Path, existing: Path): Path
    fun moveAtomically(source: Path, target: Path): Path
    fun deleteIfExists(path: Path): Boolean
}

object NioFileOperations : FileOperations {
    override fun createDirectories(directory: Path): Path = Files.createDirectories(directory)
    override fun createTempFile(directory: Path, prefix: String, suffix: String): Path = Files.createTempFile(directory, prefix, suffix)
    override fun writeAndForce(path: Path, bytes: ByteArray) {
        FileChannel.open(path, WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
    override fun createLink(target: Path, existing: Path): Path = Files.createLink(target, existing)
    override fun moveAtomically(source: Path, target: Path): Path = Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)
}

class AtomicFileWriter(private val files: FileOperations = NioFileOperations) {
    fun writeAtomically(target: Path, bytes: ByteArray, overwrite: Boolean): WriteResult {
        val directory = target.parent ?: Path.of(".")
        unsafeOutput(target)?.let { return it }
        val temporary = try {
            files.createDirectories(directory)
            unsafeOutput(target)?.let { return it }
            files.createTempFile(directory, ".${target.fileName}.", ".tmp")
        } catch (_: IOException) {
            return failure("filesystem_error", "Unable to create an output file.")
        }

        return try {
            unsafeOutput(temporary)?.let { return cleanup(temporary, it) }
            files.writeAndForce(temporary, bytes)
            // Java NIO cannot atomically validate arbitrary ancestors and publish a path.
            // Recheck immediately before commit and refuse every visible symbolic link.
            unsafeOutput(temporary)?.let { return cleanup(temporary, it) }
            unsafeOutput(target)?.let { return cleanup(temporary, it) }
            val result = if (overwrite) replace(target, temporary) else create(target, temporary)
            cleanup(temporary, result)
        } catch (_: IOException) {
            cleanup(temporary, failure("filesystem_error", "Unable to write an output file."))
        }
    }

    private fun create(target: Path, temporary: Path): WriteResult = try {
        files.createLink(target, temporary)
        WriteResult(true)
    } catch (_: FileAlreadyExistsException) {
        failure("output_exists", "Output already exists.")
    } catch (_: UnsupportedOperationException) {
        failure("filesystem_unsupported", "This filesystem does not support atomic hard-link publication.")
    } catch (_: IOException) {
        failure("filesystem_error", "Unable to publish an output file.")
    }

    private fun replace(target: Path, temporary: Path): WriteResult = try {
        files.moveAtomically(temporary, target)
        WriteResult(true)
    } catch (_: AtomicMoveNotSupportedException) {
        failure("filesystem_unsupported", "This filesystem does not support atomic replacement.")
    } catch (_: IOException) {
        failure("filesystem_error", "Unable to replace an output file.")
    }

    private fun cleanup(temporary: Path, result: WriteResult): WriteResult = try {
        files.deleteIfExists(temporary)
        result
    } catch (_: IOException) {
        result.copy(diagnostics = result.diagnostics + EngineDiagnostic(EngineDiagnosticSeverity.WARNING, "temp_cleanup_failed", "Temporary output cleanup failed."))
    }

    private fun failure(code: String, message: String) = WriteResult(false, listOf(EngineDiagnostic(EngineDiagnosticSeverity.ERROR, code, message)))

    private fun unsafeOutput(path: Path): WriteResult? = if (pathHasSymlink(path)) {
        failure("unsafe_symlink", "Output target or an ancestor is a symbolic link; refusing publication.")
    } else {
        null
    }

    private fun pathHasSymlink(path: Path): Boolean {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null) {
            if (Files.isSymbolicLink(current) && !isSystemRootAlias(current)) return true
            current = current.parent
        }
        return false
    }

    private fun isSystemRootAlias(path: Path): Boolean {
        val parent = path.parent ?: return false
        val privateAlias = parent.resolve("private").resolve(path.fileName)
        return parent == path.root && Files.exists(privateAlias) && runCatching { Files.isSameFile(path, privateAlias) }.getOrDefault(false)
    }
}
