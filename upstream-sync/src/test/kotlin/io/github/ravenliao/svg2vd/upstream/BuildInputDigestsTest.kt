package io.github.ravenliao.svg2vd.upstream

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BuildInputDigestsTest {
    @Test
    fun `runtime closure digest is canonical over locked artifact coordinates and bytes`() {
        val artifacts = Files.createTempDirectory("runtime-artifacts")
        val first = Files.writeString(artifacts.resolve("first.jar"), "first")
        val second = Files.writeString(artifacts.resolve("second.jar"), "second")

        val baseline = runtimeClosureSha256(
            listOf(
                RuntimeArtifact("org.example:second:2", second),
                RuntimeArtifact("org.example:first:1", first),
            ),
        )

        assertEquals("67de3d19896b99525523216c2226439a1acef8a62123fa30806a3f06044cde7d", baseline)
        assertEquals(baseline, runtimeClosureSha256(listOf(RuntimeArtifact("org.example:first:1", first), RuntimeArtifact("org.example:second:2", second))))
        Files.writeString(second, "changed")
        assertNotEquals(baseline, runtimeClosureSha256(listOf(RuntimeArtifact("org.example:first:1", first), RuntimeArtifact("org.example:second:2", second))))
    }

    @Test
    fun `runtime closure digest has a total order when coordinates repeat`() {
        val artifacts = Files.createTempDirectory("runtime-artifacts")
        val alpha = Files.writeString(artifacts.resolve("alpha.jar"), "alpha")
        val beta = Files.writeString(artifacts.resolve("beta.jar"), "beta")

        val first = runtimeClosureSha256(listOf(RuntimeArtifact("org.example:shared:1", beta), RuntimeArtifact("org.example:shared:1", alpha)))
        val second = runtimeClosureSha256(listOf(RuntimeArtifact("org.example:shared:1", alpha), RuntimeArtifact("org.example:shared:1", beta)))

        assertEquals(first, second)
    }

    @Test
    fun `distribution lock digest includes each controlled lockfile path and raw content hash`() {
        val workspace = Files.createTempDirectory("locked-workspace")
        Files.createDirectories(workspace.resolve("gradle/dependency-locks"))
        Files.createDirectories(workspace.resolve("engine"))
        Files.createDirectories(workspace.resolve("engine/build"))
        Files.createDirectories(workspace.resolve("nested-repository"))
        Files.writeString(workspace.resolve("gradle/dependency-locks/runtime.lockfile"), "root lock")
        Files.writeString(workspace.resolve("engine/gradle.lockfile"), "engine lock")

        val baseline = distributionLockSha256(workspace, setOf("engine"))
        Files.writeString(workspace.resolve("engine/build/gradle.lockfile"), "generated lock")
        Files.writeString(workspace.resolve("nested-repository/gradle.lockfile"), "uncontrolled lock")

        assertEquals(baseline, distributionLockSha256(workspace, setOf("engine")))
        Files.writeString(workspace.resolve("engine/gradle.lockfile"), "changed engine lock")

        assertNotEquals(baseline, distributionLockSha256(workspace, setOf("engine")))
    }

    @Test
    fun `distribution lock digest changes when identical module lock moves`() {
        val workspace = Files.createTempDirectory("locked-workspace")
        Files.createDirectories(workspace.resolve("first"))
        Files.createDirectories(workspace.resolve("second"))
        val first = workspace.resolve("first/gradle.lockfile")
        val second = workspace.resolve("second/gradle.lockfile")
        Files.writeString(first, "same lock")

        val firstDigest = distributionLockSha256(workspace, setOf("first", "second"))
        Files.move(first, second)

        assertNotEquals(firstDigest, distributionLockSha256(workspace, setOf("first", "second")))
    }
}
