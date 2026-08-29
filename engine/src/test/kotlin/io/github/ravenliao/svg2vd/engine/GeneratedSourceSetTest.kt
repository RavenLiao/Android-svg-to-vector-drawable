package io.github.ravenliao.svg2vd.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class GeneratedSourceSetTest {
    @Test
    fun `engine build wires the exact materialized upstream java directory into main source sets`() {
        val buildScript = sequenceOf(Path.of("engine", "build.gradle.kts"), Path.of("build.gradle.kts"))
            .filter { Files.exists(it) }
            .map(Files::readString)
            .first { it.contains("compileAllUpstreamEngineSources") }

        assertTrue(buildScript.contains("upstream/\$it"))
        assertTrue(buildScript.contains("sourceSets.named(\"main\")"))
        assertTrue(buildScript.contains("srcDir(upstreamJavaDirectory)"))
        assertTrue(buildScript.contains("compileAllUpstreamEngineSources"))
        assertTrue(buildScript.contains("candidateManifest"))
        assertTrue(buildScript.contains("JsonSlurper"))
        assertTrue(buildScript.contains("manifestTag"))
        assertTrue(buildScript.contains("upstreamTag").not())
    }
}
