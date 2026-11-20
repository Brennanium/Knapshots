package dev.knapshots.testing.bytecode

import dev.knapshots.testing.compiler.CompilerHost
import dev.knapshots.testing.compiler.CompilerRequest
import dev.knapshots.testing.compiler.CompilerResult
import dev.knapshots.testing.compiler.CompilerSnapshotKind
import dev.knapshots.testing.snapshot.Snapshotting
import dev.knapshots.testing.snapshot.textSnapshotting
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.util.TraceClassVisitor
import java.io.PrintWriter
import java.io.StringWriter

/** A method invocation; owner uses the JVM internal class name. */
public data class MethodCall(val owner: String, val name: String)
public data class TypeInstruction(val opcode: Int, val type: String)
public data class FieldAccess(val opcode: Int, val owner: String, val name: String)

public data class MethodBytecode(
  val typeInstructions: Set<TypeInstruction>,
  val methodCalls: Set<MethodCall>,
  val fieldAccesses: Set<FieldAccess>,
)

/** A structural view of JVM instructions; debug information and stack frames are ignored. */
public data class ClassBytecode(
  val superName: String?,
  val methods: Map<Pair<String, String>, MethodBytecode>,
  val references: Set<String>,
) {
  public fun method(name: String, descriptor: String): MethodBytecode =
    checkNotNull(methods[name to descriptor]) { "Expected bytecode method $name$descriptor." }

  public companion object {
    public fun read(bytes: ByteArray): ClassBytecode {
      var parent: String? = null
      val methods = mutableMapOf<Pair<String, String>, MethodBytecode>()
      val references = mutableSetOf<String>()
      ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
          parent = superName
          superName?.let(references::add)
          interfaces?.forEach(references::add)
        }
        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
          val types = mutableSetOf<TypeInstruction>()
          val calls = mutableSetOf<MethodCall>()
          val fields = mutableSetOf<FieldAccess>()
          methods[name to descriptor] = MethodBytecode(types, calls, fields)
          return object : MethodVisitor(Opcodes.ASM9) {
            override fun visitTypeInsn(opcode: Int, type: String) { references += type; types += TypeInstruction(opcode, type) }
            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
              references += owner; calls += MethodCall(owner, name)
            }
            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
              references += owner; fields += FieldAccess(opcode, owner, name)
            }
          }
        }
      }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
      return ClassBytecode(parent, methods, references)
    }
  }
}

/** Render a JVM class without line numbers or stack frames for text snapshot comparison. */
public fun renderJvmBytecode(bytes: ByteArray): String {
  val output = StringWriter()
  ClassReader(bytes).accept(TraceClassVisitor(PrintWriter(output)), ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
  return output.toString().replace("\r\n", "\n").trimEnd()
}

/** A snapshot strategy for individual JVM class files. */
public val jvmBytecodeSnapshotting: Snapshotting<ByteArray, String> = textSnapshotting.pullback(::renderJvmBytecode)

/** Default capture name for a JVM class-file snapshot. */
public val JVM_BYTECODE: CompilerSnapshotKind = CompilerSnapshotKind.BYTECODE

/** Capture a class file as a named compiler snapshot, alongside FIR and IR captures. */
public fun CompilerHost.withJvmBytecodeSnapshot(
  className: String,
  kind: CompilerSnapshotKind = JVM_BYTECODE,
): CompilerHost {
  require(className.split('.').all { part -> part.isNotEmpty() && part.none { it == '/' || it == '\\' } }) { "A JVM class name is required." }
  require(kind != CompilerSnapshotKind.FIR && kind != CompilerSnapshotKind.IR) { "Bytecode must use a distinct snapshot kind." }
  val underlying = this
  return object : CompilerHost {
    override fun defaultSnapshotKinds(): Set<CompilerSnapshotKind> = underlying.defaultSnapshotKinds()

    override fun compile(request: CompilerRequest): CompilerResult {
      if (kind !in request.captures) return underlying.compile(request)
      val result = underlying.compile(request.copy(captures = request.captures - kind))
      val resource = className.replace('.', '/') + ".class"
      val bytes = result.classFiles[resource]
      return if (bytes == null) result else result.copy(captures = result.captures + (kind to renderJvmBytecode(bytes)))
    }
  }
}
