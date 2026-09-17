package dev.sebastiano.jewel.tooling

import java.util.zip.ZipFile
import junit.framework.TestCase
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

class CompilerStabilityMetadataTest : TestCase() {
  fun testActualCompilerOutput() {
    ZipFile(System.getProperty("jewel.tooling.compilerFixtures")).use { jar ->
      fun result(name: String, count: Int = 0): CompilerStabilityMetadata.Result {
        val internal = "evidence/$name"
        val bytes = jar.getInputStream(jar.getEntry("$internal.class")).use { it.readBytes() }
        return CompilerStabilityMetadata.decode(bytes, internal, count)
      }
      assertEquals(CompilerStabilityMetadata.Result.Proven(false, 0), result("Stable"))
      assertEquals(CompilerStabilityMetadata.Result.Proven(true, 0), result("Mutable"))
      assertEquals(CompilerStabilityMetadata.Result.Proven(false, 1), result("Used", 1))
      assertEquals(CompilerStabilityMetadata.Result.Proven(false, 0), result("Unused", 1))
      assertEquals(CompilerStabilityMetadata.Result.Proven(false, 2), result("Partial", 3))
      assertEquals(CompilerStabilityMetadata.Result.Proven(false, 0), result("MethodOnly", 1))
      assertEquals(CompilerStabilityMetadata.Result.Proven(false, 1), result("Covariant", 1))
      assertEquals(CompilerStabilityMetadata.Result.Proven(false, 1), result("Bounded", 1))
      assertEquals(CompilerStabilityMetadata.Result.Proven(true, 0), result("MutableGeneric", 1))
      assertEquals(CompilerStabilityMetadata.Result.Proven(true, 0), result("Inherited", 1))
      assertEquals(CompilerStabilityMetadata.Result.Proven(false, 2047), result("Used11", 11))
      assertEquals(CompilerStabilityMetadata.Result.Proven(false, 0), result("Unused3", 3))
      assertEquals(
        CompilerStabilityMetadata.Result.Proven(false, 0),
        result("Outer" + "$" + "Nested"),
      )
      for (name in listOf("Base", "WithInitializer", "Singleton", "Value", "Contract", "Choice")) {
        assertEquals(
          name,
          CompilerStabilityMetadata.Result.Unsupported,
          result(name, if (name == "Base") 1 else 0),
        )
      }
    }
  }

  fun testMaskAndFieldAreIndependent() {
    assertEquals(
      CompilerStabilityMetadata.Result.Proven(false, 0),
      decode(shape(mask = 8, count = 3), 3),
    )
    assertEquals(
      CompilerStabilityMetadata.Result.Proven(false, 2),
      decode(shape(mask = 2, count = 3), 3),
    )
    assertEquals(
      CompilerStabilityMetadata.Result.Proven(true, 0),
      decode(shape(mask = 0, base = 8)),
    )
    for (value in listOf(4, 12, 140)) assertUnsupported(shape(mask = 0, base = value))
    assertUnsupported(shape(mask = 1, base = 8))
    assertUnsupported(shape(mask = 0))
    assertUnsupported(shape(mask = 3, count = 1), 1)
    assertUnsupported(shape(mask = 4, count = 1), 1)
    assertUnsupported(shape(mask = Int.MIN_VALUE, count = 31), 31)
  }

  fun testVersionIdentityAndClassShape() {
    assertUnsupported(shape(version = 66))
    assertEquals(CompilerStabilityMetadata.Result.Proven(false, 0), decode(shape(version = 65)))
    assertUnsupported(shape(metadataVersion = intArrayOf(2, 3, 0)))
    assertEquals(
      CompilerStabilityMetadata.Result.Unsupported,
      CompilerStabilityMetadata.decode(shape(), "other/Class", 0),
    )
    assertUnsupported(shape(access = Opcodes.ACC_PUBLIC))
    assertUnsupported(
      shape(access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC)
    )
    assertUnsupported(shape(annotationVisible = true))
    assertUnsupported(shape(duplicateAnnotation = true))
    assertUnsupported(shape(fieldDescriptor = "J"))
    assertUnsupported(shape(fieldAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC))
    assertUnsupported(shape(count = 1, mask = 2), 0)
  }

  fun testOnlyCompleteLiteralInitializersAreAccepted() {
    assertEquals(
      CompilerStabilityMetadata.Result.Proven(false, 0),
      decode(shape(noInitializer = true)),
    )
    assertEquals(
      CompilerStabilityMetadata.Result.Proven(true, 0),
      decode(shape(mask = 0, constant = 8, noInitializer = true)),
    )
    assertUnsupported(shape(mask = 0, constant = 8, base = 0))
    assertUnsupported(
      shape(
        initializer = {
          visitFieldInsn(Opcodes.GETSTATIC, "other/Type", "$" + "stable", "I")
          visitFieldInsn(Opcodes.PUTSTATIC, "evidence/Example", "$" + "stable", "I")
          visitInsn(Opcodes.RETURN)
        }
      )
    )
    assertUnsupported(
      shape(
        initializer = {
          val end = org.jetbrains.org.objectweb.asm.Label()
          visitJumpInsn(Opcodes.GOTO, end)
          visitLabel(end)
          visitInsn(Opcodes.RETURN)
        }
      )
    )
    assertUnsupported(
      shape(
        initializer = {
          visitInsn(Opcodes.ICONST_0)
          visitFieldInsn(Opcodes.PUTSTATIC, "other/Example", "$" + "stable", "I")
          visitInsn(Opcodes.RETURN)
        }
      )
    )
  }

  fun testMalformedAndOversizedInputIsUnsupported() {
    val bytes = shape()
    for (size in listOf(0, 4, 16, bytes.size - 1)) assertUnsupported(bytes.copyOf(size))
    assertUnsupported(ByteArray(CompilerStabilityMetadata.MAX_BYTES + 1))
    assertUnsupported(shape(signature = "<".repeat(4097)))
    assertUnsupported(shape(signature = "[".repeat(65) + "Ljava/lang/Object;"))
  }

  fun testCorruptedClassBytesDoNotEscapeTheDecoder() {
    val valid = shape()
    for (index in valid.indices) {
      val corrupted = valid.copyOf()
      corrupted[index] = (corrupted[index].toInt() xor 0xff).toByte()
      try {
        decode(corrupted)
      } catch (failure: Exception) {
        throw AssertionError("Malformed class byte at offset $index escaped the decoder", failure)
      }
    }
  }

  private fun decode(bytes: ByteArray, count: Int = 0) =
    CompilerStabilityMetadata.decode(bytes, "evidence/Example", count)

  private fun assertUnsupported(bytes: ByteArray, count: Int = 0) =
    assertEquals(CompilerStabilityMetadata.Result.Unsupported, decode(bytes, count))

  private fun shape(
    mask: Int = 1,
    count: Int = 0,
    base: Int? = null,
    constant: Int? = null,
    version: Int = 65,
    access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
    metadataVersion: IntArray = intArrayOf(2, 4, 0),
    annotationVisible: Boolean = false,
    duplicateAnnotation: Boolean = false,
    fieldDescriptor: String = "I",
    fieldAccess: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
    noInitializer: Boolean = false,
    signature: String? =
      if (count == 0) null
      else
        "<" +
          (0 until count).joinToString("") { "T$it:Ljava/lang/Object;" } +
          ">Ljava/lang/Object;",
    initializer: (MethodVisitor.() -> Unit)? = null,
  ): ByteArray {
    val writer = ClassWriter(0)
    writer.visit(version, access, "evidence/Example", signature, "java/lang/Object", null)
    writer.visitAnnotation("Lkotlin/Metadata;", true).apply {
      visit("mv", metadataVersion)
      visit("k", 1)
      visitEnd()
    }
    repeat(if (duplicateAnnotation) 2 else 1) {
      writer
        .visitAnnotation("Landroidx/compose/runtime/internal/StabilityInferred;", annotationVisible)
        .apply {
          visit("parameters", mask)
          visitEnd()
        }
    }
    writer.visitField(fieldAccess, "$" + "stable", fieldDescriptor, null, constant).visitEnd()
    if (!noInitializer) {
      writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
        visitCode()
        if (initializer != null) initializer()
        else {
          if (base != null) {
            visitLdcInsn(base)
            visitFieldInsn(Opcodes.PUTSTATIC, "evidence/Example", "$" + "stable", "I")
          }
          visitInsn(Opcodes.RETURN)
        }
        visitMaxs(1, 0)
        visitEnd()
      }
    }
    writer.visitEnd()
    return writer.toByteArray()
  }
}
