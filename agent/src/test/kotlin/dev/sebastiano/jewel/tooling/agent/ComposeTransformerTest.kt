package dev.sebastiano.jewel.tooling.agent

import dev.sebastiano.jewel.tooling.bridge.TraceBridge
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.util.CheckClassAdapter

class ComposeTransformerTest {
  @After
  fun reset() {
    TraceBridge.install(null)
    TraceBridge.gate(false, 1)
  }

  @Test
  fun inactiveForeignTracerReceivesNoForcedCallbacks() {
    val sink = CountingSink()
    TraceBridge.install(sink)
    val type = transformed()
    assertTrue(type.getMethod("isTraceInProgress").invoke(null) as Boolean)
    type
      .getMethod(
        "traceEventStart",
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        String::class.java,
      )
      .invoke(null, 12, 0, 0, "example")
    assertTrue(type.getMethod("isTraceInProgress").invoke(null) as Boolean)
    type.getMethod("traceEventEnd").invoke(null)
    assertEquals(0, type.getField("starts").getInt(null))
    assertEquals(0, type.getField("ends").getInt(null))
    assertEquals(1, sink.starts)
    assertEquals(1, sink.ends)
  }

  @Test
  fun activeForeignTracerKeepsItsCallbacks() {
    val sink = CountingSink()
    TraceBridge.install(sink)
    val type = transformed()
    type.getField("active").setBoolean(null, true)
    assertTrue(type.getMethod("isTraceInProgress").invoke(null) as Boolean)
    type
      .getMethod(
        "traceEventStart",
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        String::class.java,
      )
      .invoke(null, 12, 0, 0, "example")
    assertTrue(type.getMethod("isTraceInProgress").invoke(null) as Boolean)
    type.getMethod("traceEventEnd").invoke(null)
    assertEquals(1, type.getField("starts").getInt(null))
    assertEquals(1, type.getField("ends").getInt(null))
    assertEquals(1, sink.starts)
    assertEquals(1, sink.ends)
  }

  @Test
  fun inactiveAgentPreservesGateAndDirectCalls() {
    TraceBridge.install(null)
    val type = transformed()
    assertFalse(type.getMethod("isTraceInProgress").invoke(null) as Boolean)
    type.getMethod("traceEventEnd").invoke(null)
    assertEquals(1, type.getField("ends").getInt(null))
  }

  @Test
  fun callbackFailureDoesNotCallForeignTracerForForcedEvent() {
    val sink = CountingSink().apply { failStart = true }
    TraceBridge.install(sink)
    val type = transformed()
    assertTrue(type.getMethod("isTraceInProgress").invoke(null) as Boolean)
    type
      .getMethod(
        "traceEventStart",
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        String::class.java,
      )
      .invoke(null, 1, 0, 0, "example")
    assertEquals(0, type.getField("starts").getInt(null))
    assertTrue(sink.failed)
    assertFalse(type.getMethod("isTraceInProgress").invoke(null) as Boolean)
  }

  @Test
  fun rejectsMissingMethodWithoutReturningPartialTransformation() {
    var failure = false
    val transformer = ComposeTransformer({ _, _ -> 1 }, { failure = true })
    assertNull(
      transformer.transform(
        null,
        javaClass.classLoader,
        ComposeTransformer.COMPOSER,
        null,
        null,
        fixture(false),
      )
    )
    assertTrue(failure)
  }

  private fun transformed(): Class<*> {
    val transformer = ComposeTransformer({ _, _ -> 1 }, { fail("Supported fixture rejected") })
    val bytes =
      checkNotNull(
        transformer.transform(
          null,
          javaClass.classLoader,
          ComposeTransformer.COMPOSER,
          null,
          null,
          fixture(true),
        )
      )
    val errors = StringWriter()
    CheckClassAdapter.verify(ClassReader(bytes), javaClass.classLoader, false, PrintWriter(errors))
    assertEquals("", errors.toString())
    return object : ClassLoader(javaClass.classLoader) {
        fun define(): Class<*> =
          defineClass(ComposeTransformer.COMPOSER.replace('/', '.'), bytes, 0, bytes.size)
      }
      .define()
  }

  private fun fixture(includeEnd: Boolean): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    val flags = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC
    writer.visit(
      Opcodes.V21,
      Opcodes.ACC_PUBLIC,
      ComposeTransformer.COMPOSER,
      null,
      "java/lang/Object",
      null,
    )
    for (name in listOf("starts", "ends")) writer
      .visitField(flags, name, "I", null, null)
      .visitEnd()
    writer.visitField(flags, "active", "Z", null, null).visitEnd()
    writer.visitMethod(flags, "isTraceInProgress", "()Z", null, null).apply {
      visitCode()
      visitFieldInsn(Opcodes.GETSTATIC, ComposeTransformer.COMPOSER, "active", "Z")
      visitInsn(Opcodes.IRETURN)
      visitMaxs(0, 0)
      visitEnd()
    }
    val methods = mutableListOf(Triple("traceEventStart", "(IIILjava/lang/String;)V", "starts"))
    if (includeEnd) methods += Triple("traceEventEnd", "()V", "ends")
    for ((name, descriptor, field) in methods) writer
      .visitMethod(flags, name, descriptor, null, null)
      .apply {
        visitCode()
        visitFieldInsn(Opcodes.GETSTATIC, ComposeTransformer.COMPOSER, field, "I")
        visitInsn(Opcodes.ICONST_1)
        visitInsn(Opcodes.IADD)
        visitFieldInsn(Opcodes.PUTSTATIC, ComposeTransformer.COMPOSER, field, "I")
        visitInsn(Opcodes.RETURN)
        visitMaxs(0, 0)
        visitEnd()
      }
    writer.visitEnd()
    return writer.toByteArray()
  }

  private class CountingSink : TraceBridge.Sink {
    var starts = 0
    var ends = 0
    var failStart = false
    var failed = false

    override fun enabled(runtime: Int) = true

    override fun start(runtime: Int, key: Int, dirty1: Int, dirty2: Int, info: String) {
      if (failStart) error("Observer failure")
      starts++
    }

    override fun end(runtime: Int) {
      ends++
    }

    override fun failed() {
      failed = true
    }
  }
}
