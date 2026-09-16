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
  implementation(compose.desktop.currentOs)
  // Jewel 0.38 omitted these standalone icon runtime dependencies from its POM.
  implementation("com.jetbrains.intellij.platform:icons-impl:${libs.versions.ideaBuild.get()}") {
    exclude(group = "org.jetbrains.intellij.deps.kotlinx")
  }
  implementation("org.jetbrains.jewel:jewel-int-ui-standalone:${libs.versions.jewel.get()}")
  testImplementation("dev.sebastiano.spectre:spectre-core:${libs.versions.spectre.get()}")
  testImplementation("dev.sebastiano.spectre:spectre-testing:${libs.versions.spectre.get()}") {
    // This fixture uses screen-region stills, not the optional native recording helper.
    exclude(group = "dev.sebastiano.spectre", module = "spectre-recording")
  }
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

ktfmt { googleStyle() }

detekt {
  buildUponDefaultConfig = true
  config.setFrom(file("../../config/detekt.yml"))
}
