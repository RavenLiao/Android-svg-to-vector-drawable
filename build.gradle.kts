plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.shadow) apply false
}

val verifyBuildJdk = tasks.register("verifyBuildJdk") {
    group = "verification"
    description = "Verifies the minimum JDK used to run this build."
    doLast {
        check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
            "Gradle builds require JDK 17 or newer, got ${System.getProperty("java.version")}"
        }
    }
}

allprojects {
    group = "io.github.ravenliao"
    version = "0.1.0"
}

subprojects {
    dependencyLocking { lockAllConfigurations() }
    tasks.configureEach { dependsOn(verifyBuildJdk) }

    tasks.register("resolveLockedConfigurations") {
        group = "verification"
        description = "Resolves every lockable configuration so dependency locks stay complete."
        doLast {
            configurations
                .filter {
                    it.isCanBeResolved &&
                        (it.name.endsWith("Classpath") || it.name == "shadow")
                }
                .forEach { configuration -> configuration.resolve() }
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            compilerOptions.freeCompilerArgs.add("-Xjdk-release=11")
        }
    }
    plugins.withId("java") {
        tasks.withType<JavaCompile>().configureEach { options.release.set(11) }
    }
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        jvmArgs("-Djava.awt.headless=true")
    }

}

subprojects
    .map { it.tasks.named("resolveLockedConfigurations") }
    .zipWithNext()
    .forEach { (before, after) -> after.configure { mustRunAfter(before) } }
