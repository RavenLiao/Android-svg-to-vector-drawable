package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Path
import java.util.zip.ZipFile

data class ClassMajor(val entry: String, val major: Int)
data class ClosureReport(
    val sourcePaths: List<String>,
    val externalArtifacts: List<String>,
    val offendingClasses: List<ClassMajor>,
    val undeclaredPackages: List<String>,
    val resources: List<String>,
)

class ClosureVerificationException(message: String) : IllegalStateException(message)

class ClosureVerifier(
    private val runtimeClosureSha256: String,
    private val distributionLockSha256: String,
    private val allowedPackages: Set<String>,
    private val declaredServices: Set<String>,
    private val declaredResources: Set<String>,
) {
    fun verify(manifestPath: Path, scope: UpstreamScope, jar: Path): ClosureReport {
        val manifest = readCandidateManifest(manifestPath)
        val errors = mutableListOf<String>()
        if (manifest.scopeVersion != scope.version || manifest.scopeSha256 != scope.sha256) errors += "candidate scope changed; regenerate manifest"
        if (manifest.engineRuntimeClosureSha256 != runtimeClosureSha256) errors += "engine runtime closure changed; regenerate manifest"
        if (manifest.distributionLockSha256 != distributionLockSha256) errors += "distribution lock changed; regenerate manifest"
        val paths = manifest.scopeEntries.map(ScopeEntry::path)
        if (paths.toSet() != scope.paths.toSet() || manifest.sourceArchives.map(SourceArchive::path).toSet() != scope.paths.toSet()) {
            errors += "candidate source scope is incomplete"
        }
        if (scope.paths.none { it == "sdk-common/src/main/java/com/android/ide/common/util/AssetUtil.java" }) errors += "missing AssetUtil source"
        if (scope.paths.none { it == "common/src/main/java/com/android/utils" }) errors += "missing com.android.utils source"

        val offending = mutableListOf<ClassMajor>()
        val undeclaredPackages = mutableSetOf<String>()
        val resources = mutableListOf<String>()
        ZipFile(jar.toFile()).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                when {
                    entry.name.endsWith(".class") -> {
                        val bytes = zip.getInputStream(entry).readBytes()
                        val major = classMajor(entry.name, bytes)
                        if (major > 55) offending += ClassMajor(entry.name, major)
                        val packageName = entry.name.substringBeforeLast('/', "").replace('/', '.')
                        if (packageName.isNotEmpty() && allowedPackages.none { packageName == it || packageName.startsWith("$it.") }) {
                            undeclaredPackages += packageName
                        }
                    }
                    entry.name.startsWith("META-INF/services/") -> {
                        if (entry.name !in declaredServices) errors += "undeclared ServiceLoader resource ${entry.name}"
                    }
                    entry.name != "META-INF/MANIFEST.MF" && !entry.name.startsWith("META-INF/") -> {
                        resources += entry.name
                        if (entry.name !in declaredResources) errors += "undeclared resource ${entry.name}"
                    }
                }
            }
        }
        offending.forEach { errors += "class ${it.entry} has major ${it.major}" }
        undeclaredPackages.forEach { errors += "undeclared package $it" }
        if (errors.isNotEmpty()) throw ClosureVerificationException(errors.sorted().joinToString("; "))
        return ClosureReport(paths.sorted(), listOf(jar.toAbsolutePath().normalize().toString()), offending.sortedBy(ClassMajor::entry), undeclaredPackages.sorted(), resources.sorted())
    }

    private fun classMajor(entry: String, bytes: ByteArray): Int {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))) {
            throw ClosureVerificationException("invalid class header: $entry")
        }
        return ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
    }
}

fun verifyJava11Closure(manifest: Path, scope: UpstreamScope, jar: Path): ClosureReport {
    val candidate = readCandidateManifest(manifest)
    return ClosureVerifier(
        candidate.engineRuntimeClosureSha256,
        candidate.distributionLockSha256,
        setOf("com.android", "com.google.common", "org.jetbrains.annotations"),
        emptySet(),
        emptySet(),
    ).verify(manifest, scope, jar)
}
