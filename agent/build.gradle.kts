plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

repositories {
    google()
    mavenCentral()
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":recording"))
    implementation(kotlin("stdlib"))
    implementation("com.fasterxml.jackson.core:jackson-core:2.19.0")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    compileOnly(project(":agent-bridge"))
    testImplementation(project(":agent-bridge"))
    testImplementation("androidx.compose.runtime:runtime-desktop:1.11.1")
    testImplementation("org.ow2.asm:asm-util:9.10.1")
    testImplementation("junit:junit:${libs.versions.junit4.get()}")
}

val premain by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies { premain(project(":agent-premain")) }

tasks.jar {
    dependsOn(premain)
    archiveFileName.set("inspection-agent.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Premain-Class" to "dev.sebastiano.jewel.tooling.agent.Premain") }
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    from({ premain.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
    from(listOf(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) { into("META-INF") }
    configurations.runtimeClasspath
        .get()
        .filter { it.extension == "jar" }
        .forEach { dependency ->
            from(zipTree(dependency)) {
                include("**/LICENSE*", "**/NOTICE*", "**/license*", "**/notice*")
                into("META-INF/licenses/${dependency.nameWithoutExtension}")
            }
        }
}

ktfmt { kotlinLangStyle() }

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt.yml"))
}

val bridgeFixture by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies { bridgeFixture(project(":agent-bridge")) }

tasks.test {
    dependsOn(tasks.jar, bridgeFixture)
    val java25 = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    doFirst {
        systemProperty("inspection.agent", tasks.jar.get().archiveFile.get().asFile.absolutePath)
        systemProperty(
            "inspection.pathLoader",
            rootProject.configurations
                .getByName("intellijPlatformClasspath")
                .files
                .single { it.name == "platform-loader.jar" }
                .absolutePath,
        )
        systemProperty("inspection.bridge", bridgeFixture.singleFile.absolutePath)
        systemProperty("inspection.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
        systemProperty("inspection.java25", java25.get().executablePath.asFile.absolutePath)
    }
}
