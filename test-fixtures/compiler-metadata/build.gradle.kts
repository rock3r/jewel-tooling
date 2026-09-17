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

// Each compiler runs in its own process and resolves its own dependency graph.
for ((row, compilerVersion) in mapOf("standalone" to "2.3.20", "platform" to "2.4.20-RC3")) {
  val compiler = configurations.create("${row}Compiler")
  val composePlugin = configurations.create("${row}ComposePlugin")
  val libraries = configurations.create("${row}Libraries")
  dependencies {
    add(compiler.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:$compilerVersion")
    add(
      composePlugin.name,
      "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:$compilerVersion",
    ) {
      isTransitive = false
    }
    add(libraries.name, "org.jetbrains.kotlin:kotlin-stdlib:$compilerVersion")
    add(
      libraries.name,
      "org.jetbrains.compose.runtime:runtime-desktop:${libs.versions.compose.get()}",
    )
  }
  val sources =
    tasks.register<Sync>("${row}Sources") {
      from("src/main/kotlin/evidence/Models.kt") {
        filter { line: String ->
          if (line == "package evidence") "package ${row}evidence" else line
        }
      }
      into(layout.buildDirectory.dir("generated/$row"))
    }
  val classes = layout.buildDirectory.dir("matrix/$row")
  val compile =
    tasks.register<JavaExec>("compile${row.replaceFirstChar { it.uppercase() }}Fixtures") {
      dependsOn(sources)
      javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
      )
      classpath = compiler
      mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
      inputs.files(sources, libraries, composePlugin)
      inputs.property("compilerVersion", compilerVersion)
      outputs.dir(classes)
      doFirst {
        delete(classes)
        args =
          listOf(
            "-no-stdlib",
            "-no-reflect",
            "-jvm-target",
            "25",
            "-module-name",
            "${row}_fixtures",
            "-Xplugin=${composePlugin.singleFile.absolutePath}",
            "-classpath",
            libraries.asPath,
            "-d",
            classes.get().asFile.absolutePath,
            layout.buildDirectory.file("generated/$row/Models.kt").get().asFile.absolutePath,
          )
      }
    }
  tasks.jar {
    dependsOn(compile)
    from(classes)
    duplicatesStrategy = DuplicatesStrategy.FAIL
  }
}
