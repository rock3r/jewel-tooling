package dev.sebastiano.jewel.tooling

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtNamedFunction

class McpAnalysisTest : LightJavaCodeInsightFixtureTestCase() {
  override fun getTempDirFixture() =
    com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl()

  override fun getProjectDescriptor(): com.intellij.testFramework.LightProjectDescriptor =
    object : com.intellij.testFramework.LightProjectDescriptor() {
      override fun getSdk(): com.intellij.openapi.projectRoots.Sdk =
        com.intellij.openapi.projectRoots.JavaSdk.getInstance()
          .createJdk("MCP test JDK", System.getProperty("java.home"), false)
    }

  override fun setUp() {
    super.setUp()
    PsiTestUtil.addSourceContentToRoots(module, myFixture.tempDirFixture.findOrCreateDir(""))
    val stdlib = kotlinStdlibJar(testRootDisposable)
    PsiTestUtil.addLibrary(module, "kotlin-stdlib", stdlib.parent, stdlib.name)
    val binary = File(System.getProperty("jewel.tooling.compilerFixtures"))
    PsiTestUtil.addLibrary(module, "compiler-fixtures", binary.parent, binary.name)
    myFixture.addFileToProject(
      "androidx/compose/runtime/Annotations.kt",
      """
      package androidx.compose.runtime
      @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE) annotation class Composable
      @Target(AnnotationTarget.CLASS) annotation class Stable
      """
        .trimIndent(),
    )
  }

  fun testParityEvidenceAndUnsavedDocument() {
    val file =
      myFixture.addFileToProject(
        "Screen.kt",
        """
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.Stable
        @Stable class Contract
        class Mutable(var value: String)
        @Composable fun Screen(text: String, mutable: Mutable, contract: Contract, binary: evidence.Stable, unknown: Missing) {}
        """
          .trimIndent(),
      )
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    val before = call("jewel_analyze")
    assertSchema("jewel_analyze", McpResults.success(before))
    assertSchema("jewel_analyze", McpResults.failure("INDEXING"))
    val parameters = before.declarations.single().parameters
    assertEquals(
      listOf("STABLE", "UNSTABLE", "STABLE", "STABLE", "UNKNOWN"),
      parameters.map { it.stability },
    )
    assertEquals("kotlin.String", parameters.first().resolvedType)
    assertTrue(parameters[2].evidence.any { it.code == "DECLARED_CONTRACT" })
    assertTrue(parameters[3].evidence.any { it.code == "COMPILER_METADATA" })
    assertEquals(listOf("UNRESOLVED_TYPE"), parameters.last().incomplete)
    val engine = background {
      runReadActionBlocking {
        StabilityAnalysis.inspect(
          PsiTreeUtil.findChildOfType(file, KtNamedFunction::class.java)!!
        )!!
      }
    }
    assertEquals(
      engine.parameters.map { it.assessment.stability.name },
      parameters.map { it.stability },
    )
    WriteCommandAction.runWriteCommandAction(project) {
      val offset = myFixture.editor.document.text.indexOf("text: String")
      myFixture.editor.document.replaceString(
        offset,
        offset + "text: String".length,
        "text: List<String>",
      )
    }
    val after = call("jewel_analyze")
    assertFalse(before.contentHash == after.contentHash)
    assertEquals("UNSTABLE", after.declarations.single().parameters.first().stability)
    assertTrue(File(file.virtualFile.path).readText().contains("text: String"))
    val stale = JsonObject().apply { addProperty("declarationId", before.declarations.single().id) }
    assertEquals("STALE_LOCATION", failure { call("jewel_analyze", stale) }.code)
  }

  fun testExplainAndProjectBounds() {
    val file =
      myFixture.addFileToProject(
        "Screen.kt",
        "import androidx.compose.runtime.Composable; @Composable fun Screen(value: String) {}",
      )
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    val listed = call("jewel_composables")
    assertSchema("jewel_composables", McpResults.success(listed))
    val args =
      JsonObject().apply {
        addProperty("declarationId", listed.declarations.single().id)
        addProperty("parameter", "value")
      }
    val explained = call("jewel_explain", args)
    assertSchema("jewel_explain", McpResults.success(explained))
    assertEquals("value", explained.declarations.single().parameters.single().name)
    val invalid = JsonObject().apply { addProperty("file", "../outside.kt") }
    assertEquals("UNSUPPORTED_FILE", failure { call("jewel_analyze", invalid) }.code)
    assertEquals("PROJECT_UNAVAILABLE", failure { call("jewel_analyze", enabled = false) }.code)
  }

  fun testDeclarationIdsBelongToTheFile() {
    val source =
      "import androidx.compose.runtime.Composable; @Composable fun Screen(value: String) {}"
    val first = myFixture.addFileToProject("Screen.kt", source)
    myFixture.addFileToProject("Other.kt", source)
    myFixture.configureFromExistingVirtualFile(first.virtualFile)
    val original = call("jewel_composables")
    val arguments =
      JsonObject().apply {
        addProperty("file", "Other.kt")
        addProperty("declarationId", original.declarations.single().id)
      }
    assertEquals("STALE_LOCATION", failure { call("jewel_analyze", arguments) }.code)
    arguments.addProperty("file", "./Screen.kt")
    assertEquals(
      original.declarations.single().id,
      call("jewel_analyze", arguments).declarations.single().id,
    )
  }

  fun testIndexingAndCancellation() {
    val file =
      myFixture.addFileToProject(
        "Screen.kt",
        "import androidx.compose.runtime.Composable; @Composable fun Screen(value: String) {}",
      )
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(project) {
      assertEquals("INDEXING", failure { call("jewel_analyze") }.code)
    }
    assertEquals(1, call("jewel_analyze").declarations.size)
    val root = file.virtualFile.parent.toNioPath().toRealPath()
    val cancelled = background {
      runCatching {
          runBlocking {
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            McpAnalysisFacade(project, "project", "generation", root) { true }
              .inspect("jewel_analyze", JsonObject().apply { addProperty("file", "Screen.kt") })
          }
        }
        .exceptionOrNull()
    }
    assertTrue(cancelled is kotlinx.coroutines.CancellationException)
  }

  fun testRangeValidationAndSourceLimit() {
    val file =
      myFixture.addFileToProject(
        "Screen.kt",
        """
        import androidx.compose.runtime.Composable
        @Composable fun First(value: String) {}
        @Composable fun Second(value: Int) {}
        """
          .trimIndent(),
      )
    myFixture.configureFromExistingVirtualFile(file.virtualFile)
    fun range(start: String, end: String) =
      com.google.gson.JsonParser.parseString("""{"range":{"start":$start,"end":$end}}""")
        .asJsonObject
    val point = """{"line":2,"column":20}"""
    assertEquals("First", call("jewel_analyze", range(point, point)).declarations.single().name)
    val fractional = """{"line":2.5,"column":1}"""
    assertEquals(
      "INVALID_ARGUMENT",
      failure { call("jewel_analyze", range(fractional, point)) }.code,
    )
    val extra = """{"line":2,"column":1,"unexpected":true}"""
    assertEquals("INVALID_ARGUMENT", failure { call("jewel_analyze", range(extra, point)) }.code)
    val outside = """{"line":200,"column":1}"""
    assertEquals("STALE_LOCATION", failure { call("jewel_analyze", range(outside, outside)) }.code)
    WriteCommandAction.runWriteCommandAction(project) {
      myFixture.editor.document.setText("//" + "x".repeat(McpAnalysisFacade.MAX_BYTES))
    }
    assertEquals("LIMIT_EXCEEDED", failure { call("jewel_analyze") }.code)
  }

  private fun assertSchema(tool: String, result: String) {
    val definitions =
      javaClass.getResourceAsStream("/mcp/tools.json")!!.use {
        com.google.gson.JsonParser.parseReader(it.reader()).asJsonArray
      }
    val schemaText =
      definitions
        .first { it.asJsonObject.get("name").asString == tool }
        .asJsonObject
        .get("outputSchema")
        .toString()
    val urls =
      System.getProperty("jewel.tooling.schemaValidator")
        .split(File.pathSeparator)
        .map { File(it).toURI().toURL() }
        .toTypedArray()
    java.net.URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
      val mapperClass = loader.loadClass("com.fasterxml.jackson.databind.ObjectMapper")
      val mapper = mapperClass.getConstructor().newInstance()
      val node = mapperClass.getMethod("readTree", String::class.java).invoke(mapper, result)
      val versionClass = loader.loadClass("com.networknt.schema.SpecVersion\$VersionFlag")
      val version = versionClass.getField("V202012").get(null)
      val factoryClass = loader.loadClass("com.networknt.schema.JsonSchemaFactory")
      val factory = factoryClass.getMethod("getInstance", versionClass).invoke(null, version)
      val schema =
        factoryClass.getMethod("getSchema", String::class.java).invoke(factory, schemaText)
      val errors =
        schema.javaClass
          .getMethod("validate", loader.loadClass("com.fasterxml.jackson.databind.JsonNode"))
          .invoke(schema, node) as Set<*>
      assertTrue(errors.toString(), errors.isEmpty())
    }
  }

  private fun call(
    tool: String,
    args: JsonObject = JsonObject(),
    enabled: Boolean = true,
  ): McpSnapshot {
    if (!args.has("file")) args.addProperty("file", "Screen.kt")
    val root = myFixture.file.virtualFile.parent.toNioPath().toRealPath()
    return background {
      runBlocking {
        McpAnalysisFacade(project, "project", "generation", root) { enabled }.inspect(tool, args)
      }
    }
  }

  private fun failure(block: () -> Unit): McpFailure {
    try {
      block()
    } catch (failure: McpFailure) {
      return failure
    }
    error("Expected an MCP failure")
  }

  private fun <T> background(block: () -> T): T {
    val future = java.util.concurrent.CompletableFuture<Result<T>>()
    ApplicationManager.getApplication().executeOnPooledThread {
      future.complete(runCatching(block))
    }
    PlatformTestUtil.waitWithEventsDispatching("MCP analysis did not finish", { future.isDone }, 30)
    return future.get().getOrThrow()
  }
}
