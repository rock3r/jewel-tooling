import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  alias(libs.plugins.kotlin)
  alias(libs.plugins.intellijPlatform)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
}

group = "dev.sebastiano.jewel.tooling"

version = providers.gradleProperty("pluginVersion").get()

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

dependencies {
  intellijPlatform {
    val localIde = providers.gradleProperty("localIdePath")
    if (localIde.isPresent) local(localIde.get()) else intellijIdea(libs.versions.idea.get())
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Platform)
    testFramework(TestFrameworkType.Plugin.Java)
  }
  testImplementation("junit:junit:${libs.versions.junit4.get()}")
  testImplementation(kotlin("stdlib"))
}

kotlin { jvmToolchain(21) }

intellijPlatform {
  pluginVerification { ides { current() } }
  pluginConfiguration {
    name = "Jewel Tooling"
    ideaVersion {
      sinceBuild = libs.versions.ideaBuild.get()
      untilBuild = provider { null }
    }
  }
}

tasks.test {
  systemProperty("idea.kotlin.plugin.use.k2", "true")
  val stdlib =
    configurations.testRuntimeClasspath.map { files ->
      files.single { it.name.matches(Regex("kotlin-stdlib-[0-9].*\\.jar")) }.absolutePath
    }
  doFirst { systemProperty("jewel.tooling.stdlib", stdlib.get()) }
}

ktfmt { googleStyle() }

detekt {
  buildUponDefaultConfig = true
  config.setFrom(rootProject.file("config/detekt.yml"))
}

tasks.named("buildSearchableOptions") { enabled = false }

// The Bazel fixture has no Gradle project; include its Kotlin sources in root checks.
tasks.named<com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask>("ktfmtCheckMain") {
  source(fileTree("fixtures/ijpl/src/main/kotlin") { include("**/*.kt") })
}

detekt { source.from(files("fixtures/ijpl/src/main/kotlin")) }
