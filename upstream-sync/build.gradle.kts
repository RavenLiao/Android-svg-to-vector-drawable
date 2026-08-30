
import groovy.json.JsonSlurper
import org.gradle.api.tasks.PathSensitivity
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.streams.asSequence

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml.engine)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}
tasks.test { useJUnitPlatform() }

val candidateManifest = providers.gradleProperty("candidateManifest")
val candidateOutputDirectory = providers.gradleProperty("candidateOutputDirectory")
val candidateTag = providers.gradleProperty("candidateTag")
val latestTagOutput = providers.gradleProperty("latestTagOutput")
val toolVersionFile = providers.gradleProperty("toolVersionFile")
val expectedToolVersion = providers.gradleProperty("expectedToolVersion")
val corpusLock = providers.gradleProperty("corpusLock")
val corpusOutputDirectory = providers.gradleProperty("corpusOutputDirectory")
val corpusManifestInput = providers.gradleProperty("corpusManifest")
val corpusLockOutput = providers.gradleProperty("corpusLockOutput")
val workspaceRoot = rootProject.layout.projectDirectory.asFile.toPath().toAbsolutePath().normalize()
val engineBuildDirectory = project(":engine").layout.buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
val engineRuntimeArtifacts = engineBuildDirectory.resolve("upstream-inputs/runtime-artifacts.txt")
val buildInputDigests = layout.buildDirectory.file("upstream-inputs/digests.txt")
val controlledModuleRoots = rootProject.allprojects.map { project ->
    workspaceRoot.relativize(project.layout.projectDirectory.asFile.toPath().toAbsolutePath().normalize()).toString().ifBlank { "." }
}.sorted()
val gitObjectId = Regex("[0-9a-f]{40}")
val sha256 = Regex("[0-9a-f]{64}")
val acceptedStableTag = Regex("^studio-[0-9]+\\.[0-9]+\\.[0-9]+(?:-patch0*[1-9][0-9]*)?$")
data class CandidateManifestInputs(val path: Path, val tagName: String, val materials: List<java.io.File>)
fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun safeCandidateManifestInputs(manifest: String): CandidateManifestInputs {
    val path = Path.of(manifest).toAbsolutePath().normalize()
    val bytes = Files.readAllBytes(path)
    require(path.fileName.toString().matches(Regex("[0-9a-f]{64}\\.json")) && path.fileName.toString().removeSuffix(".json") == sha256Hex(bytes)) {
        "candidate manifest filename does not match its canonical SHA-256: $manifest"
    }
    val root = JsonSlurper().parse(bytes) as? Map<*, *>
        ?: error("candidate manifest is not a JSON object: $manifest")
    require((root["schema_version"] as? Number)?.toString() == "1") { "candidate manifest has unsupported schema version: $manifest" }
    val tag = root["tag"] as? Map<*, *> ?: error("candidate manifest does not contain tag: $manifest")
    val tagName = tag["name"] as? String ?: error("candidate manifest tag has no name: $manifest")
    val tagObject = tag["tag_object"] as? String ?: error("candidate manifest tag has no tag_object: $manifest")
    val peeledCommit = tag["peeled_commit"] as? String ?: error("candidate manifest tag has no peeled_commit: $manifest")
    require(tagName.matches(acceptedStableTag) && !tagName.contains('/') && !tagName.contains('\\') && tagObject.matches(gitObjectId) && peeledCommit.matches(gitObjectId)) {
        "candidate manifest has an invalid accepted stable tag: $manifest"
    }
    val materialsDirectory = path.resolveSibling("${path.fileName.toString().removeSuffix(".json")}.materials").normalize()
    val archives = root["source_archives"] as? List<*> ?: error("candidate manifest does not contain source_archives: $manifest")
    val materials = archives.map { archive ->
        val entry = archive as? Map<*, *> ?: error("candidate manifest source archive is invalid: $manifest")
        val sourcePath = entry["path"] as? String ?: error("candidate manifest source archive has no path: $manifest")
        val safeSourcePath = runCatching { Path.of(sourcePath).normalize() }.getOrElse { error("candidate manifest source archive path is invalid: $manifest") }
        require(!safeSourcePath.isAbsolute && !safeSourcePath.startsWith("..") && safeSourcePath.toString() !in setOf("", ".")) {
            "candidate manifest source archive path escapes scope: $manifest"
        }
        val objectId = entry["object_id"] as? String ?: error("candidate manifest source archive has no object_id: $manifest")
        val contentSha256 = entry["sha256"] as? String ?: error("candidate manifest source archive has no sha256: $manifest")
        require(objectId.matches(gitObjectId) && contentSha256.matches(sha256)) { "candidate manifest source archive identity is invalid: $manifest" }
        materialsDirectory.resolve("$objectId-$contentSha256").normalize().also { material ->
            require(material.startsWith(materialsDirectory)) { "candidate material path escapes its sidecar directory: $manifest" }
        }.toFile()
    }
    return CandidateManifestInputs(path, tagName, materials)
}
fun controlledLockfiles(): List<java.io.File> {
    val moduleLocks = controlledModuleRoots.mapNotNull { moduleRoot ->
        val directory = workspaceRoot.resolve(moduleRoot).normalize()
        val lockfile = directory.resolve("gradle.lockfile")
        lockfile.takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory) && !Files.isSymbolicLink(it) }
    }
    val dependencyLocks = workspaceRoot.resolve("gradle/dependency-locks")
    val dependencyLockfiles = if (Files.isDirectory(dependencyLocks, NOFOLLOW_LINKS) && !Files.isSymbolicLink(dependencyLocks)) {
        Files.walk(dependencyLocks).use { paths ->
            paths.asSequence()
                .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .filter { it.fileName.toString().endsWith(".lockfile") }
                .filter { workspaceRoot.relativize(it).none { segment -> segment.toString() == "build" } }
                .toList()
        }
    } else emptyList()
    return (moduleLocks + dependencyLockfiles).distinct().map(Path::toFile)
}
fun controlledDistributionLockSha256(): String {
    val records = controlledLockfiles().map { lockfile ->
        val relative = workspaceRoot.relativize(lockfile.toPath()).toString().replace('\\', '/')
        relative.toByteArray(Charsets.UTF_8) to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(lockfile.toPath()))
    }.sortedWith { left, right -> compareUnsigned(left.first, right.first) }
    val canonical = ByteArrayOutputStream()
    records.forEach { (path, hash) ->
        canonical.write(path)
        canonical.write(0)
        canonical.write(hash)
        canonical.write(0)
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
}
fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
    first.zip(second).forEach { (left, right) ->
        val difference = (left.toInt() and 0xff) - (right.toInt() and 0xff)
        if (difference != 0) return difference
    }
    return first.size.compareTo(second.size)
}
val configuredCandidateInputs = candidateManifest.orNull?.let(::safeCandidateManifestInputs)
val candidateInputs = providers.provider {
    configuredCandidateInputs ?: error("-PcandidateManifest=<external immutable manifest> is required")
}
val candidateTagFromManifest = candidateInputs.map { inputs ->
    inputs.tagName
}
val candidateMaterials = candidateInputs.map(CandidateManifestInputs::materials)
val syncTarget = candidateTagFromManifest.map { tag -> engineBuildDirectory.resolve("upstream").resolve(tag).toFile() }
val copiedManifest = candidateManifest.map { manifest ->
    engineBuildDirectory.resolve("sync-candidate").resolve(Path.of(manifest).fileName.toString()).toFile()
}

fun currentBuildInputDigests(): Pair<String, String> {
    val digestFile = buildInputDigests.get().asFile
    check(digestFile.isFile) { "build input digest file is missing: $digestFile" }
    val digests = digestFile.readLines().filter(String::isNotBlank)
    check(digests.size == 2) { "build input digest helper returned an invalid result" }
    return digests[0] to digests[1]
}

tasks.register<JavaExec>("discoverCandidate") {
    group = "upstream"
    description = "Writes one immutable external candidate manifest for an exact accepted Gitiles tag."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.ravenliao.svg2vd.upstream.CandidateCoordinatorMain")
    dependsOn("writeBuildInputDigests")
    inputs.file(buildInputDigests)
    doFirst {
        val output = candidateOutputDirectory.orNull ?: error("-PcandidateOutputDirectory=<external directory> is required")
        val tag = candidateTag.orNull ?: error("-PcandidateTag=<studio tag> is required")
        val (runtime, locks) = currentBuildInputDigests()
        args("discover", workspaceRoot.toString(), tag, output, "$runtime:$locks")
    }
}

tasks.register<JavaExec>("discoverLatestStableTag") {
    group = "upstream"
    description = "Writes the newest accepted stable Android Studio tag after the canonical corpus lock anchor."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.ravenliao.svg2vd.upstream.LatestStableTagMain")
    inputs.file(corpusLock).withPathSensitivity(PathSensitivity.NONE)
    outputs.file(latestTagOutput.map(::file))
    doFirst {
        val lock = corpusLock.orNull ?: error("-PcorpusLock=<canonical corpus lock> is required")
        val output = latestTagOutput.orNull ?: error("-PlatestTagOutput=<external output file> is required")
        args(lock, output)
    }
}

tasks.register<JavaExec>("bumpToolPatchVersion") {
    group = "release"
    description = "Increments the patch component of the checked-in tool version after an exact-value check."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.ravenliao.svg2vd.upstream.ToolVersionMain")
    doFirst {
        val file = toolVersionFile.orNull ?: error("-PtoolVersionFile=<gradle.properties> is required")
        val expected = expectedToolVersion.orNull ?: error("-PexpectedToolVersion=X.Y.Z is required")
        args("bump-patch", file, expected)
    }
}

tasks.register<JavaExec>("syncCandidate") {
    group = "upstream"
    description = "Materializes an external immutable candidate manifest without querying Gitiles refs."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.ravenliao.svg2vd.upstream.CandidateCoordinatorMain")
    inputs.file(candidateManifest).withPathSensitivity(PathSensitivity.NONE)
    inputs.files(candidateMaterials).withPropertyName("candidateMaterials").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(workspaceRoot.resolve("upstream-scope.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    dependsOn("writeBuildInputDigests")
    inputs.file(buildInputDigests)
    outputs.dir(syncTarget)
    outputs.file(copiedManifest)
    doFirst {
        val manifest = candidateManifest.orNull ?: error("-PcandidateManifest=<external immutable manifest> is required")
        val (runtime, locks) = currentBuildInputDigests()
        args("sync", workspaceRoot.toString(), manifest, workspaceRoot.resolve("upstream-scope.yaml").toString(), engineBuildDirectory.toString(), "$runtime:$locks")
    }
}

tasks.register<JavaExec>("syncCorpus") {
    group = "upstream"
    description = "Synchronizes immutable VectorDrawable visual assets from one fixed candidate commit."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.ravenliao.svg2vd.upstream.CorpusManifestMain")
    inputs.file(candidateManifest).withPathSensitivity(PathSensitivity.NONE)
    outputs.dir(corpusOutputDirectory.map { file(it) })
    doFirst {
        val manifest = candidateManifest.orNull ?: error("-PcandidateManifest=<external immutable manifest> is required")
        val output = corpusOutputDirectory.orNull ?: error("-PcorpusOutputDirectory=<directory> is required")
        args("sync", workspaceRoot.toString(), manifest, output)
    }
}

tasks.register<JavaExec>("discoverLockedCandidate") {
    group = "upstream"
    description = "Materializes an immutable candidate from a canonical corpus lock without querying Gitiles refs."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.ravenliao.svg2vd.upstream.LockedCandidateMain")
    dependsOn("writeBuildInputDigests")
    inputs.file(corpusLock).withPathSensitivity(PathSensitivity.NONE)
    inputs.file(workspaceRoot.resolve("upstream-scope.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(buildInputDigests)
    inputs.files(providers.provider(::controlledLockfiles)).withPropertyName("controlledLockfiles").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(candidateOutputDirectory.map { file(it) })
    doFirst {
        val lock = corpusLock.orNull ?: error("-PcorpusLock=<canonical corpus lock> is required")
        val output = candidateOutputDirectory.orNull ?: error("-PcandidateOutputDirectory=<external directory> is required")
        val (runtime, locks) = currentBuildInputDigests()
        args(workspaceRoot.toString(), lock, output, "$runtime:$locks", workspaceRoot.resolve("upstream-scope.yaml").toString())
    }
}

tasks.register<JavaExec>("writeCorpusLock") {
    group = "upstream"
    description = "Writes a fixed canonical corpus lock from one validated materialized corpus manifest."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.ravenliao.svg2vd.upstream.CorpusLockMain")
    inputs.file(corpusManifestInput).withPathSensitivity(PathSensitivity.NONE)
    outputs.file(corpusLockOutput.map(::file))
    doFirst {
        val manifest = corpusManifestInput.orNull ?: error("-PcorpusManifest=<materialized external manifest.json> is required")
        val output = corpusLockOutput.orNull ?: error("-PcorpusLockOutput=<target corpus.lock.json> is required")
        args(manifest, output)
    }
}

tasks.register<JavaExec>("writeBuildInputDigests") {
    group = "upstream"
    description = "Hashes the resolved engine runtime closure and controlled lockfiles for candidate manifests."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.ravenliao.svg2vd.upstream.BuildInputDigestMain")
    dependsOn(":engine:writeUpstreamRuntimeArtifacts")
    inputs.file(engineRuntimeArtifacts)
    inputs.files(providers.provider(::controlledLockfiles)).withPropertyName("controlledLockfiles").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("controlledModuleRoots", controlledModuleRoots)
    inputs.property("distributionLockSha256", providers.provider(::controlledDistributionLockSha256))
    outputs.file(buildInputDigests)
    doFirst {
        args("summarize", workspaceRoot.toString(), engineRuntimeArtifacts.toString(), buildInputDigests.get().asFile.toString(), *controlledModuleRoots.toTypedArray())
    }
    doLast {
        check(currentBuildInputDigests().second == controlledDistributionLockSha256()) {
            "build input digest helper disagrees with the controlled distribution lock input"
        }
    }
}
