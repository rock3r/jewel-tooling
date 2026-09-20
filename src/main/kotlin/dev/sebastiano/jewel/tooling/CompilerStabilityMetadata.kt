package dev.sebastiano.jewel.tooling

import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.signature.SignatureReader
import org.jetbrains.org.objectweb.asm.signature.SignatureVisitor
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode
import org.jetbrains.org.objectweb.asm.tree.IntInsnNode
import org.jetbrains.org.objectweb.asm.tree.LdcInsnNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode

/** Decodes supported Kotlin 2.3 and 2.4 stability metadata in Java 8 through 25 class files. */
internal object CompilerStabilityMetadata {
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_PARAMETERS = 30
    private val SUPPORTED_METADATA_VERSIONS = listOf(intArrayOf(2, 3, 0), intArrayOf(2, 4, 0))
    private const val MIN_CLASS_VERSION = 52
    private const val MAX_CLASS_VERSION = 69
    private const val MAX_SIGNATURE_LENGTH = 4096
    private const val MAX_SIGNATURE_NESTING = 64
    private const val UNSTABLE_VALUE = 8
    private const val LITERAL_INITIALIZER_SIZE = 3

    sealed interface Result {
        data class Proven(val unstableBase: Boolean, val argumentMask: Int) : Result

        data object Unsupported : Result
    }

    fun decode(bytes: ByteArray, internalName: String, typeParameters: Int): Result {
        if (bytes.size > MAX_BYTES || typeParameters !in 0..MAX_PARAMETERS)
            return Result.Unsupported
        return try {
            val visitor = MetadataVisitor(internalName, typeParameters)
            ClassReader(bytes).accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            visitor.result()
        } catch (_: UnsupportedShape) {
            Result.Unsupported
        } catch (_: IllegalArgumentException) {
            Result.Unsupported
        } catch (_: IndexOutOfBoundsException) {
            Result.Unsupported
        } catch (_: NegativeArraySizeException) {
            Result.Unsupported
        }
    }

    private class UnsupportedShape : RuntimeException(null, null, false, false)

    private fun requireShape(condition: Boolean) {
        if (!condition) throw UnsupportedShape()
    }

    private class MetadataVisitor(
        private val expectedName: String,
        private val expectedCount: Int,
    ) : ClassVisitor(Opcodes.ASM9) {
        private var metadataCount = 0
        private var metadataVersion: IntArray? = null
        private var metadataKind: Int? = null
        private var inferredCount = 0
        private var mask: Int? = null
        private var fieldCount = 0
        private var constant: Int? = null
        private var initializer: MethodNode? = null

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            requireShape(version in MIN_CLASS_VERSION..MAX_CLASS_VERSION && name == expectedName)
            requireShape(access and Opcodes.ACC_FINAL != 0)
            requireShape(
                access and
                    (Opcodes.ACC_INTERFACE or
                        Opcodes.ACC_ANNOTATION or
                        Opcodes.ACC_ENUM or
                        Opcodes.ACC_SYNTHETIC) == 0
            )
            var count = 0
            if (signature != null) {
                requireShape(
                    signature.length <= MAX_SIGNATURE_LENGTH &&
                        signature.count { it == '[' || it == '<' } <= MAX_SIGNATURE_NESTING
                )
                SignatureReader(signature)
                    .accept(
                        object : SignatureVisitor(Opcodes.ASM9) {
                            override fun visitFormalTypeParameter(name: String) {
                                count++
                            }
                        }
                    )
            }
            requireShape(count == expectedCount)
        }

        override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
            throw UnsupportedShape()
        }

        override fun visitInnerClass(
            name: String,
            outerName: String?,
            innerName: String?,
            access: Int,
        ) {
            if (name == expectedName)
                requireShape(
                    outerName != null && innerName != null && access and Opcodes.ACC_STATIC != 0
                )
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
            when (descriptor) {
                "Lkotlin/Metadata;" -> {
                    requireShape(++metadataCount == 1 && visible)
                    object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(name: String?, value: Any?) {
                            when (name) {
                                "mv" -> {
                                    requireShape(metadataVersion == null && value is IntArray)
                                    metadataVersion = value as IntArray
                                }
                                "k" -> {
                                    requireShape(metadataKind == null && value is Int)
                                    metadataKind = value as Int
                                }
                            }
                        }
                    }
                }
                "Landroidx/compose/runtime/internal/StabilityInferred;" -> {
                    requireShape(++inferredCount == 1 && !visible)
                    object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(name: String?, value: Any?) {
                            requireShape(name == "parameters" && mask == null && value is Int)
                            mask = value as Int
                        }

                        override fun visitArray(name: String?): AnnotationVisitor =
                            throw UnsupportedShape()

                        override fun visitAnnotation(
                            name: String?,
                            descriptor: String,
                        ): AnnotationVisitor = throw UnsupportedShape()

                        override fun visitEnum(name: String?, descriptor: String, value: String) {
                            throw UnsupportedShape()
                        }
                    }
                }
                else -> null
            }

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor? {
            if (!name.startsWith("$" + "stable")) return null
            val required = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
            requireShape(
                name == "$" + "stable" &&
                    ++fieldCount == 1 &&
                    descriptor == "I" &&
                    access and required == required
            )
            requireShape(
                access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED or Opcodes.ACC_VOLATILE) ==
                    0
            )
            requireShape(value == null || value == 0 || value == UNSTABLE_VALUE)
            constant = value as Int?
            return null
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodNode? {
            if (name != "<clinit>") return null
            requireShape(
                initializer == null && descriptor == "()V" && access and Opcodes.ACC_STATIC != 0
            )
            requireShape(access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) == 0)
            return MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions).also {
                initializer = it
            }
        }

        fun result(): Result {
            requireShape(
                metadataCount == 1 &&
                    metadataKind == 1 &&
                    SUPPORTED_METADATA_VERSIONS.any { metadataVersion?.contentEquals(it) == true }
            )
            requireShape(inferredCount == 1 && fieldCount == 1)
            val m = mask ?: throw UnsupportedShape()
            val flag = 1 shl expectedCount
            val arguments = flag - 1
            requireShape(m and (flag or arguments).inv() == 0)
            requireShape(m and flag == 0 || m and arguments == 0)
            val base = baseValue()
            requireShape(!(m and flag != 0 && base == UNSTABLE_VALUE))
            requireShape(base != 0 || m != 0)
            return Result.Proven(base == UNSTABLE_VALUE, m and arguments)
        }

        private fun literalValue(instruction: AbstractInsnNode): Int? =
            when (instruction) {
                is IntInsnNode ->
                    if (
                        instruction.opcode == Opcodes.BIPUSH || instruction.opcode == Opcodes.SIPUSH
                    )
                        instruction.operand
                    else null
                is LdcInsnNode -> instruction.cst as? Int
                else -> if (instruction.opcode == Opcodes.ICONST_0) 0 else null
            }

        private fun baseValue(): Int {
            val method = initializer ?: return constant ?: 0
            requireShape(method.tryCatchBlocks.isEmpty())
            val instructions = method.instructions.toArray().filter { it.opcode >= 0 }
            return if (instructions.size == 1 && instructions.single().opcode == Opcodes.RETURN)
                constant ?: 0
            else literalInitializer(instructions)
        }

        private fun literalInitializer(instructions: List<AbstractInsnNode>): Int {
            requireShape(
                instructions.size == LITERAL_INITIALIZER_SIZE &&
                    instructions.last().opcode == Opcodes.RETURN
            )
            val value = literalValue(instructions.first())
            requireShape(value == 0 || value == UNSTABLE_VALUE)
            val write = instructions[1] as? FieldInsnNode ?: throw UnsupportedShape()
            requireShape(
                write.opcode == Opcodes.PUTSTATIC &&
                    write.owner == expectedName &&
                    write.name == "$" + "stable" &&
                    write.desc == "I"
            )
            requireShape(constant == null || constant == value)
            return checkNotNull(value)
        }
    }
}
