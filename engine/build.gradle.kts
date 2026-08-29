import java.nio.file.Files
import java.nio.file.Path
import groovy.json.JsonSlurper
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins { alias(libs.plugins.kotlin.jvm) }

val candidateManifest = providers.gradleProperty("candidateManifest")
val manifestTag = candidateManifest.map { manifest ->
    val root = JsonSlurper().parse(Path.of(manifest).toFile()) as Map<*, *>
    (root["tag"] as? Map<*, *>)?.get("name") as? String
        ?: error("candidate manifest does not contain tag.name: $manifest")
}
val upstreamJavaDirectory = layout.buildDirectory.dir(manifestTag.map { "upstream/$it" })

if (candidateManifest.isPresent) {
    kotlin {
        sourceSets {
            named("main") { kotlin.srcDir(upstreamJavaDirectory) }
        }
    }
    sourceSets.named("main") {
        java.srcDir(upstreamJavaDirectory)
    }
    tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
        dependsOn(":upstream-sync:syncCandidate")
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        dependsOn(":upstream-sync:syncCandidate")
    }
}

dependencies {
    implementation(libs.guava)
    testImplementation(project(":upstream-sync"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}
tasks.test { useJUnitPlatform() }

val upstreamRuntimeArtifacts = layout.buildDirectory.file("upstream-inputs/runtime-artifacts.txt")
val writeUpstreamRuntimeArtifacts = tasks.register("writeUpstreamRuntimeArtifacts") {
    group = "upstream"
    description = "Writes the resolved, locked engine runtime artifacts for candidate input hashing."
    inputs.files(configurations.named("runtimeClasspath"))
    outputs.file(upstreamRuntimeArtifacts)
    doLast {
        val records = configurations.named("runtimeClasspath").get().incoming.artifacts.artifacts.map { artifact ->
            val component = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                ?: error("engine runtime closure contains a non-module artifact: ${artifact.id.componentIdentifier}")
            "${component.group}:${component.module}:${component.version}\t${artifact.file.toPath()}"
        }.sorted()
        val destination = upstreamRuntimeArtifacts.get().asFile.toPath()
        Files.createDirectories(destination.parent)
        Files.writeString(destination, records.joinToString("\n", postfix = "\n"))
    }
}

val verifyUpstreamClosureInputs = tasks.register("verifyUpstreamClosureInputs") {
    group = "verification"
    description = "Fails closed unless an external candidate and its materialized upstream source exist."
    inputs.property("candidateManifest", candidateManifest)
    inputs.property("manifestTag", manifestTag)
    inputs.dir(upstreamJavaDirectory)
    dependsOn(":upstream-sync:syncCandidate")
    doLast {
        val manifest = candidateManifest.orNull
            ?: error("-PcandidateManifest=<external immutable manifest> is required")
        check(Files.isRegularFile(Path.of(manifest))) {
            "candidate manifest is not a regular file: $manifest"
        }
        val tag = manifestTag.get()
        val sourceDirectory = upstreamJavaDirectory.get().asFile.toPath()
        check(sourceDirectory.fileName.toString() == tag) {
            "materialized source directory is not bound to candidate tag: $sourceDirectory"
        }
        check(Files.isDirectory(sourceDirectory)) {
            "materialized upstream source directory is missing: $sourceDirectory"
        }
        check(Files.walk(sourceDirectory).use { paths -> paths.anyMatch(Files::isRegularFile) }) {
            "materialized upstream source directory is empty: $sourceDirectory"
        }
    }
}

val compileAllUpstreamEngineSources = tasks.register("compileAllUpstreamEngineSources") {
    group = "build"
    description = "Synchronizes and compiles the exact candidate upstream engine closure."
    dependsOn(":upstream-sync:syncCandidate")
    dependsOn(verifyUpstreamClosureInputs)
    dependsOn(tasks.named("jar"))
    doLast {
        val classDirectories = listOf(
            layout.buildDirectory.dir("classes/kotlin/main").get().asFile.toPath(),
            layout.buildDirectory.dir("classes/java/main").get().asFile.toPath(),
        )
        val offending = classDirectories.flatMap { root ->
            if (!Files.isDirectory(root)) emptyList<String>() else Files.walk(root).use { files ->
                files.filter(Files::isRegularFile).filter { it.toString().endsWith(".class") }.map { classFile ->
                    val bytes = Files.readAllBytes(classFile)
                    require(bytes.size >= 8 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))) {
                        "invalid class header: $classFile"
                    }
                    val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
                    if (major > 55) listOf("$classFile:$major") else emptyList()
                }.toList().flatten()
            }
        }
        check(offending.isEmpty()) { "Java 11 closure contains class files newer than major 55: ${offending.joinToString()}" }
    }
}
