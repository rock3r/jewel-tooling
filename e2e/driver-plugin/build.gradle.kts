import java.nio.file.Files

plugins {
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
}

version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    google()
    intellijPlatform { defaultRepositories() }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("dev.sebastiano.spectre:spectre-recording:${libs.versions.spectre.get()}")
    if (System.getProperty("os.name").startsWith("Mac"))
        runtimeOnly("dev.sebastiano.spectre:spectre-recording-macos:${libs.versions.spectre.get()}")
    if (System.getProperty("os.name").startsWith("Linux"))
        runtimeOnly("dev.sebastiano.spectre:spectre-recording-linux:${libs.versions.spectre.get()}")

    implementation("dev.sebastiano.spectre:spectre-core:${libs.versions.spectre.get()}")
    intellijPlatform {
        intellijIdea(libs.versions.idea.get())
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.kotlin")
        @Suppress("UnstableApiUsage") composeUI()
    }
}

configurations
    .matching { it.name == "runtimeClasspath" }
    .configureEach {
        listOf(
                "org.jetbrains.compose",
                "org.jetbrains.compose.runtime",
                "org.jetbrains.compose.foundation",
                "org.jetbrains.compose.ui",
                "org.jetbrains.skiko",
                "org.jetbrains.kotlin",
                "org.jetbrains.kotlinx",
            )
            .forEach { exclude(group = it) }
    }

intellijPlatform {
    pluginConfiguration {
        name = "Jewel Tooling Test Driver"
        ideaVersion {
            sinceBuild = libs.versions.ideaBuild.get()
            untilBuild = "262.*"
        }
    }
}

tasks.named("buildSearchableOptions") { enabled = false }

val fixtureCompiler by configurations.creating
val fixtureComposeCompiler by configurations.creating

dependencies {
    fixtureCompiler("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
    fixtureComposeCompiler(
        "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${libs.versions.kotlin.get()}"
    ) {
        isTransitive = false
    }
}

val fixtureSdk = rootProject.layout.projectDirectory.dir("fixtures/ijpl/.local/sdk")

tasks.register<Sync>("exportFixtureSdk") {
    dependsOn(rootProject.tasks.named("exportCompilerFixtures"))
    from(rootProject.configurations.named("recordingFixtures")) {
        into("api")
        rename { it.replace("-${project.version}.jar", ".jar") }
    }
    from(rootProject.configurations.named("compilerFixtures")) {
        into("api")
        rename { "compiler-metadata.jar" }
    }
    from(
        provider {
            (configurations.getByName("intellijPlatformClasspath") +
                    configurations.getByName("intellijPlatformDependencies"))
                .filter { it.extension == "jar" }
                .groupBy { it.name }
                .map { (name, copies) ->
                    val first = copies.first()
                    require(copies.all { Files.mismatch(first.toPath(), it.toPath()) == -1L }) {
                        "Conflicting SDK jars: $name"
                    }
                    first
                }
        }
    ) {
        into("api")
    }
    from(fixtureCompiler) { into("compiler") }
    from(fixtureComposeCompiler) { into("compose-plugin") }
    into(fixtureSdk)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    doLast {
        fixtureSdk
            .file("MODULE.bazel")
            .asFile
            .writeText("module(name = \"idea_sdk\", version = \"0.0.0\")\n")
        fixtureSdk
            .file("BUILD.bazel")
            .asFile
            .writeText(
                """
                package(default_visibility = ["//visibility:public"])
                filegroup(name = "api", srcs = glob(["api/*.jar"]))
                filegroup(name = "compiler", srcs = glob(["compiler/*.jar"]))
                filegroup(name = "compose_plugin", srcs = glob(["compose-plugin/*.jar"]))
                """
                    .trimIndent()
            )
    }
}

ktfmt { kotlinLangStyle() }

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
}
