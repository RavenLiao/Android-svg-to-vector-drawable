package io.github.ravenliao.svg2vd.engine

import io.github.ravenliao.svg2vd.upstream.CandidateManifest
import io.github.ravenliao.svg2vd.upstream.ClosureVerificationException
import io.github.ravenliao.svg2vd.upstream.ClosureVerifier
import io.github.ravenliao.svg2vd.upstream.KNOWN_LEGACY_TAGS
import io.github.ravenliao.svg2vd.upstream.ScopeEntry
import io.github.ravenliao.svg2vd.upstream.SourceArchive
import io.github.ravenliao.svg2vd.upstream.UpstreamScope
import io.github.ravenliao.svg2vd.upstream.UpstreamTag
import io.github.ravenliao.svg2vd.upstream.fingerprint
import io.github.ravenliao.svg2vd.upstream.writeCandidateManifest
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Java11ClosureTest {
    @Test
    fun `closure rejects a studio 2026 1 2 manifest missing Android utility sources`() {
        val scope = scope("sdk-common/src/main/java/com/android/ide/common/vectordrawable", "sdk-common/src/main/java/com/android/ide/common/util/AssetUtil.java")
        val manifest = manifest(scope, "runtime", "locks")

        val failure = assertFailsWith<ClosureVerificationException> {
            verifier().verify(manifest, scope, java11Jar())
        }

        assertTrue(failure.message!!.contains("com.android.utils"))
    }

    @Test
    fun `closure rejects missing AssetUtil source and Java 12 dependency classes`() {
        val scope = scope("sdk-common/src/main/java/com/android/ide/common/vectordrawable", "common/src/main/java/com/android/utils")
        val manifest = manifest(scope, "runtime", "locks")

        val failure = assertFailsWith<ClosureVerificationException> {
            verifier().verify(manifest, scope, java11Jar("com/google/common/Future.class" to 56))
        }

        assertTrue(failure.message!!.contains("AssetUtil"))
        assertTrue(failure.message!!.contains("major 56"))
    }

    @Test
    fun `closure rejects undeclared packages services resources and controlled input drift`() {
        val scope = completeScope()
        val manifest = manifest(scope, "runtime", "locks")
        val jar = java11Jar(
            "com/android/outside/Unscoped.class" to 55,
            "META-INF/services/com.example.UnknownService" to 0,
            "icons/unknown.png" to 0,
        )

        val policyFailure = assertFailsWith<ClosureVerificationException> { verifier().verify(manifest, scope, jar) }
        assertTrue(policyFailure.message!!.contains("com.android.outside"))
        assertTrue(policyFailure.message!!.contains("UnknownService"))
        assertTrue(policyFailure.message!!.contains("icons/unknown.png"))
        assertFailsWith<ClosureVerificationException> { verifier(runtime = "changed").verify(manifest, scope, java11Jar()) }
        assertFailsWith<ClosureVerificationException> { verifier(locks = "changed").verify(manifest, scope, java11Jar()) }
        assertFailsWith<ClosureVerificationException> { verifier().verify(manifest, scope.copy(version = 8), java11Jar()) }
    }

    private fun completeScope() = scope(
        "sdk-common/src/main/java/com/android/ide/common/vectordrawable",
        "sdk-common/src/main/java/com/android/ide/common/util/AssetUtil.java",
        "common/src/main/java/com/android/utils",
    )

    private fun scope(vararg paths: String) = UpstreamScope(7, paths.toList(), KNOWN_LEGACY_TAGS)

    private fun manifest(scope: UpstreamScope, runtime: String, locks: String): Path {
        val entries = scope.paths.map { ScopeEntry(it, "object-${it.hashCode()}") }
        val candidate = CandidateManifest(
            schemaVersion = 1,
            scopeVersion = scope.version,
            scopeSha256 = scope.sha256,
            tag = UpstreamTag("studio-2026.1.2", "tag-object", "peeled-commit"),
            engineFingerprint = fingerprint(scope.sha256, entries, runtime).sha256,
            scopeEntries = entries,
            sourceArchives = scope.paths.map { SourceArchive(it, "archive-${it.hashCode()}", "hash-${it.hashCode()}") },
            engineRuntimeClosureSha256 = runtime,
            distributionLockSha256 = locks,
        )
        return writeCandidateManifest(candidate, Files.createTempDirectory("candidate"))
    }

    private fun verifier(runtime: String = "runtime", locks: String = "locks") = ClosureVerifier(
        runtimeClosureSha256 = runtime,
        distributionLockSha256 = locks,
        allowedPackages = setOf("com.android.ide.common.vectordrawable", "com.android.ide.common.util", "com.android.utils", "com.google.common"),
        declaredServices = emptySet(),
        declaredResources = emptySet(),
    )

    private fun java11Jar(vararg entries: Pair<String, Int>): Path {
        val jar = Files.createTempFile("closure", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(ZipEntry("com/android/ide/common/vectordrawable/Svg2Vector.class"))
            output.write(classHeader(55))
            output.closeEntry()
            entries.forEach { (name, major) ->
                output.putNextEntry(ZipEntry(name))
                output.write(if (name.endsWith(".class")) classHeader(major) else byteArrayOf(1))
                output.closeEntry()
            }
        }
        return jar
    }

    private fun classHeader(major: Int) = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, (major shr 8).toByte(), major.toByte())
}
