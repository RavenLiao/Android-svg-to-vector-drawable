package io.github.ravenliao.svg2vd.verification

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditArtifactsTest {
    @Test
    fun `release JAR process command is headless`() {
        assertTrue(
            releaseJarCommand(Path.of("/java"), Path.of("/svg2vd.jar"), listOf("render")).contains("-Djava.awt.headless=true"),
        )
    }

    @Test
    fun `successful case records jar hash CLI JSON output paths and summary`() {
        val root = Files.createTempDirectory("svg2vd-success-audit")
        val jar = root.resolve("svg2vd.jar").also { it.writeText("release jar") }
        val xml = root.resolve("actual.xml").also { it.writeText("<vector/>") }
        val png = root.resolve("actual.png").also { it.writeText("PNG") }
        val response = JarResponse(0, "{\"command\":\"render\",\"outcome\":\"success\"}\n", "", CommandResult("render", "success"))
        val artifacts = root.resolve("artifacts")
        Files.createDirectories(artifacts.resolve("plain"))
        artifacts.resolve("plain/stale-delta.png").writeText("stale")

        writeSuccessArtifacts(artifacts, "plain", "{\"assets\":[]}", jar, listOf("render" to response), xml, png)

        val audit = root.resolve("artifacts/plain/audit.json")
        assertTrue(Files.isRegularFile(audit))
        val text = Files.readString(audit)
        assertTrue(text.contains("jar_sha256"))
        assertTrue(text.contains("render.json"))
        assertTrue(text.contains("actual.xml"))
        assertTrue(text.contains("actual.png"))
        assertEquals(response.stdout, Files.readString(root.resolve("artifacts/plain/render.json")))
        assertEquals("<vector/>", Files.readString(root.resolve("artifacts/plain/actual.xml")))
        assertEquals("PNG", Files.readString(root.resolve("artifacts/plain/actual.png")))
        assertTrue(!Files.exists(root.resolve("artifacts/plain/stale-delta.png")))
    }

    @Test
    fun `failed case records tested jar hash and replaces stale artifacts`() {
        val root = Files.createTempDirectory("svg2vd-failure-audit")
        val jar = root.resolve("svg2vd.jar").also { it.writeText("release jar") }
        val png = root.resolve("actual.png").also { it.writeText("PNG") }
        val artifacts = root.resolve("artifacts")
        artifacts.resolve("failed").also(Files::createDirectories).resolve("stale.txt").writeText("stale")

        writeFailureArtifacts(artifacts, "failed", "{}", jar, "render stdout", "render stderr", null, png, byteArrayOf(1, 2, 3))

        val failure = artifacts.resolve("failed")
        val audit = failure.resolve("audit.json")
        assertTrue(Files.isRegularFile(audit))
        assertTrue(Files.readString(audit).contains(sha256(jar)))
        assertTrue(Files.isRegularFile(failure.resolve("actual.png")))
        assertTrue(Files.isRegularFile(failure.resolve("delta.png")))
        assertTrue(!Files.exists(failure.resolve("stale.txt")))
    }

    private fun sha256(path: java.nio.file.Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it) }
}
