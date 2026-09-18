plugins { `java-library` }

repositories { mavenCentral() }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies { testImplementation("junit:junit:4.13.2") }

tasks.jar {
  archiveFileName.set("mcp-bootstrap.jar")
  manifest { attributes["Main-Class"] = "dev.sebastiano.jewel.tooling.mcp.bootstrap.Boot" }
  from(rootProject.file("LICENSE")) { into("META-INF") }
}
