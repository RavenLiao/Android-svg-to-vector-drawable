import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    testImplementation(project(":upstream-sync"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.serialization.json)
}

val corpusPropertyNames = listOf("corpusManifest", "corpusRoot", "svg2vdJar", "javaExecutable")
val shadowJar = project(":cli").tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar")
val shadowJarFile = shadowJar.flatMap { it.archiveFile }

tasks.test {
    useJUnitPlatform()
    exclude("**/UpstreamCorpusTest.*")
}

tasks.register<Test>("corpusTest") {
    group = "verification"
    description = "Runs the immutable visual corpus through the published svg2vd JAR."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/UpstreamCorpusTest.*")
    dependsOn(shadowJar)

    inputs.file(shadowJarFile).withPropertyName("currentShadowJar").withPathSensitivity(PathSensitivity.NONE)
    inputs.property("currentShadowJarSha256", providers.provider {
        sha256(shadowJarFile.get().asFile.toPath())
    })
    inputs.file(providers.gradleProperty("corpusManifest").map(::file))
        .withPropertyName("corpusManifestFile")
        .withPathSensitivity(PathSensitivity.NONE)
        .optional()
    inputs.dir(providers.gradleProperty("corpusRoot").map(::file))
        .withPropertyName("corpusRootFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()
    corpusPropertyNames.forEach { name ->
        inputs.property(name, providers.gradleProperty(name).orElse(""))
    }
    outputs.dir(layout.buildDirectory.dir("corpus-artifacts"))

    doFirst {
        val configured = corpusPropertyNames.associateWith { name ->
            providers.gradleProperty(name).orNull?.takeIf(String::isNotBlank)
                ?: error("-P$name=<path> is required for :verification:corpusTest")
        }
        val expectedJar = shadowJarFile.get().asFile.toPath().toRealPath()
        val configuredJar = Path.of(configured.getValue("svg2vdJar")).toAbsolutePath().normalize().toRealPath()
        check(configuredJar == expectedJar) {
            "-Psvg2vdJar must resolve to the current :cli:shadowJar archive: $expectedJar"
        }
        check(sha256(configuredJar) == sha256(expectedJar)) {
            "-Psvg2vdJar does not have the current :cli:shadowJar SHA-256"
        }

        systemProperty("svg2vd.corpusManifest", Path.of(configured.getValue("corpusManifest")).toAbsolutePath().normalize())
        systemProperty("svg2vd.corpusRoot", Path.of(configured.getValue("corpusRoot")).toAbsolutePath().normalize())
        systemProperty("svg2vd.jar", configuredJar)
        systemProperty("svg2vd.javaExecutable", Path.of(configured.getValue("javaExecutable")).toAbsolutePath().normalize())
        systemProperty("svg2vd.corpusArtifactRoot", layout.buildDirectory.dir("corpus-artifacts").get().asFile.absolutePath)
    }
}

private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(path))
    .joinToString("") { "%02x".format(it) }
