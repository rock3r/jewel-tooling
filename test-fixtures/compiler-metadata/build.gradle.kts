plugins {
  alias(libs.plugins.kotlin)
  alias(libs.plugins.composeCompiler)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.detekt)
}

repositories {
  mavenCentral()
  google()
}

kotlin { jvmToolchain(21) }

dependencies { compileOnly("org.jetbrains.compose.runtime:runtime:${libs.versions.compose.get()}") }

val fixtureJar by configurations.creating {
  isCanBeConsumed = true
  isCanBeResolved = false
}

artifacts { add(fixtureJar.name, tasks.jar) }

ktfmt { googleStyle() }

detekt {
  buildUponDefaultConfig = true
  config.setFrom(rootProject.file("config/detekt.yml"))
}
