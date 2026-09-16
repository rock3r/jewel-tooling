package dev.sebastiano.jewel.tooling

import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderFactory
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class StabilityAnalysisTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getProjectDescriptor(): com.intellij.testFramework.LightProjectDescriptor =
    object : com.intellij.testFramework.LightProjectDescriptor() {
      override fun getSdk(): com.intellij.openapi.projectRoots.Sdk =
        com.intellij.openapi.projectRoots.JavaSdk.getInstance()
          .createJdk("Test JDK", System.getProperty("java.home"), false)
    }

  override fun setUp() {
    super.setUp()
    val stdlib = File(System.getProperty("jewel.tooling.stdlib"))
    PsiTestUtil.addLibrary(module, "kotlin-stdlib", stdlib.parent, stdlib.name)
    myFixture.addFileToProject(
      "androidx/compose/runtime/Annotations.kt",
      """
      package androidx.compose.runtime
      @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
      annotation class Composable
      @Target(AnnotationTarget.ANNOTATION_CLASS)
      annotation class StableMarker
      @StableMarker @Target(AnnotationTarget.CLASS)
      annotation class Stable
      @StableMarker @Target(AnnotationTarget.CLASS)
      annotation class Immutable
      """
        .trimIndent(),
    )
  }

  fun testBuiltinNullableFunctionEnumAndAliases() {
    val hints =
      hints(
        """
      import androidx.compose.runtime.Composable as Render
      enum class Mode { A }
      typealias Title = String
      @Render fun Demo(text: Title?, count: Int, action: () -> Unit, mode: Mode?) {}
    """
      )
    assertEquals(List(4) { Stability.STABLE }, hints.map { it.assessment.stability })
  }

  fun testFieldsAndConcreteGenericSubstitutions() {
    assertVerdict("class Model(val title: String)", "Model", Stability.STABLE, "reason.fields")
    assertVerdict(
      "class Model(var title: String)",
      "Model",
      Stability.UNSTABLE,
      "reason.mutable",
      "title",
    )
    assertVerdict("class Box<T>(val value: T)", "Box<String>", Stability.STABLE, "reason.fields")
    assertVerdict("class Box<T>(val value: T)", "Box<Box<String>>", Stability.UNKNOWN)
    assertVerdict("class Box<T>(val value: T)", "Box<*>", Stability.UNKNOWN)
    assertVerdict(
      "class Inner<T>(val value: T); class Outer<T>(val inner: T)",
      "Outer<Inner<String>>",
      Stability.STABLE,
    )
  }

  fun testCollectionsUnsignedAndExternalTypes() {
    assertVerdict("", "List<String>?", Stability.UNSTABLE, "reason.collection")
    assertVerdict("", "Array<String>", Stability.UNSTABLE, "reason.collection")
    assertVerdict("", "UInt", Stability.UNKNOWN, "reason.valueClass")
    assertVerdict("", "Pair<String, String>", Stability.UNKNOWN, "reason.external")
    assertVerdict("", "Triple<String, String, String>", Stability.UNKNOWN, "reason.external")
  }

  fun testSingletonValueClassComputedCompanionDelegation() {
    assertVerdict("object Model", "Model", Stability.UNKNOWN, "reason.singleton")
    assertVerdict(
      "@JvmInline value class Model(val value: Int)",
      "Model",
      Stability.UNKNOWN,
      "reason.valueClass",
    )
    assertVerdict("class Model { val title get() = mutableListOf(1) }", "Model", Stability.STABLE)
    assertVerdict("class Model { companion object { var count = 0 } }", "Model", Stability.STABLE)
    assertVerdict("class Model { val title by lazy { 1 } }", "Model", Stability.UNKNOWN)
    assertVerdict(
      "class Model { val title by lazy { 1 }; var count = 0 }",
      "Model",
      Stability.UNSTABLE,
    )
  }

  fun testContractsAndFakeShortNames() {
    assertVerdict(
      "@androidx.compose.runtime.Stable class Model(var value: String)",
      "Model?",
      Stability.STABLE,
    )
    assertVerdict(
      "annotation class Stable; @Stable class Model(var value: String)",
      "Model",
      Stability.UNSTABLE,
    )
    assertVerdict(
      "@androidx.compose.runtime.StableMarker annotation class Custom; @Custom class Model(var value: String)",
      "Model",
      Stability.UNKNOWN,
      "reason.unsupportedContract",
    )
    assertTrue(hints("annotation class Composable; @Composable fun Demo(value: Int) {}").isEmpty())
  }

  fun testInnerClassCapturedStateIsUnknown() {
    assertVerdict(
      "class Outer(var title: String) { inner class Inner }",
      "Outer.Inner",
      Stability.UNKNOWN,
      "reason.captured",
    )
  }

  fun testProviderDefersDuringIndexing() {
    myFixture.configureByText("Example.kt", "fun Demo(count: Int) {}")
    com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(project) {
      assertNull(StabilityInlayProvider().createCollector(myFixture.file, myFixture.editor))
    }
  }

  fun testReceiverAndVararg() {
    val hints =
      hints(
        "import androidx.compose.runtime.Composable; @Composable fun String.Demo(vararg items: Int) {}"
      )
    assertEquals(
      listOf(Stability.STABLE, Stability.UNSTABLE),
      hints.map { it.assessment.stability },
    )
    assertEquals(JewelToolingBundle.message("hint.receiver"), hints.first().name)
  }

  fun testBudgetPreservesCompletedParameters() {
    val source =
      "import androidx.compose.runtime.Composable; class Wide(" +
        (1..260).joinToString { "val p$it: Int" } +
        "); @Composable fun Demo(first: Int, wide: Wide, last: String) {}"
    val hints = hints(source)
    assertEquals(
      listOf(Stability.STABLE, Stability.UNKNOWN, Stability.UNKNOWN),
      hints.map { it.assessment.stability },
    )
    assertTrue(hints[1].assessment.reason.contains(JewelToolingBundle.message("reason.bounded")))
    assertEquals(JewelToolingBundle.message("reason.bounded"), hints[2].assessment.reason)
  }

  fun testReferencedClassEditRecomputes() {
    hints(
      "import androidx.compose.runtime.Composable; class Model(val value: Int); @Composable fun Demo(model: Model) {}"
    )
    WriteCommandAction.runWriteCommandAction(project) {
      val document = myFixture.editor.document
      val start = document.text.indexOf("val value")
      document.replaceString(start, start + 3, "var")
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
    assertEquals(Stability.UNSTABLE, analyzeCurrent().single().assessment.stability)
  }

  fun testTooltipKeepsExplanationAndLimits() {
    val tooltip = JewelToolingBundle.message("hint.tooltip", "count", "stable", "A specific reason")
    assertTrue(tooltip.contains("A specific reason"))
    assertTrue(tooltip.contains("strong skipping"))
    assertTrue(tooltip.contains('\n'))
  }

  fun testRegisteredProviderPositionsAndExplanation() {
    val source = "import androidx.compose.runtime.Composable; @Composable fun Demo(count: Int) {}"
    val provider =
      requireNotNull(
          InlayHintsProviderFactory.getProviderInfo(
            KotlinLanguage.INSTANCE,
            "jewel.compose.stability",
          )
        )
        .provider
    val captured = collect(source, provider)
    assertEquals(1, captured.size)
    assertEquals(source.indexOf("Int") + 3, captured.single().offset)
    assertEquals("stable", captured.single().label)
    assertTrue(captured.single().tooltip.contains("built-in stable type"))
    assertTrue(captured.single().tooltip.contains("strong skipping"))
  }

  fun testFailureContainmentAndCancellation() {
    val source =
      "annotation class Marker; @Marker fun Broken(x: Int) {}; @Marker fun Healthy(x: Int) {}"
    val failing = StabilityInlayProvider { function ->
      if (function.name == "Broken") throw IllegalStateException("Injected failure")
      listOf(
        ParameterHint(
          function.valueParameters.single().typeReference!!.textRange.endOffset,
          "x",
          StabilityAssessment(Stability.STABLE, "test reason"),
        )
      )
    }
    assertEquals(listOf("stable"), collect(source, failing).map { it.label })
    for (failure in listOf(ProcessCanceledException(), CancellationException("test"))) {
      val thrown =
        org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException::class.java) {
          collect(source, StabilityInlayProvider { throw failure })
        }
      assertSame(failure, thrown.cause)
    }
  }

  fun testAnnotatedNonComposableStress() {
    val source =
      "annotation class Marker; " + (1..500).joinToString("\n") { "@Marker fun f$it(x: Int) {}" }
    assertTrue(collect(source, StabilityInlayProvider()).isEmpty())
  }

  fun testDepthBudget() {
    val classes =
      (0..14).joinToString("; ") { i ->
        "class C$i(val item: " + (if (i == 14) "Int" else "C${i + 1}") + ")"
      }
    val result =
      hints(
          "import androidx.compose.runtime.Composable; $classes; @Composable fun Demo(value: C0) {}"
        )
        .single()
    assertEquals(Stability.UNKNOWN, result.assessment.stability)
    assertTrue(result.assessment.reason.contains(JewelToolingBundle.message("reason.bounded")))
  }

  fun testNullableSourceAndUnresolvedTypes() {
    assertVerdict("class Model(val title: String)", "Model?", Stability.STABLE, "reason.fields")
    assertVerdict(
      "class Model(var title: String)",
      "Model?",
      Stability.UNSTABLE,
      "reason.mutable",
      "title",
    )
    assertVerdict("", "MissingType", Stability.UNKNOWN, "reason.unresolved")
    assertVerdict(
      "typealias Items = MutableList<String>",
      "Items",
      Stability.UNSTABLE,
      "reason.collection",
    )
    assertVerdict(
      "@androidx.compose.runtime.Immutable class Model(var title: String)",
      "Model?",
      Stability.STABLE,
      "reason.contract",
      "Immutable",
    )
    assertVerdict("interface Model", "Model", Stability.UNKNOWN, "reason.open")
    assertVerdict(
      "open class Base; class Model : Base()",
      "Model",
      Stability.UNKNOWN,
      "reason.inheritance",
    )
  }

  fun testJavaPlatformPropertyAndMappedCollection() {
    myFixture.addClass("public class JavaValues { public static String title() { return null; } }")
    assertVerdict(
      "class Model { val title = JavaValues.title() }",
      "Model",
      Stability.UNKNOWN,
      "reason.property",
      "title",
      JewelToolingBundle.message("reason.unsupported"),
    )
    assertVerdict("", "java.util.List<String>", Stability.UNSTABLE, "reason.collection")
  }

  fun testUnsupportedAndPlatformReasons() {
    assertVerdict(
      "",
      "java.util.concurrent.ConcurrentHashMap<String, String>",
      Stability.UNKNOWN,
      "reason.platformType",
    )
    val result =
      hints(
          "import androidx.compose.runtime.Composable; @Composable fun <T> Demo(value: T & Any) {}"
        )
        .single()
    assertEquals(Stability.UNKNOWN, result.assessment.stability)
    assertEquals(JewelToolingBundle.message("reason.unsupported"), result.assessment.reason)
    for (type in listOf("UInt", "ULong", "UByte", "UShort")) {
      assertVerdict("", type, Stability.UNKNOWN, "reason.valueClass")
    }
  }

  fun testContextParametersDoNotShiftValueParameterHints() {
    val result =
      hints(
        "import androidx.compose.runtime.Composable; " +
          "context(logger: String) @Composable fun Demo(items: List<String>, count: Int) {}"
      )
    assertEquals(listOf("items", "count"), result.map { it.name })
    assertEquals(
      listOf(Stability.UNSTABLE, Stability.STABLE),
      result.map { it.assessment.stability },
    )
  }

  fun testK2OnlyPluginDescriptor() {
    val plugin =
      requireNotNull(
        com.intellij.ide.plugins.PluginManagerCore.getPlugin(
          com.intellij.openapi.extensions.PluginId.getId("dev.sebastiano.jewel.tooling")
        )
      )
    val descriptors = plugin.pluginClassLoader!!.getResources("META-INF/plugin.xml").asSequence()
    val descriptor =
      descriptors
        .map { url ->
          url.openStream().use {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
          }
        }
        .first {
          it.getElementsByTagName("id").item(0)?.textContent == "dev.sebastiano.jewel.tooling"
        }
    val mode =
      descriptor.getElementsByTagName("supportsKotlinPluginMode").item(0) as org.w3c.dom.Element
    assertEquals("false", mode.getAttribute("supportsK1"))
    assertEquals("true", mode.getAttribute("supportsK2"))
  }

  @Suppress(
    "DEPRECATION"
  ) // Intentionally cancellable read action; Blocking would mask this assertion.
  fun testRealAnalysisHonorsCancellation() {
    myFixture.configureByText(
      "Example.kt",
      "import androidx.compose.runtime.Composable; @Composable fun Demo(value: String) {}",
    )
    val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
    val failure =
      org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException::class.java) {
        AppExecutorUtil.getAppExecutorService()
          .submit(
            Callable {
              com.intellij.openapi.progress.ProgressManager.getInstance()
                .runProcess(
                  Runnable {
                    com.intellij.openapi.application.ReadAction.run<RuntimeException> {
                      indicator.cancel()
                      val function =
                        (myFixture.file as KtFile)
                          .declarations
                          .filterIsInstance<KtNamedFunction>()
                          .single()
                      StabilityAnalysis.hints(function)
                    }
                  },
                  indicator,
                )
            }
          )
          .get(5, TimeUnit.SECONDS)
      }
    assertTrue(failure.cause is ProcessCanceledException)
  }

  fun testRecursiveAndStorageReasons() {
    val recursive = JewelToolingBundle.message("reason.recursive")
    val typeArgument = JewelToolingBundle.message("reason.typeArgument")
    assertVerdict(
      "class Box<T>(val value: T)",
      "Box<Box<String>>",
      Stability.UNKNOWN,
      "reason.property",
      "value",
      recursive,
    )
    assertVerdict(
      "class Box<T>(val value: T)",
      "Box<*>",
      Stability.UNKNOWN,
      "reason.property",
      "value",
      typeArgument,
    )
    assertVerdict(
      "class Model { val title by lazy { 1 } }",
      "Model",
      Stability.UNKNOWN,
      "reason.delegated",
      "title",
    )
    assertVerdict(
      "class Model { companion object { var count = 0 } }",
      "Model",
      Stability.STABLE,
      "reason.fields",
    )
    assertVerdict(
      "class Model { val title get() = mutableListOf(1) }",
      "Model",
      Stability.STABLE,
      "reason.fields",
    )
    assertVerdict("enum class Model { ONE }", "Model?", Stability.STABLE, "reason.enum")
    assertVerdict(
      "class Inner<T>(val value: T); class Outer<T>(val inner: T)",
      "Outer<Inner<String>>",
      Stability.STABLE,
      "reason.fields",
    )
  }

  private data class CapturedHint(val offset: Int, val label: String, val tooltip: String)

  private fun collect(source: String, provider: InlayHintsProvider): List<CapturedHint> {
    myFixture.configureByText("Example.kt", source)
    val editor = myFixture.editor
    return AppExecutorUtil.getAppExecutorService()
      .submit(
        Callable<List<CapturedHint>> {
          runReadActionBlocking {
            val captured = mutableListOf<CapturedHint>()
            val collector =
              provider.createCollector(myFixture.file, editor) as SharedBypassCollector
            val sink =
              object : InlayTreeSink {
                override fun whenOptionEnabled(optionId: String, block: () -> Unit) = block()

                override fun addPresentation(
                  position: InlayPosition,
                  payloads: List<InlayPayload>?,
                  tooltip: String?,
                  hintFormat: HintFormat,
                  builder: PresentationTreeBuilder.() -> Unit,
                ) {
                  val label = StringBuilder()
                  val tree =
                    object : PresentationTreeBuilder {
                      override fun text(text: String, actionData: InlayActionData?) {
                        label.append(text)
                      }

                      override fun list(builder: PresentationTreeBuilder.() -> Unit) = builder()

                      override fun clickHandlerScope(
                        actionData: InlayActionData,
                        builder: PresentationTreeBuilder.() -> Unit,
                      ) = builder()

                      override fun collapsibleList(
                        state: CollapseState,
                        expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
                        collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit,
                      ) {
                        error("Unexpected collapsible hint")
                      }
                    }
                  tree.builder()
                  captured +=
                    CapturedHint(
                      (position as InlineInlayPosition).offset,
                      label.toString(),
                      tooltip.orEmpty(),
                    )
                }
              }
            (myFixture.file as KtFile).declarations.filterIsInstance<KtNamedFunction>().forEach {
              collector.collectFromElement(it, sink)
            }
            captured
          }
        }
      )
      .get(30, TimeUnit.SECONDS)
  }

  private fun assertVerdict(
    declarations: String,
    type: String,
    expected: Stability,
    reason: String? = null,
    vararg args: Any,
  ) {
    val result =
      hints(
          "import androidx.compose.runtime.Composable; $declarations; @Composable fun Demo(model: $type) {}"
        )
        .single()
        .assessment
    assertEquals(result.reason, expected, result.stability)
    if (reason != null) assertEquals(JewelToolingBundle.message(reason, *args), result.reason)
  }

  private fun hints(source: String): List<ParameterHint> {
    myFixture.configureByText("Example.kt", source.trimIndent())
    return analyzeCurrent()
  }

  private fun analyzeCurrent(): List<ParameterHint> =
    ApplicationManager.getApplication()
      .executeOnPooledThread<List<ParameterHint>> {
        runReadActionBlocking {
          val function =
            (myFixture.file as KtFile).declarations.filterIsInstance<KtNamedFunction>().single()
          StabilityAnalysis.hints(function)
        }
      }
      .get(30, TimeUnit.SECONDS)
}
