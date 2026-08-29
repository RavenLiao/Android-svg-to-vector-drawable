package io.github.ravenliao.svg2vd.verification

import java.awt.image.BufferedImage
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CommandResult(val command: String, val outcome: String)

data class JarResponse(val exitCode: Int, val stdout: String, val stderr: String, val result: CommandResult)

fun runJar(javaExecutable: Path, jar: Path, arguments: List<String>): JarResponse {
    require(javaExecutable.isAbsolute && jar.isAbsolute) { "java executable and JAR must be absolute paths" }
    val process = ProcessBuilder(releaseJarCommand(javaExecutable, jar, arguments)).start()
    val stdout = process.inputStream.readFullyAsync()
    val stderr = process.errorStream.readFullyAsync()
    val exitCode = process.waitFor()
    val output = stdout.join()
    val error = stderr.join()
    val response = try {
        Json.parseToJsonElement(output).jsonObject
    } catch (failure: Exception) {
        throw IllegalStateException("release JAR did not emit one JSON response: $output", failure)
    }
    return JarResponse(
        exitCode = exitCode,
        stdout = output,
        stderr = error,
        result = CommandResult(
            command = response.getValue("command").jsonPrimitive.content,
            outcome = response.getValue("outcome").jsonPrimitive.content,
        ),
    )
}

internal fun releaseJarCommand(javaExecutable: Path, jar: Path, arguments: List<String>): List<String> =
    listOf(javaExecutable.toString(), "-Djava.awt.headless=true", "-jar", jar.toString()) + arguments

internal fun requireJava11(javaExecutable: Path) {
    require(javaExecutable.isAbsolute && Files.isRegularFile(javaExecutable) && Files.isExecutable(javaExecutable)) {
        "javaExecutable must be an absolute executable file: $javaExecutable"
    }
    val process = ProcessBuilder(javaExecutable.toString(), "-version").redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
    require(process.waitFor() == 0) { "javaExecutable -version failed: $output" }
    val feature = Regex("""version\s+[\"']?(\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()
        ?: throw IllegalArgumentException("javaExecutable did not report a Java feature version: $output")
    require(feature == 11) { "javaExecutable must report feature version 11, got $feature" }
}

internal fun requireReleaseJar(jar: Path) {
    require(jar.isAbsolute && Files.isRegularFile(jar) && !Files.isSymbolicLink(jar)) {
        "svg2vdJar must be an absolute regular file: $jar"
    }
}

internal data class CorpusConfiguration(
    val manifestPath: Path,
    val root: Path,
    val jar: Path,
    val javaExecutable: Path,
    val artifactRoot: Path,
    val manifest: CorpusManifest,
) {
    val assetRoot: Path = root.resolve("assets")

    fun validate() {
        requireJava11(javaExecutable)
        requireReleaseJar(jar)
    }

    companion object {
        fun fromProperties(properties: Map<String, String>): CorpusConfiguration {
            fun property(name: String): Path = Path.of(
                properties[name]?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("$name is required"),
            ).toAbsolutePath().normalize()
            val manifestPath = property("svg2vd.corpusManifest")
            val root = property("svg2vd.corpusRoot")
            return CorpusConfiguration(
                manifestPath = manifestPath,
                root = root,
                jar = property("svg2vd.jar"),
                javaExecutable = property("svg2vd.javaExecutable"),
                artifactRoot = property("svg2vd.corpusArtifactRoot"),
                manifest = readCorpusManifest(manifestPath, root),
            )
        }
    }
}

internal data class CorpusManifest(
    val renderableCases: List<CorpusCase>,
)

internal data class CorpusCase(
    val id: String,
    val inputPath: String,
    val inputType: String,
    val goldenPngPath: String,
    val renderSize: Int,
)

internal fun readCorpusManifest(manifestPath: Path, root: Path): CorpusManifest {
    require(manifestPath.isAbsolute && root.isAbsolute && Files.isRegularFile(manifestPath) && Files.isDirectory(root) && !Files.isSymbolicLink(root)) {
        "corpusManifest and corpusRoot must be absolute non-symbolic existing paths"
    }
    val materializationRoot = root.toRealPath()
    require(manifestPath.toRealPath().parent == materializationRoot) {
        "corpus manifest must be directly inside its materialization root"
    }
    val manifestBytes = Files.readAllBytes(manifestPath)
    require(materializationRoot.fileName.toString() == sha256(manifestBytes)) {
        "corpus materialization root does not match manifest canonical bytes SHA-256"
    }
    val assetsRoot = materializationRoot.resolve("assets")
    require(Files.isDirectory(assetsRoot) && !Files.isSymbolicLink(assetsRoot)) {
        "corpus materialization root has no non-symbolic assets directory"
    }
    val manifest = Json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject
    val assets = manifest.getValue("assets").jsonArray.map { element ->
        val value = element.jsonObject
        value.getValue("path").jsonPrimitive.content to value.getValue("sha256").jsonPrimitive.content
    }
    require(assets.isNotEmpty() && assets.map { it.first }.toSet().size == assets.size) { "corpus manifest assets are empty or duplicated" }
    val assetPaths = assets.map { it.first }.toSet()
    assets.forEach { (relativePath, expectedHash) ->
        val asset = safeCorpusAsset(assetsRoot, relativePath)
        require(expectedHash.matches(Regex("[0-9a-f]{64}")) && sha256(asset) == expectedHash) {
            "corpus asset hash does not match manifest: $relativePath"
        }
    }
    val renderableCases = manifest.getValue("renderable_cases").jsonArray.map { element ->
        val value = element.jsonObject
        CorpusCase(
            id = value.getValue("id").jsonPrimitive.content,
            inputPath = value.getValue("input_path").jsonPrimitive.content,
            inputType = value.getValue("input_type").jsonPrimitive.content,
            goldenPngPath = value.getValue("golden_png_path").jsonPrimitive.content,
            renderSize = value.getValue("render_size").jsonPrimitive.content.toInt(),
        )
    }
    require(renderableCases.isNotEmpty() && renderableCases.map(CorpusCase::id).toSet().size == renderableCases.size) {
        "corpus manifest has no unique renderable cases"
    }
    renderableCases.forEach { case ->
        require(case.id.matches(Regex("[A-Za-z0-9_-]+")) && case.inputType in setOf("svg", "xml") && case.renderSize > 0) {
            "corpus renderable case is invalid: ${case.id}"
        }
        require(case.inputPath in assetPaths && case.goldenPngPath in assetPaths) { "corpus case is not backed by declared assets: ${case.id}" }
        require(case.inputPath.endsWith(".${case.inputType}") && case.goldenPngPath.endsWith(".png")) { "corpus case path type is invalid: ${case.id}" }
    }
    return CorpusManifest(renderableCases)
}

internal fun readPng(path: Path): BufferedImage = ImageIO.read(path.toFile())
    ?: throw IllegalArgumentException("PNG cannot be decoded: $path")

internal fun writeFailureArtifacts(
    artifactRoot: Path,
    caseId: String,
    manifest: String,
    jar: Path,
    stdout: String?,
    stderr: String?,
    actualXml: Path?,
    actualPng: Path?,
    deltaPng: ByteArray?,
) {
    require(caseId.matches(Regex("[A-Za-z0-9_-]+"))) { "unsafe corpus case id: $caseId" }
    requireReleaseJar(jar)
    val root = artifactRoot.resolve(caseId).normalize()
    require(root.startsWith(artifactRoot)) { "corpus artifact path escapes root" }
    if (Files.exists(root)) {
        require(!Files.isSymbolicLink(root)) { "corpus artifact case directory must not be symbolic: $root" }
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
    Files.createDirectories(root)
    Files.writeString(root.resolve("manifest.json"), manifest)
    Files.writeString(root.resolve("stdout.txt"), stdout.orEmpty())
    Files.writeString(root.resolve("stderr.txt"), stderr.orEmpty())
    val xml = actualXml?.takeIf(Files::isRegularFile)?.let { source ->
        Files.copy(source, root.resolve("actual.xml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        JsonPrimitive("actual.xml")
    }
    val png = actualPng?.takeIf(Files::isRegularFile)?.let { source ->
        Files.copy(source, root.resolve("actual.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        JsonPrimitive("actual.png")
    }
    val delta = deltaPng?.let {
        Files.write(root.resolve("delta.png"), it)
        JsonPrimitive("delta.png")
    }
    val audit = JsonObject(sortedMapOf(
        "case_id" to JsonPrimitive(caseId),
        "jar_sha256" to JsonPrimitive(sha256(jar)),
        "manifest" to JsonPrimitive("manifest.json"),
        "outputs" to JsonObject(sortedMapOf<String, kotlinx.serialization.json.JsonElement>().apply {
            xml?.let { put("xml", it) }
            png?.let { put("png", it) }
            delta?.let { put("delta", it) }
        }),
        "stderr" to JsonPrimitive("stderr.txt"),
        "stdout" to JsonPrimitive("stdout.txt"),
    ))
    Files.writeString(root.resolve("audit.json"), Json.encodeToString(JsonObject.serializer(), audit) + "\n")
}

internal fun writeSuccessArtifacts(
    artifactRoot: Path,
    caseId: String,
    manifest: String,
    jar: Path,
    responses: List<Pair<String, JarResponse>>,
    actualXml: Path?,
    actualPng: Path?,
) {
    require(caseId.matches(Regex("[A-Za-z0-9_-]+"))) { "unsafe corpus case id: $caseId" }
    requireReleaseJar(jar)
    val root = artifactRoot.resolve(caseId).normalize()
    require(root.startsWith(artifactRoot)) { "corpus artifact path escapes root" }
    if (Files.exists(root)) {
        require(!Files.isSymbolicLink(root)) { "corpus artifact case directory must not be symbolic: $root" }
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
    Files.createDirectories(root)
    Files.writeString(root.resolve("manifest.json"), manifest)
    val responseEntries = responses.map { (name, response) ->
        require(name.matches(Regex("[A-Za-z0-9_-]+"))) { "unsafe CLI audit name: $name" }
        val output = "$name.json"
        Files.writeString(root.resolve(output), response.stdout)
        JsonObject(sortedMapOf(
            "command" to JsonPrimitive(response.result.command),
            "exit_code" to JsonPrimitive(response.exitCode),
            "json" to JsonPrimitive(output),
            "outcome" to JsonPrimitive(response.result.outcome),
        ))
    }
    val xml = actualXml?.takeIf(Files::isRegularFile)?.let { source ->
        Files.copy(source, root.resolve("actual.xml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        JsonPrimitive("actual.xml")
    }
    val png = actualPng?.takeIf(Files::isRegularFile)?.let { source ->
        Files.copy(source, root.resolve("actual.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        JsonPrimitive("actual.png")
    }
    val audit = JsonObject(sortedMapOf(
        "case_id" to JsonPrimitive(caseId),
        "cli" to JsonArray(responseEntries),
        "jar_sha256" to JsonPrimitive(sha256(jar)),
        "manifest" to JsonPrimitive("manifest.json"),
        "outputs" to JsonObject(sortedMapOf<String, kotlinx.serialization.json.JsonElement>().apply {
            xml?.let { put("xml", it) }
            png?.let { put("png", it) }
        }),
    ))
    Files.writeString(root.resolve("audit.json"), Json.encodeToString(JsonObject.serializer(), audit) + "\n")
}

private fun safeCorpusAsset(root: Path, relativePath: String): Path {
    require(relativePath.isNotBlank() && !relativePath.startsWith("/") && !relativePath.contains('\\') && !relativePath.split('/').any { it in setOf("", ".", "..") }) {
        "corpus asset path is unsafe: $relativePath"
    }
    val output = root.resolve(relativePath).normalize()
    require(output.startsWith(root) && Files.isRegularFile(output) && !Files.isSymbolicLink(output)) {
        "corpus asset is not a regular in-root file: $relativePath"
    }
    return output
}

private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(path))
    .joinToString("") { "%02x".format(it) }

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun InputStream.readFullyAsync(): CompletableFuture<String> = CompletableFuture.supplyAsync {
    readBytes().toString(StandardCharsets.UTF_8)
}
