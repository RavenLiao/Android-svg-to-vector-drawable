plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

evaluationDependsOn(":engine")

dependencies {
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    compileOnly(project(":engine")) { isTransitive = false }
    testImplementation(project(":engine")) { isTransitive = false }
    testRuntimeOnly(project(":engine"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.snakeyaml.engine)
}

tasks.test { useJUnitPlatform() }
tasks.shadowJar {
    val engine = rootProject.project(":engine")
    dependsOn(engine.tasks.named("jar"))
    from({
        zipTree(engine.tasks.named<org.gradle.jvm.tasks.Jar>("jar").get().archiveFile.get().asFile)
    })
    from({ engine.configurations.getByName("runtimeClasspath").files.map(::zipTree) })
    archiveBaseName.set("svg2vd")
    archiveClassifier.set("all")
    manifest { attributes["Main-Class"] = "io.github.ravenliao.svg2vd.CliMainKt" }
    mergeServiceFiles()
}

tasks.test {
    dependsOn(tasks.shadowJar)
    inputs.file(tasks.shadowJar.flatMap { it.archiveFile })
    systemProperty("svg2vd.projectRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
    systemProperty("svg2vd.shadowJar", tasks.shadowJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}
