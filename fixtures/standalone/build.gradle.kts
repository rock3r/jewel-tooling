plugins {
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.compose)
    alias(libs.plugins.composeCompiler)
    application
}

repositories {
    mavenCentral()
    google()
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation(
        files(
            ".local/compiler-metadata.jar",
            ".local/recording.jar",
            ".local/recording-compose.jar",
        )
    )
    implementation("com.fasterxml.jackson.core:jackson-core:2.19.0")
    implementation(compose.desktop.currentOs)
    // Jewel 0.38 omitted these standalone icon runtime dependencies from its POM.
    implementation("com.jetbrains.intellij.platform:icons-impl:${libs.versions.ideaBuild.get()}") {
        exclude(group = "org.jetbrains.intellij.deps.kotlinx")
    }
    implementation("org.jetbrains.jewel:jewel-int-ui-standalone:${libs.versions.jewel.get()}")
    testImplementation("dev.sebastiano.spectre:spectre-core:${libs.versions.spectre.get()}")
    testImplementation("dev.sebastiano.spectre:spectre-testing:${libs.versions.spectre.get()}")
    testImplementation("dev.sebastiano.spectre:spectre-recording:${libs.versions.spectre.get()}")
    if (System.getProperty("os.name").startsWith("Mac"))
        testRuntimeOnly(
            "dev.sebastiano.spectre:spectre-recording-macos:${libs.versions.spectre.get()}"
        )
    if (System.getProperty("os.name").startsWith("Linux"))
        testRuntimeOnly(
            "dev.sebastiano.spectre:spectre-recording-linux:${libs.versions.spectre.get()}"
        )
    testImplementation("org.junit.jupiter:junit-jupiter:${libs.versions.junit.get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass.set("example.MainKt") }

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    outputs.upToDateWhen { false }
    systemProperty(
        "jewel.test.captureId",
        providers.gradleProperty("captureId").getOrElse("development"),
    )
    systemProperty("jewel.test.retina", providers.gradleProperty("retina").getOrElse("false"))
    systemProperty("fixture.output", layout.buildDirectory.dir("capture").get().asFile.absolutePath)
}

ktfmt { kotlinLangStyle() }

detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("../../config/detekt.yml"))
}

val writeLiveTestClasspath by tasks.registering {
    dependsOn(tasks.testClasses)
    val output = layout.buildDirectory.file("live-test-classpath.txt")
    outputs.file(output)
    inputs.files(sourceSets.test.get().runtimeClasspath)
    doLast { output.get().asFile.writeText(sourceSets.test.get().runtimeClasspath.asPath) }
}

tasks.test { dependsOn(writeLiveTestClasspath) }

// This target uses Spectre for interaction, without a recorder bootstrap.
tasks.register<JavaExec>("inspectionE2E") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("example.InspectionStandaloneTarget")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    systemProperty(
        "jewel.test.commands",
        providers.gradleProperty("inspectionCommands").getOrElse(""),
    )
}
