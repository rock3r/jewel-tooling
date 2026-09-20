import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ktfmt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    implementation("io.ktor:ktor-server-cio:3.6.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("io.ktor:ktor-client-cio:3.6.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("junit:junit:${libs.versions.junit4.get()}")
    testImplementation(project(":mcp-bootstrap"))
}

val mergedServices = layout.buildDirectory.dir("merged-services")
val mergeServices by tasks.registering {
    inputs.files(configurations.runtimeClasspath)
    outputs.dir(mergedServices)
    doLast {
        val destination = mergedServices.get().asFile
        destination.deleteRecursively()
        val entries = sortedMapOf<String, MutableSet<String>>()
        configurations.runtimeClasspath
            .get()
            .filter { it.extension == "jar" }
            .forEach { dependency ->
                ZipFile(dependency).use { archive ->
                    archive
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                        .forEach { entry ->
                            entries
                                .getOrPut(entry.name) { linkedSetOf() }
                                .addAll(archive.getInputStream(entry).bufferedReader().readLines())
                        }
                }
            }
        entries.forEach { (name, lines) ->
            destination.resolve(name).apply {
                parentFile.mkdirs()
                writeText(lines.joinToString("\n", postfix = "\n"))
            }
        }
    }
}

tasks.jar {
    dependsOn(mergeServices)
    archiveFileName.set("mcp-runtime.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(mergedServices)
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude(
            "META-INF/services/**",
            "META-INF/*.SF",
            "META-INF/*.RSA",
            "META-INF/*.DSA",
            "module-info.class",
        )
    }
    from(rootProject.file("LICENSE")) { into("META-INF") }
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

val bootstrapJar = project(":mcp-bootstrap").tasks.named<Jar>("jar")

tasks.test {
    dependsOn(tasks.jar, bootstrapJar)
    doFirst {
        systemProperty(
            "jewel.mcp.runtime.jar",
            tasks.jar.get().archiveFile.get().asFile.absolutePath,
        )
        systemProperty(
            "jewel.mcp.bootstrap.jar",
            bootstrapJar.get().archiveFile.get().asFile.absolutePath,
        )
    }
}
