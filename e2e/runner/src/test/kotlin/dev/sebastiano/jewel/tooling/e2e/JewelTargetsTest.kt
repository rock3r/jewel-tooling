package dev.sebastiano.jewel.tooling.e2e

import com.intellij.driver.sdk.invokeGlobalBackendAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.installer.ExistingIdeInstaller
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimateProductInit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Test

class JewelTargetsTest {
  @Test
  fun standaloneGradleMcp() {
    runTarget("standalone", mcp = true)
  }

  @Test
  fun bazelIjplMcp() {
    runTarget("ijpl", mcp = true)
  }

  @Test
  fun bazelIjplOneClickInspection() {
    runTarget("ijpl", oneClick = true)
  }

  @Test
  fun standaloneOneClickInspection() {
    runTarget("standalone", oneClick = true)
  }

  @Test
  fun standaloneGradleEditor() {
    runTarget("standalone")
  }

  @Test
  fun bazelIjplEditorAndUi() {
    runTarget("ijpl")
  }

  @Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "ComplexCondition",
  ) // Keep each target scenario and teardown together.
  private fun runTarget(target: String, oneClick: Boolean = false, mcp: Boolean = false) {
    requireCompatibleLocalIde()
    val repository = Path.of(System.getProperty("jewel.test.repo"))
    val temporary = Files.createTempDirectory("jewel-$target-").toRealPath()
    val project =
      if (target == "standalone") {
        val path = Files.createDirectories(temporary.resolve("fixtures/standalone"))
        val source = repository.resolve("fixtures/standalone")
        copyTree(source.resolve("src"), path.resolve("src"))
        copyTree(source.resolve(".local"), path.resolve(".local"))
        for (name in listOf("build.gradle.kts", "settings.gradle.kts")) Files.copy(
          source.resolve(name),
          path.resolve(name),
        )
        copyTree(repository.resolve("gradle"), temporary.resolve("gradle"))
        copyTree(repository.resolve("config"), temporary.resolve("config"))
        copyTree(repository.resolve("gradle/wrapper"), path.resolve("gradle/wrapper"))
        Files.copy(repository.resolve("gradlew"), path.resolve("gradlew"))
        path.resolve("gradlew").toFile().setExecutable(true)
        path
      } else {
        val source = repository.resolve("fixtures/ijpl/build/ide-project")
        check(Files.exists(source.resolve("model-evidence.json"))) {
          "Run scripts/prepare-bazel-fixture.py first"
        }
        val path = temporary.resolve("project")
        copyTree(source, path)
        path
      }
    val output =
      Files.createDirectories(
        Path.of(System.getProperty("jewel.test.artifacts"), "$target-${System.nanoTime()}")
      )
    val liveEndpointFile = temporary.resolve("live-endpoint")
    val context =
      Starter.newContext(
          CurrentTestMethod.hyphenateWithClass(),
          TestCase(
            IdeaUltimateProductInit()
              .ideInfo
              .copy(buildType = "release", buildNumber = System.getProperty("jewel.test.ideBuild"))
              .let { info ->
                val local = System.getProperty("jewel.test.idePath")
                if (local == null) info
                else info.copy(getInstaller = { ExistingIdeInstaller(Path.of(local)) })
              },
            LocalProjectInfo(project),
          ),
        )
        .apply {
          val installer = PluginConfigurator(this)
          installer.installPluginFromPath(Path.of(System.getProperty("jewel.test.productionZip")))
          installer.installPluginFromPath(Path.of(System.getProperty("jewel.test.driverZip")))
          if (target == "ijpl")
            installer.installPluginFromPath(
              repository.resolve("fixtures/ijpl/build/distributions/ijpl-fixture.zip")
            )
        }
        .applyVMOptionsPatch {
          addSystemProperty("jewel.test.output", output.toString())
          addSystemProperty("jewel.test.mcp", mcp)
          addSystemProperty("jewel.test.targetHome", temporary.resolve("target-ide").toString())
          addSystemProperty("jewel.test.driverZip", System.getProperty("jewel.test.driverZip"))
          addSystemProperty(
            "jewel.test.fixtureZip",
            repository.resolve("fixtures/ijpl/build/distributions/ijpl-fixture.zip").toString(),
          )
          addSystemProperty("jewel.test.target", target)
          addSystemProperty("jewel.test.liveEndpoint", liveEndpointFile.toString())
          addSystemProperty(
            "jewel.test.recording",
            repository.resolve("fixtures/standalone/build/capture/recording.json").toString(),
          )
          addSystemProperty(
            "jewel.test.captureId",
            System.getProperty("jewel.test.captureId", "development"),
          )
          addSystemProperty("jewel.test.file", "src/main/kotlin/example/Example.kt")
          addSystemProperty("jewel.test.expected", "stable,unstable,stable,unknown,stable")
          addSystemProperty("jewel.test.gradle", target == "standalone")
          addSystemProperty("jewel.test.hover", target == "standalone")
          addSystemProperty("jewel.test.retina", System.getProperty("jewel.test.retina", "false"))
          addSystemProperty("jetbrainsd.discovery.enabled", false)
          addSystemProperty("jetbrainsd.uri.handling.enabled", false)
          setIdeStartupDialogEnabled(false)
          disableStartupDialogs()
          disableNewUsersOnboardingDialogue()
        }
    context.runIdeWithDriver().useDriverAndCloseIde {
      waitForProjectOpen(timeout = 5.minutes)
      waitForIndicators(timeout = 5.minutes)
      var liveTarget: LiveStandaloneProcess? = null
      try {
        invokeGlobalBackendAction(
          if (oneClick) "JewelTooling.RunLaunchScenario" else "JewelTooling.RunEditorScenario",
          singleProject(),
          now = false,
        )
        val deadline = System.nanoTime() + 960_000_000_000L
        val result = output.resolve("result.txt")
        while (!Files.exists(result) && System.nanoTime() < deadline) {
          if (
            !oneClick &&
              target == "standalone" &&
              liveTarget == null &&
              Files.exists(output.resolve("live-start-request"))
          ) {
            liveTarget = LiveStandaloneProcess(repository, liveEndpointFile, output)
            liveTarget.start()
          }
          Thread.sleep(200)
        }
        check(Files.exists(result)) { "Scenario timed out; artifacts: $output" }
        check(Files.readString(result).startsWith("PASS:")) { Files.readString(result) }
        if (mcp) {
          check(Files.size(output.resolve("mcp-setup.png")) > 0)
          check(Files.readString(output.resolve("mcp-evidence.txt")).startsWith("PASS:"))
          return@useDriverAndCloseIde
        }
        if (oneClick) {
          check(Files.size(output.resolve("one-click-live.png")) > 0)
          return@useDriverAndCloseIde
        }
        check(Files.size(output.resolve("editor-before.png")) > 0)
        check(Files.size(output.resolve("model-roots.txt")) > 0)
        check(Files.size(output.resolve("recording.png")) > 0)
        check(Files.size(output.resolve("recording-evidence.txt")) > 0)
        check(Files.readString(output.resolve("recording-lifecycle.txt")).startsWith("PASS:"))
        check(Files.readString(output.resolve("navigation.txt")).startsWith("PASS:"))
        check(Files.readString(output.resolve("recording-filter.txt")).startsWith("PASS:"))
        if (target == "standalone") {
          check(Files.size(output.resolve("explanation.png")) > 0)
          check(Files.size(output.resolve("details-dark.png")) > 0)
          check(Files.size(output.resolve("details-light.png")) > 0)
        } else check(Files.size(output.resolve("ijpl-ui.png")) > 0)
        check(Files.readString(output.resolve("live-evidence.txt")).startsWith("PASS:"))
        check(Files.size(output.resolve("live-recording.png")) > 0)
        check(Files.readString(output.resolve("live-unload.txt")).startsWith("PASS:"))
        Files.writeString(output.resolve("scenario.txt"), target)
      } finally {
        liveTarget?.close()
      }
    }
  }

  private fun requireCompatibleLocalIde() {
    val local = System.getProperty("jewel.test.idePath") ?: return
    val expected = System.getProperty("jewel.test.ideBuild")
    val home = Path.of(local)
    val info =
      listOf(
          home.resolve("Contents/Resources/product-info.json"),
          home.resolve("product-info.json"),
        )
        .firstOrNull { Files.exists(it) } ?: error("No product-info.json under $local")
    val build =
      BUILD_NUMBER.find(Files.readString(info))?.groupValues?.get(1)
        ?: error("Missing buildNumber in $info")
    check(build == expected) {
      "JewelTargetsTest requires the pinned IDE $expected. $local is $build."
    }
  }

  private companion object {
    private val BUILD_NUMBER = Regex(""""buildNumber"\s*:\s*"([^"]+)"""")
  }

  private fun copyTree(source: Path, destination: Path) {
    Files.walk(source).use { paths ->
      paths.forEach { path ->
        val target = destination.resolve(source.relativize(path))
        if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
      }
    }
  }
}
