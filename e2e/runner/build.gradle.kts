plugins {
  alias(libs.plugins.kotlin)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
}

repositories {
  mavenCentral()
  maven("https://www.jetbrains.com/intellij-repository/releases")
  maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

kotlin { jvmToolchain(25) }

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter:${libs.versions.junit.get()}")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  listOf(
      "ide-starter-squashed",
      "ide-starter-product-idea-ultimate",
      "ide-starter-junit5",
      "ide-starter-driver",
    )
    .forEach {
      testImplementation("com.jetbrains.intellij.tools:$it:${libs.versions.ideaBuild.get()}")
    }
  listOf("driver-client", "driver-sdk", "driver-model").forEach {
    testImplementation("com.jetbrains.intellij.driver:$it:${libs.versions.ideaBuild.get()}")
  }
}

evaluationDependsOn(":")

evaluationDependsOn(":e2e:driver-plugin")

val productionZip = rootProject.tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
val driverZip =
  project(":e2e:driver-plugin").tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }

tasks.test {
  useJUnitPlatform()
  dependsOn(productionZip, driverZip, rootProject.tasks.named("exportCompilerFixtures"))
  outputs.upToDateWhen { false }
  jvmArgs("--add-opens=java.base/sun.nio.fs=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
  systemProperty("jewel.test.repo", rootProject.layout.projectDirectory.asFile.absolutePath)
  systemProperty(
    "jewel.test.captureId",
    providers.gradleProperty("captureId").getOrElse("development"),
  )
  systemProperty("jewel.test.retina", providers.gradleProperty("retina").getOrElse("false"))
  systemProperty("jewel.test.ideBuild", libs.versions.ideaBuild.get())
  providers.gradleProperty("testIdePath").orNull?.let { systemProperty("jewel.test.idePath", it) }
  systemProperty(
    "jewel.test.artifacts",
    layout.buildDirectory.dir("artifacts").get().asFile.absolutePath,
  )
  systemProperty("jewel.test.productionZip", productionZip.get().asFile.absolutePath)
  systemProperty("jewel.test.driverZip", driverZip.get().asFile.absolutePath)
}

ktfmt { googleStyle() }

detekt {
  buildUponDefaultConfig = true
  config.setFrom(rootProject.file("config/detekt.yml"))
}
