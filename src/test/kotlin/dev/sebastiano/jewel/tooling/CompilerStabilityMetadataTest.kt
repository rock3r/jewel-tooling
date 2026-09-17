package dev.sebastiano.jewel.tooling

import java.util.zip.ZipFile
import junit.framework.TestCase
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

class CompilerStabilityMetadataTest : TestCase() {
  fun testActualCompilerOutput() = checkCompilerOutput("evidence", 65, intArrayOf(2, 4, 0))

  fun testStandaloneCompilerOutput() =
    checkCompilerOutput("standaloneevidence", 69, intArrayOf(2, 3, 0))

  fun testPlatformCompilerOutput() =
    checkCompilerOutput("platformevidence", 69, intArrayOf(2, 4, 0))

  private fun checkCompilerOutput(packageName: String, classVersion: Int, metadata: IntArray) {
    ZipFile(System.getProperty("jewel.tooling.compilerFixtures")).use { jar ->
      fun result(name: String, count: Int = 0): CompilerStabilityMetadata.Result {
        val internal = "$packageName/$name"
        val bytes = jar.getInputStream(jar.getEntry("$internal.class")).use { it.readBytes() }
        assertCompilerVersions(bytes, classVersion, metadata)
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
    for (version in 52..69) {
      assertEquals(
        CompilerStabilityMetadata.Result.Proven(false, 0),
        decode(shape(version = version)),
      )
    }
    assertUnsupported(shape(version = 51))
    assertUnsupported(shape(version = 70))
    assertUnsupported(shape(version = (0xffff shl 16) or 69))
    assertUnsupported(shape(version = (1 shl 16) or 69))
    assertEquals(CompilerStabilityMetadata.Result.Proven(false, 0), decode(shape(version = 65)))
    assertEquals(
      CompilerStabilityMetadata.Result.Proven(false, 0),
      decode(shape(metadataVersion = intArrayOf(2, 3, 0))),
    )
    for (metadata in
      listOf(
        intArrayOf(1, 9, 0),
        intArrayOf(2, 2, 0),
        intArrayOf(2, 5, 0),
        intArrayOf(2, 3, 1),
        intArrayOf(2, 4, 1),
        intArrayOf(2, 4),
        intArrayOf(2, 4, 0, 0),
      )) {
      assertUnsupported(shape(metadataVersion = metadata))
    }
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

  private fun assertCompilerVersions(
    bytes: ByteArray,
    expectedClass: Int,
    expectedMetadata: IntArray,
  ) {
    var metadataSeen = false
    ClassReader(bytes)
      .accept(
        object : ClassVisitor(Opcodes.ASM9) {
          override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
          ) {
            assertEquals(expectedClass, version)
          }

          override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            if (descriptor != "Lkotlin/Metadata;") return null
            return object : AnnotationVisitor(Opcodes.ASM9) {
              override fun visit(name: String?, value: Any?) {
                if (name == "mv") {
                  assertTrue(expectedMetadata.contentEquals(value as IntArray))
                  metadataSeen = true
                }
              }
            }
          }
        },
        ClassReader.SKIP_CODE,
      )
    assertTrue("The compiler must emit the expected Kotlin metadata", metadataSeen)
  }

  private fun decode(bytes: ByteArray, count: Int = 0) =
    CompilerStabilityMetadata.decode(bytes, "evidence/Example", count)

  private fun assertUnsupported(bytes: ByteArray, count: Int = 0) =
    assertEquals(CompilerStabilityMetadata.Result.Unsupported, decode(bytes, count))

  @Suppress("LongParameterList")
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
