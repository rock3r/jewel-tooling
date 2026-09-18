import dev.detekt.gradle.Detekt
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
  implementation(project(":recording"))
  implementation(project(":mcp-bootstrap"))
  intellijPlatform {
    val localIde = providers.gradleProperty("localIdePath")
    if (localIde.isPresent) local(localIde.get()) else intellijIdea(libs.versions.idea.get())
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.kotlin")
    bundledPlugin("com.intellij.gradle")
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

val schemaValidator by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies { schemaValidator("com.networknt:json-schema-validator:1.5.9") }

val compilerFixtures by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies {
  compilerFixtures(project(path = ":test-fixtures:compiler-metadata", configuration = "fixtureJar"))
}

tasks.test {
  dependsOn(compilerFixtures)
  inputs.files(compilerFixtures, schemaValidator)
  doFirst {
    systemProperty("jewel.tooling.compilerFixtures", compilerFixtures.singleFile.absolutePath)
    systemProperty("jewel.tooling.schemaValidator", schemaValidator.asPath)
  }
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

// Binary-only libraries exercise dependency analysis in both disposable editor targets.
tasks.register<Sync>("exportCompilerFixtures") {
  from(compilerFixtures) { rename { "compiler-metadata.jar" } }
  into(layout.projectDirectory.dir("fixtures/standalone/.local"))
}

val recordingFixtures by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies {
  recordingFixtures(project(":recording"))
  recordingFixtures(project(":recording-compose"))
}

tasks.named<Sync>("exportCompilerFixtures") {
  from(recordingFixtures) { rename { it.replace("-${project.version}.jar", ".jar") } }
}

tasks.register("exportRecordingFixtures") { dependsOn("exportCompilerFixtures") }

tasks.named<Jar>("jar") {
  from(listOf(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) { into("META-INF") }
}

val inspectionAgent by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = false
}
val inspectionBridge by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = false
}

dependencies {
  inspectionAgent(project(":agent"))
  inspectionBridge(project(":agent-bridge"))
}

tasks.processResources {
  from(inspectionAgent) {
    into("agent")
    rename { "inspection-agent.jar" }
  }
  from(inspectionBridge) {
    into("agent")
    rename { "bridge.jar" }
  }
}

val composeRules by configurations.creating

dependencies { composeRules("io.nlopez.compose.rules:detekt:0.6.0") }

tasks.register<Detekt>("detektComposeFixtures") {
  description = "Check Compose fixtures and the target adapter with Compose rules."
  group = "verification"
  setSource(
    files(
      "fixtures/standalone/src",
      "fixtures/ijpl/src",
      "test-fixtures/compiler-metadata/src",
      "recording-compose/src",
    )
  )
  include("**/*.kt")
  detektClasspath.setFrom(configurations.named("detekt"))
  pluginClasspath.setFrom(composeRules)
  config.setFrom(file("config/detekt-compose.yml"))
  disableDefaultRuleSets = true
}

tasks.register<Detekt>("detektBuildScripts") {
  description = "Check maintained Gradle Kotlin scripts without type resolution."
  group = "verification"
  setSource(
    fileTree(projectDir) {
      include("**/*.gradle.kts")
      exclude("**/build/**", "**/.*/**", "out/**", "artifacts/**", "**/bazel-*/**")
    }
  )
  detektClasspath.setFrom(configurations.named("detekt"))
  config.setFrom(file("config/detekt.yml"))
  buildUponDefaultConfig = true
}

tasks.named<com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask>("ktfmtCheckScripts") {
  source(files("agent-premain/build.gradle.kts", "agent-bridge/build.gradle.kts"))
}

val mcpRuntime by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = false
}
val mcpBootstrap by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  isTransitive = false
}

dependencies {
  mcpRuntime(project(":mcp-runtime"))
  mcpBootstrap(project(":mcp-bootstrap"))
}

tasks.processResources {
  val mcpVersion = project.version.toString()
  inputs.property("mcpVersion", mcpVersion)
  filesMatching("mcp/jewel-tooling-version.txt") { expand("pluginVersion" to mcpVersion) }
  from(mcpRuntime) {
    into("mcp")
    rename { "runtime.jar" }
  }
  from(mcpBootstrap) {
    into("mcp")
    rename { "bootstrap.jar" }
  }
}
