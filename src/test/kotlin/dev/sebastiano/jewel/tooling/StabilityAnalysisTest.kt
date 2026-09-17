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

@Suppress("LargeClass") // Shared Kotlin PSI fixture covers inference and its editor presentation.
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
    val binaryFixtures = File(System.getProperty("jewel.tooling.compilerFixtures"))
    PsiTestUtil.addLibrary(module, "compiler-fixtures", binaryFixtures.parent, binaryFixtures.name)
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
    assertTrue(tooltip.contains("Static estimate"))
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
    assertTrue(captured.single().tooltip.contains("Static estimate"))
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

  fun testReportDistinguishesEmptyComposableAndCapturesTypes() {
    myFixture.configureByText(
      "Example.kt",
      """
      import androidx.compose.runtime.Composable as Render
      @Render fun Empty() {}
      @Render fun String.Demo(values: List<String>) {}
      fun Ordinary() {}
      """
        .trimIndent(),
    )
    val reports = backgroundRead {
      (myFixture.file as KtFile).declarations.filterIsInstance<KtNamedFunction>().map {
        StabilityAnalysis.inspect(it)
      }
    }
    assertNotNull(reports[0])
    assertTrue(reports[0]!!.parameters.isEmpty())
    assertEquals(
      JewelToolingBundle.message("summary.empty"),
      StabilityPresentation.counts(reports[0]!!),
    )
    assertEquals(listOf("String", "List<String>"), reports[1]!!.parameters.map { it.typeText })
    assertEquals("1 stable · 1 unstable · 0 unknown", StabilityPresentation.counts(reports[1]!!))
    assertNull(reports[2])
  }

  fun testMarkersResolveIdentityEscapeNamesAndUpdateAfterEdits() {
    myFixture.configureByText(
      "Example.kt",
      """
      import androidx.compose.runtime.Composable as Render
      class Model(val name: String)
      @Render fun `<demo>`(model: Model, external: Pair<String, String>) {}
      """
        .trimIndent(),
    )
    val first = markers().single()
    assertTrue(first.lineMarkerTooltip!!.contains("&lt;demo&gt;"))
    assertTrue(first.lineMarkerTooltip!!.contains("1 stable · 0 unstable · 1 unknown"))
    WriteCommandAction.runWriteCommandAction(project) {
      val start = myFixture.editor.document.text.indexOf("val name")
      myFixture.editor.document.replaceString(start, start + 3, "var")
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
    assertTrue(markers().single().lineMarkerTooltip!!.contains("0 stable · 1 unstable · 1 unknown"))
    myFixture.configureByText(
      "Example.kt",
      "annotation class Composable; @Composable fun Fake(value: Int) {}",
    )
    assertTrue(markers().isEmpty())
  }

  fun testMarkerDefersDuringIndexing() {
    myFixture.configureByText(
      "Example.kt",
      "import androidx.compose.runtime.Composable; @Composable fun Demo(value: Int) {}",
    )
    com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(project) {
      val result = mutableListOf<com.intellij.codeInsight.daemon.LineMarkerInfo<*>>()
      val function =
        (myFixture.file as KtFile).declarations.filterIsInstance<KtNamedFunction>().single()
      StabilityLineMarkerProvider { error("Must not analyze during indexing") }
        .collectSlowLineMarkers(listOf(function.nameIdentifier!!), result)
      assertTrue(result.isEmpty())
    }
  }

  fun testMarkerFailureContainmentAndCancellation() {
    myFixture.configureByText(
      "Example.kt",
      "import androidx.compose.runtime.Composable; @Composable fun Broken() {}; @Composable fun Good() {}",
    )
    val result =
      markers(
        StabilityLineMarkerProvider { function ->
          if (function.name == "Broken") throw IllegalStateException("Intentional fixture failure")
          FunctionStability(function.name!!, emptyList())
        }
      )
    assertEquals(1, result.size)
    val failure =
      org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException::class.java) {
        markers(StabilityLineMarkerProvider { throw ProcessCanceledException() })
      }
    assertTrue(failure.cause is ProcessCanceledException)
  }

  fun testMarkerStressAndNativeRegistration() {
    val source =
      "annotation class Other;\n" +
        (1..500).joinToString("\n") { "@Other fun Demo$it(value: Int) {}" }
    myFixture.configureByText("Example.kt", source)
    val started = System.nanoTime()
    assertTrue(markers().isEmpty())
    assertTrue(
      "500 marker candidates exceeded 30 seconds",
      System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30),
    )
    assertNotNull(
      com.intellij.openapi.actionSystem.ActionManager.getInstance()
        .getAction("JewelTooling.ShowStability")
    )
    myFixture.configureByText(
      "Example.kt",
      "import androidx.compose.runtime.Composable; @Composable fun Demo(value: Int) {}",
    )
    assertTrue(
      myFixture.findAllGutters().any {
        it.tooltipText?.contains("Click to inspect parameter stability") == true
      }
    )
  }

  fun testNativeGutterSettingHidesAndRestoresSummary() {
    myFixture.configureByText(
      "Example.kt",
      "import androidx.compose.runtime.Composable; @Composable fun Demo(value: Int) {}",
    )
    val provider = StabilityLineMarkerProvider()
    val settings = com.intellij.codeInsight.daemon.LineMarkerSettings.getSettings()
    try {
      settings.setEnabled(provider, false)
      com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project)
        .restart(myFixture.file, "Gutter setting changed in test")
      assertFalse(
        myFixture.findAllGutters().any {
          it.tooltipText?.contains("Click to inspect parameter stability") == true
        }
      )
      settings.setEnabled(provider, true)
      com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project)
        .restart(myFixture.file, "Gutter setting changed in test")
      assertTrue(
        myFixture.findAllGutters().any {
          it.tooltipText?.contains("Click to inspect parameter stability") == true
        }
      )
    } finally {
      settings.setEnabled(provider, true)
    }
  }

  fun testDetailsUsePlainAccessibleTextAndPreserveLimits() {
    val report =
      FunctionStability(
        "<html>Demo",
        listOf(
          ParameterHint(
            0,
            "<html>value",
            StabilityAssessment(Stability.UNKNOWN, "Unresolved <type> & reason"),
            "Pair<String, String>",
          )
        ),
      )
    val panel = StabilityDetailsPanel(report)
    assertEquals("Unresolved <type> & reason", panel.focus.accessibleContext.accessibleName)
    assertTrue("Tab must leave selectable explanation text", panel.focus.focusTraversalKeysEnabled)
    fun texts(component: java.awt.Component): List<String> =
      (when (component) {
        is javax.swing.JLabel -> {
          assertEquals(true, component.getClientProperty("html.disable"))
          listOf(component.text.orEmpty())
        }
        is javax.swing.text.JTextComponent -> listOf(component.text.orEmpty())
        else -> emptyList()
      }) +
        if (component is java.awt.Container) component.components.flatMap { texts(it) }
        else emptyList()
    val content = texts(panel.component).joinToString("\n")
    assertTrue(content.contains("<html>Demo"))
    assertTrue(content.contains("Pair<String, String>"))
    assertTrue(content.contains("strong skipping"))
    assertTrue(content.contains("Context parameters are not analyzed"))
    assertEquals(Stability.UNKNOWN, StabilityPresentation.overall(report))
    assertEquals(
      Stability.UNKNOWN,
      StabilityPresentation.overall(FunctionStability("Empty", emptyList())),
    )
  }

  fun testRealBinaryMetadataAndGenericSelection() {
    val results =
      hints(
          """
      import androidx.compose.runtime.Composable
      import evidence.*
      @Composable fun Demo(a: Stable, b: Mutable, c: Used<String>, d: Used<List<String>>,
        e: Used<*>, f: Unused<*>, g: Partial<*, String, *>, h: Partial<String, List<String>, String>,
        i: Unused3<*, *, *>, j: MutableGeneric<*>) {}
    """
        )
        .map { it.assessment }
    assertEquals(
      listOf(
        Stability.STABLE,
        Stability.UNSTABLE,
        Stability.STABLE,
        Stability.UNSTABLE,
        Stability.UNKNOWN,
        Stability.STABLE,
        Stability.STABLE,
        Stability.UNSTABLE,
        Stability.STABLE,
        Stability.UNSTABLE,
      ),
      results.map { it.stability },
    )
    assertEquals(setOf(Evidence.COMPILER_METADATA), results[0].evidence)
    assertEquals(setOf(Evidence.COMPILER_METADATA, Evidence.BUILTIN), results[2].evidence)
    assertEquals(setOf(Evidence.COMPILER_METADATA, Evidence.UNSUPPORTED), results[4].evidence)
    backgroundRead {
      val function =
        (myFixture.file as KtFile).declarations.filterIsInstance<KtNamedFunction>().single()
      org.jetbrains.kotlin.analysis.api.analyze(function) {
        val type =
          function.valueParameters.first().typeReference!!.type
            as org.jetbrains.kotlin.analysis.api.types.KaClassType
        val declaration =
          requireNotNull(type.expandedSymbol).psi as org.jetbrains.kotlin.psi.KtClass
        assertTrue(
          "The fixture must resolve as a binary Kotlin class",
          declaration.containingKtFile.isCompiled,
        )
      }
    }
  }

  fun testBinaryUnsupportedCasesDoNotHideHealthyParameters() {
    val results =
      hints(
          """
      import androidx.compose.runtime.Composable
      import evidence.*
      @Composable fun Demo(a: Base<String>, b: WithInitializer, c: Outer.Inner,
        d: Singleton, e: Value, f: Stable, g: Contract, h: Choice) {}
    """
        )
        .map { it.assessment }
    assertEquals(
      List(5) { Stability.UNKNOWN } + List(3) { Stability.STABLE },
      results.map { it.stability },
    )
    assertEquals(setOf(Evidence.DECLARED_CONTRACT), results[6].evidence)
    assertEquals(setOf(Evidence.BUILTIN), results[7].evidence)
  }

  fun testBinaryAndSourceEvidenceRemainDistinct() {
    val results =
      hints(
          """
      import androidx.compose.runtime.Composable
      import evidence.Used
      class Local(val name: String)
      class Wrapper(val value: Used<String>)
      @Composable fun Demo(a: Used<Local>, b: Wrapper) {}
    """
        )
        .map { it.assessment }
    for (result in results) {
      assertEquals(Stability.STABLE, result.stability)
      assertEquals(
        setOf(Evidence.COMPILER_METADATA, Evidence.SOURCE, Evidence.BUILTIN),
        result.evidence,
      )
      assertTrue(StabilityPresentation.evidence(result).startsWith("Mixed evidence:"))
    }
  }

  fun testSourceResolutionWinsOverBinaryWithSameName() {
    myFixture.addFileToProject(
      "evidence/Stable.kt",
      "package evidence; class Stable(var title: String)",
    )
    val result =
      hints(
          "import androidx.compose.runtime.Composable; @Composable fun Demo(a: evidence.Stable) {}"
        )
        .single()
        .assessment
    assertEquals(Stability.UNSTABLE, result.stability)
    assertEquals(setOf(Evidence.SOURCE), result.evidence)
  }

  fun testBinaryReaderNeverOpensStreamsOnEdt() {
    val file = BinaryFile("Stable.class", binaryBytes())
    assertTrue(ApplicationManager.getApplication().isDispatchThread)
    assertEquals(
      CompilerStabilityMetadata.Result.Unsupported,
      BinaryMetadataReader().read(file, "evidence/Stable", 0),
    )
    assertEquals(0, file.opens)
    assertEquals(
      CompilerStabilityMetadata.Result.Proven(false, 0),
      backgroundRead { BinaryMetadataReader().read(file, "evidence/Stable", 0) },
    )
    assertEquals(1, file.opens)
  }

  fun testBinaryReaderCapsActualBytesAndContainsIoFailure() {
    backgroundRead {
      val reader = BinaryMetadataReader()
      val oversized =
        BinaryFile("Oversized.class", ByteArray(CompilerStabilityMetadata.MAX_BYTES + 16))
      assertEquals(
        CompilerStabilityMetadata.Result.Unsupported,
        reader.read(oversized, "evidence/Stable", 0),
      )
      assertTrue(oversized.readBytes <= CompilerStabilityMetadata.MAX_BYTES + 1)
      val broken =
        BinaryFile("Broken.class", byteArrayOf(), java.io.IOException("Expected test read failure"))
      assertEquals(
        CompilerStabilityMetadata.Result.Unsupported,
        reader.read(broken, "evidence/Stable", 0),
      )
      val valid = BinaryFile("Stable.class", binaryBytes())
      assertEquals(
        CompilerStabilityMetadata.Result.Proven(false, 0),
        reader.read(valid, "evidence/Stable", 0),
      )
      assertEquals(
        CompilerStabilityMetadata.Result.Proven(false, 0),
        reader.read(valid, "evidence/Stable", 0),
      )
      assertEquals(1, valid.opens)
      // No decoding result survives a new inspection, even when a file retains its stamp.
      assertEquals(
        CompilerStabilityMetadata.Result.Proven(false, 0),
        BinaryMetadataReader().read(valid, "evidence/Stable", 0),
      )
      assertEquals(2, valid.opens)
      val capped = BinaryMetadataReader()
      repeat(4) { index ->
        assertEquals(
          CompilerStabilityMetadata.Result.Unsupported,
          capped.read(
            BinaryFile("$index.class", ByteArray(CompilerStabilityMetadata.MAX_BYTES)),
            "evidence/Stable",
            0,
          ),
        )
      }
      val afterLimit = BinaryFile("After.class", binaryBytes())
      assertEquals(
        CompilerStabilityMetadata.Result.Unsupported,
        capped.read(afterLimit, "evidence/Stable", 0),
      )
      assertEquals(0, afterLimit.opens)
      val classCapped = BinaryMetadataReader()
      repeat(32) { index ->
        classCapped.read(BinaryFile("$index.class", byteArrayOf()), "evidence/Stable", 0)
      }
      val afterClassLimit = BinaryFile("AfterClasses.class", binaryBytes())
      assertEquals(
        CompilerStabilityMetadata.Result.Unsupported,
        classCapped.read(afterClassLimit, "evidence/Stable", 0),
      )
      assertEquals(0, afterClassLimit.opens)
    }
  }

  fun testBinaryReaderPreservesCancellation() {
    val failure = ProcessCanceledException()
    val result =
      org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException::class.java) {
        backgroundRead {
          BinaryMetadataReader()
            .read(BinaryFile("Canceled.class", byteArrayOf(), failure), "evidence/Stable", 0)
        }
      }
    assertSame(failure, result.cause)
  }

  fun testBinaryMetadataStress() {
    val source =
      "import androidx.compose.runtime.Composable;\n" +
        (1..500).joinToString("\n") { "@Composable fun Demo$it(value: evidence.Stable) {}" }
    myFixture.configureByText("Example.kt", source)
    val started = System.nanoTime()
    val results = backgroundRead {
      (myFixture.file as KtFile).declarations.filterIsInstance<KtNamedFunction>().flatMap {
        StabilityAnalysis.hints(it)
      }
    }
    assertEquals(500, results.size)
    assertTrue(
      results.all {
        it.assessment.stability == Stability.STABLE &&
          it.assessment.evidence == setOf(Evidence.COMPILER_METADATA)
      }
    )
    val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    assertTrue("500 binary inspections exceeded 30 seconds: $millis ms", millis < 30_000)
    println("500 binary inspections: $millis ms")
  }

  private fun binaryBytes(): ByteArray =
    java.util.zip.ZipFile(System.getProperty("jewel.tooling.compilerFixtures")).use { jar ->
      jar.getInputStream(jar.getEntry("evidence/Stable.class")).use { it.readBytes() }
    }

  private class BinaryFile(
    name: String,
    private val bytes: ByteArray,
    private val failure: Exception? = null,
  ) : com.intellij.testFramework.LightVirtualFile(name) {
    var opens = 0
    var readBytes = 0

    override fun getLength(): Long = 0 // Exercise the actual stream cap, not the length fast path.

    override fun getInputStream(): java.io.InputStream {
      opens++
      failure?.let { throw it }
      return object : java.io.ByteArrayInputStream(bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
          super.read(buffer, offset, length).also { if (it > 0) readBytes += it }
      }
    }
  }

  private fun markers(
    provider: StabilityLineMarkerProvider = StabilityLineMarkerProvider()
  ): List<com.intellij.codeInsight.daemon.LineMarkerInfo<*>> = backgroundRead {
    val result = mutableListOf<com.intellij.codeInsight.daemon.LineMarkerInfo<*>>()
    val names =
      (myFixture.file as KtFile).declarations.filterIsInstance<KtNamedFunction>().mapNotNull {
        it.nameIdentifier
      }
    provider.collectSlowLineMarkers(names, result)
    result
  }

  @Suppress("DEPRECATION") // This fixture must propagate explicit cancellation.
  private fun <T> backgroundRead(block: () -> T): T =
    AppExecutorUtil.getAppExecutorService()
      .submit(
        Callable {
          com.intellij.openapi.application.ReadAction.compute<T, RuntimeException> { block() }
        }
      )
      .get(30, TimeUnit.SECONDS)

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
