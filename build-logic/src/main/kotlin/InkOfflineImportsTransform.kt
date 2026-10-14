import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * Lets ML Kit's handwriting model downloader install a model from files
 * imported off another device (#666). See `InkPackImports` in
 * :core:intelligence for the whole story; this is the half that edits the
 * library, three call sites in its shaded package:
 *
 *  - every `OkHttpClient.Builder.build()` goes through `InkPackImports.build`,
 *    which adds the interceptor that serves imported packs off disk;
 *  - every `ContextCompat.checkSelfPermission` goes through its namesake,
 *    which grants the internet permission while an import installs;
 *  - the method that decides a download has no network to run on (found by
 *    its log line, not its obfuscated name) first asks
 *    `InkPackImports.connectivityWaived()`, and says yes when it is.
 *
 * Keyed on the package, the called APIs and a log string, never on the
 * obfuscated class names, so a library update either still matches or
 * matches nothing. InkPackHookTest notices the second.
 */
abstract class InkOfflineImportsTransform : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className.startsWith(PACKAGE)

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
                    override fun visitEnd() {
                        super.visitEnd()
                        rewrite(this)
                        accept(next)
                    }
                }
            }
        }

    private fun rewrite(method: MethodNode) {
        var connectivityCheck = false
        for (insn in method.instructions.toArray()) {
            when {
                insn is LdcInsnNode && insn.cst == NO_NETWORK_LOG -> connectivityCheck = true
                insn is MethodInsnNode && insn.opcode == Opcodes.INVOKEVIRTUAL &&
                    insn.owner == OKHTTP_BUILDER && insn.name == "build" && insn.desc == "()L$OKHTTP_CLIENT;" ->
                    method.instructions.set(
                        insn,
                        MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "build", "(L$OKHTTP_BUILDER;)L$OKHTTP_CLIENT;", false),
                    )
                insn is MethodInsnNode && insn.opcode == Opcodes.INVOKESTATIC &&
                    insn.owner == CONTEXT_COMPAT && insn.name == "checkSelfPermission" ->
                    method.instructions.set(insn, MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, insn.name, insn.desc, false))
            }
        }
        if (connectivityCheck && method.desc.endsWith(")Z")) {
            val proceed = LabelNode()
            val prefix = InsnList().apply {
                add(MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "connectivityWaived", "()Z", false))
                add(JumpInsnNode(Opcodes.IFEQ, proceed))
                add(InsnNode(Opcodes.ICONST_1))
                add(InsnNode(Opcodes.IRETURN))
                add(proceed)
            }
            method.instructions.insert(prefix)
        }
    }

    private companion object {
        const val PACKAGE = "com.google.android.gms.internal.mlkit_vision_digital_ink."
        const val HOOK = "com/wasimaster/wmkeyboard/core/handwriting/InkPackImports"
        const val OKHTTP_BUILDER = "okhttp3/OkHttpClient\$Builder"
        const val OKHTTP_CLIENT = "okhttp3/OkHttpClient"
        const val CONTEXT_COMPAT = "androidx/core/content/ContextCompat"
        const val NO_NETWORK_LOG = "No current network, connectivity cannot be satisfied."
    }
}
