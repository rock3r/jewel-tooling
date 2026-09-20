plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

group = rootProject.group

version = rootProject.version

repositories {
    google()
    mavenCentral()
}

kotlin { jvmToolchain(21) }

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(project(":recording"))
    compileOnly("androidx.compose.runtime:runtime-desktop:1.11.1")
    compileOnly("org.jetbrains:annotations:26.1.0")
    testImplementation(kotlin("stdlib"))
    testImplementation(project(":recording"))
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.19.0")
    testImplementation("androidx.compose.runtime:runtime-desktop:1.11.1")
    testImplementation("junit:junit:${libs.versions.junit4.get()}")
}

ktfmt { kotlinLangStyle() }

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
}

tasks.named<Jar>("jar") {
    from(listOf(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) { into("META-INF") }
}
