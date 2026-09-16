package dev.sebastiano.jewel.tooling.e2e

import com.intellij.driver.sdk.invokeGlobalBackendAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
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

class EditorSpikeTest {
  @Test
  @Suppress("LongMethod") // Sequential setup, assertions and teardown form one E2E scenario.
  fun realEditorUpdatesAndCaptures() {
    val project = Files.createTempDirectory("jewel-editor-spike-").toRealPath()
    val output =
      Files.createDirectories(
        Path.of(System.getProperty("jewel.test.artifacts"), "spike-" + System.nanoTime())
      )
    Files.createDirectories(project.resolve("src"))
    Files.createDirectories(project.resolve(".idea"))
    Files.writeString(
      project.resolve("src/Example.kt"),
      """
      import androidx.compose.runtime.Composable

      class Greeting(val title: String)

      @Composable
      fun GreetingRow(greeting: Greeting, items: List<String>) {}
      """
        .trimIndent(),
    )
    Files.writeString(
      project.resolve("src/Composable.kt"),
      """
      package androidx.compose.runtime
      @Target(AnnotationTarget.FUNCTION)
      annotation class Composable
      """
        .trimIndent(),
    )
    val stdlib = Path.of(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI())
    Files.writeString(
      project.resolve("spike.iml"),
      """<module type="JAVA_MODULE" version="4">
        <component name="NewModuleRootManager">
        <content url="file://${'$'}MODULE_DIR${'$'}">
        <sourceFolder url="file://${'$'}MODULE_DIR${'$'}/src" isTestSource="false"/>
        </content>
        <orderEntry type="sourceFolder" forTests="false"/>
        <orderEntry type="module-library">
        <library name="Kotlin stdlib">
        <CLASSES>
        <root url="jar://${stdlib}!/"/>
        </CLASSES>
        </library>
        </orderEntry>
        </component>
        </module>""",
    )
    Files.writeString(
      project.resolve(".idea/modules.xml"),
      """<project version="4">
        <component name="ProjectModuleManager">
        <modules>
        <module fileurl="file://${'$'}PROJECT_DIR${'$'}/spike.iml" filepath="${'$'}PROJECT_DIR${'$'}/spike.iml"/>
        </modules>
        </component>
        </project>""",
    )
    val context =
      Starter.newContext(
          CurrentTestMethod.hyphenateWithClass(),
          TestCase(
            IdeaUltimateProductInit()
              .ideInfo
              .copy(buildType = "release", buildNumber = System.getProperty("jewel.test.ideBuild")),
            LocalProjectInfo(project),
          ),
        )
        .apply {
          val installer = PluginConfigurator(this)
          installer.installPluginFromPath(Path.of(System.getProperty("jewel.test.productionZip")))
          installer.installPluginFromPath(Path.of(System.getProperty("jewel.test.driverZip")))
        }
        .applyVMOptionsPatch {
          addSystemProperty("jewel.test.output", output.toString())
          addSystemProperty("jetbrainsd.discovery.enabled", false)
          addSystemProperty("jetbrainsd.uri.handling.enabled", false)
          setIdeStartupDialogEnabled(false)
          disableStartupDialogs()
          disableNewUsersOnboardingDialogue()
        }
    context.runIdeWithDriver().useDriverAndCloseIde {
      waitForProjectOpen(timeout = 5.minutes)
      waitForIndicators(timeout = 5.minutes)
      invokeGlobalBackendAction("JewelTooling.RunEditorScenario", singleProject(), now = false)
      val deadline = System.nanoTime() + 240_000_000_000L
      val result = output.resolve("result.txt")
      while (!Files.exists(result) && System.nanoTime() < deadline) Thread.sleep(200)
      check(Files.exists(result)) { "Scenario did not finish; artifacts: $output" }
      check(Files.readString(result).startsWith("PASS:")) { Files.readString(result) }
      check(Files.size(output.resolve("editor-before.png")) > 0)
    }
  }
}
