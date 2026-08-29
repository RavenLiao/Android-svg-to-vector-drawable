package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class UpstreamScopeTest {
    @Test
    fun `scope yaml has a canonical hash independent of declaration order`() {
        val first = Files.createTempFile("scope-first", ".yaml")
        val second = Files.createTempFile("scope-second", ".yaml")
        Files.writeString(first, """
            version: 1
            paths:
              - b/path
              - a/path
            history_allowlist:
              - studio-1.4
              - studio-1.5
              - studio-2.0
              - studio-2.0-rc1
              - studio-2.2
              - studio-2.2-preview3
              - studio-2.3
              - studio-3.0
        """.trimIndent())
        Files.writeString(second, """
            history_allowlist:
              - studio-3.0
              - studio-2.3
              - studio-2.2-preview3
              - studio-2.2
              - studio-2.0-rc1
              - studio-2.0
              - studio-1.5
              - studio-1.4
            paths:
              - a/path
              - b/path
            version: 1
        """.trimIndent())

        assertEquals(loadScope(first).sha256, loadScope(second).sha256)
    }

    @Test
    fun `scope rejects an edited historical allowlist`() {
        val scope = Files.createTempFile("scope-invalid", ".yaml")
        Files.writeString(scope, "version: 1\npaths: [a/path]\nhistory_allowlist: [studio-1.4]\n")

        assertFailsWith<IllegalArgumentException> { loadScope(scope) }
    }

    @Test
    fun `scope hash changes when controlled paths change`() {
        val root = Files.createTempFile("scope-original", ".yaml")
        Files.writeString(root, """
            version: 1
            paths: [a/path]
            history_allowlist: [studio-1.4, studio-1.5, studio-2.0, studio-2.0-rc1, studio-2.2, studio-2.2-preview3, studio-2.3, studio-3.0]
        """.trimIndent())
        val original = loadScope(root)
        val changed = original.copy(paths = original.paths + "new-controlled-path")

        assertNotEquals(original.sha256, changed.sha256)
    }

    @Test
    fun `scope fingerprints explicit blob materialization and rejects blobs outside paths`() {
        val valid = Files.createTempFile("scope-blobs", ".yaml")
        Files.writeString(valid, """
            version: 1
            paths: [directory, AssetUtil.java]
            blob_paths: [AssetUtil.java]
            history_allowlist: [studio-1.4, studio-1.5, studio-2.0, studio-2.0-rc1, studio-2.2, studio-2.2-preview3, studio-2.3, studio-3.0]
        """.trimIndent())
        assertEquals(setOf("AssetUtil.java"), loadScope(valid).blobPaths)

        val invalid = Files.createTempFile("scope-invalid-blobs", ".yaml")
        Files.writeString(invalid, """
            version: 1
            paths: [directory]
            blob_paths: [AssetUtil.java]
            history_allowlist: [studio-1.4, studio-1.5, studio-2.0, studio-2.0-rc1, studio-2.2, studio-2.2-preview3, studio-2.3, studio-3.0]
        """.trimIndent())
        assertFailsWith<IllegalArgumentException> { loadScope(invalid) }
    }

    @Test
    fun `controlled studio 2026 1 2 seed scope contains only the audited directory and AssetUtil blob`() {
        val scope = loadScope(Path.of("..", "upstream-scope.yaml").toAbsolutePath().normalize())

        assertEquals(
            setOf(
                "sdk-common/src/main/java/com/android/ide/common/vectordrawable",
                "sdk-common/src/main/java/com/android/ide/common/util/AssetUtil.java",
                "annotations/src/main/java/com/android/annotations",
                "common/src/main/java/com/android/SdkConstants.java",
                "common/src/main/java/com/android/ProgressManagerAdapter.java",
                "common/src/main/java/com/android/sdklib/AndroidVersion.java",
                "common/src/main/java/com/android/utils/Pair.java",
                "common/src/main/java/com/android/utils/DecimalUtils.java",
                "common/src/main/java/com/android/utils/PositionXmlParser.java",
                "common/src/main/java/com/android/utils/XmlUtils.java",
                "common/src/main/java/com/android/ide/common/blame/SourceFile.java",
                "common/src/main/java/com/android/ide/common/blame/SourceFilePosition.java",
                "common/src/main/java/com/android/ide/common/blame/SourcePosition.java",
                "common/src/main/java/com/android/sdklib/AndroidApiLevel.kt",
                "common/src/main/java/com/android/sdklib/AndroidMajorVersion.kt",
                "common/agp-version/Version.java",
                "common/src/main/java/com/android/utils/CharSequences.java",
                "common/src/main/java/com/android/utils/CharSequenceReader.java",
            ),
            scope.paths.toSet(),
        )
        assertEquals(
            setOf(
                "sdk-common/src/main/java/com/android/ide/common/util/AssetUtil.java",
                "common/src/main/java/com/android/SdkConstants.java",
                "common/src/main/java/com/android/ProgressManagerAdapter.java",
                "common/src/main/java/com/android/sdklib/AndroidVersion.java",
                "common/src/main/java/com/android/utils/Pair.java",
                "common/src/main/java/com/android/utils/DecimalUtils.java",
                "common/src/main/java/com/android/utils/PositionXmlParser.java",
                "common/src/main/java/com/android/utils/XmlUtils.java",
                "common/src/main/java/com/android/ide/common/blame/SourceFile.java",
                "common/src/main/java/com/android/ide/common/blame/SourceFilePosition.java",
                "common/src/main/java/com/android/ide/common/blame/SourcePosition.java",
                "common/src/main/java/com/android/sdklib/AndroidApiLevel.kt",
                "common/src/main/java/com/android/sdklib/AndroidMajorVersion.kt",
                "common/agp-version/Version.java",
                "common/src/main/java/com/android/utils/CharSequences.java",
                "common/src/main/java/com/android/utils/CharSequenceReader.java",
            ),
            scope.blobPaths,
        )
    }
}
