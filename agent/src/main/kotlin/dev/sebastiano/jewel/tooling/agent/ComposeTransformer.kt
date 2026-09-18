package dev.sebastiano.jewel.tooling.agent

import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode

internal class ComposeTransformer(
  private val runtime: (ClassLoader?, Module?) -> Int,
  private val unavailable: () -> Unit,
  private val available: (Int) -> Unit = {},
) : ClassFileTransformer {
  override fun transform(
    module: Module?,
    loader: ClassLoader?,
    className: String?,
    classBeingRedefined: Class<*>?,
    protectionDomain: ProtectionDomain?,
    bytes: ByteArray,
  ): ByteArray? {
    if (className != COMPOSER || classBeingRedefined != null) return null
    return try {
      val id = runtime(loader, module)
      if (id <= 0) null else transformRuntime(bytes, id)
    } catch (_: RuntimeException) {
      unavailable()
      null
    } catch (_: LinkageError) {
      unavailable()
      null
    }
  }

  private fun transformRuntime(bytes: ByteArray, id: Int): ByteArray {
    val node = ClassNode(Opcodes.ASM9)
    ClassReader(bytes).accept(node, 0)
    checkAbi(node)
    patch(node, id)
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    return writer.toByteArray().also { available(id) }
  }

  private fun checkAbi(node: ClassNode) {
    require(node.name == COMPOSER)
    for ((name, descriptor) in REQUIRED) {
      val method = node.methods.single { it.name == name && it.desc == descriptor }
      require(
        method.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC) ==
          (Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
      )
      require(method.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) == 0)
    }
    for (method in
      node.methods.filter { it.name.startsWith("traceEvent") || it.name.startsWith("isTrace") }) {
      if (REQUIRED[method.name] == method.desc) continue
      require(method.name == "traceEventStart" && method.desc == "(ILjava/lang/String;)V")
      checkLegacyStart(method)
    }
  }

  private fun checkLegacyStart(method: MethodNode) {
    val calls = method.instructions.toArray().filterIsInstance<MethodInsnNode>()
    require(
      calls.count { it.owner == COMPOSER && it.name == "traceEventStart" && it.desc == START } == 1
    )
    require(
      calls.all {
        (it.owner == COMPOSER && it.name == "traceEventStart" && it.desc == START) ||
          (it.owner == "kotlin/jvm/internal/Intrinsics" && it.name == "checkNotNullParameter")
      }
    )
    val allowed =
      setOf(
        Opcodes.ILOAD,
        Opcodes.ALOAD,
        Opcodes.ICONST_M1,
        Opcodes.LDC,
        Opcodes.INVOKESTATIC,
        Opcodes.RETURN,
      )
    require(method.instructions.toArray().all { it.opcode == -1 || it.opcode in allowed })
  }

  private fun patch(node: ClassNode, runtime: Int) {
    val gate = node.methods.single { it.name == "isTraceInProgress" && it.desc == "()Z" }
    for (instruction in gate.instructions.toArray()) {
      if (instruction.opcode == Opcodes.IRETURN)
        gate.instructions.insertBefore(
          instruction,
          InsnList().apply {
            add(LdcInsnNode(runtime))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "gate", "(ZI)Z", false))
          },
        )
    }
    val start = node.methods.single { it.name == "traceEventStart" && it.desc == START }
    prepend(
      start,
      InsnList().apply {
        add(LdcInsnNode(runtime))
        for (index in 0..2) add(VarInsnNode(Opcodes.ILOAD, index))
        add(VarInsnNode(Opcodes.ALOAD, INFO_ARGUMENT))
        add(
          MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "start", "(IIIILjava/lang/String;)Z", false)
        )
      },
    )
    val end = node.methods.single { it.name == "traceEventEnd" && it.desc == "()V" }
    prepend(
      end,
      InsnList().apply {
        add(LdcInsnNode(runtime))
        add(MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "end", "(I)Z", false))
      },
    )
  }

  private fun prepend(method: MethodNode, prefix: InsnList) {
    val original = LabelNode()
    prefix.add(JumpInsnNode(Opcodes.IFNE, original))
    prefix.add(InsnNode(Opcodes.RETURN))
    prefix.add(original)
    prefix.add(FrameNode(Opcodes.F_SAME, 0, null, 0, null))
    method.instructions.insert(prefix)
  }

  companion object {
    const val COMPOSER = "androidx/compose/runtime/ComposerKt"
    private const val INFO_ARGUMENT = 3
    private const val BRIDGE = "dev/sebastiano/jewel/tooling/bridge/TraceBridge"
    private const val START = "(IIILjava/lang/String;)V"
    private val REQUIRED =
      mapOf("isTraceInProgress" to "()Z", "traceEventStart" to START, "traceEventEnd" to "()V")
  }
}
