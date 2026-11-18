package dev.knapshots.testing.compiler

import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.config.Services

/**
 * Runs the bundled JVM compiler with an explicit classpath and compiler-plugin arguments.
 *
 * [captureArguments] maps requested snapshot kinds to output files and supplies the plugin
 * arguments that write them. Only files produced by the plugin are returned as captures.
 * The requested compiler version must match the embedded compiler version.
 */
public class EmbeddedCompilerHost(
  private val classpath: List<Path> = emptyList(),
  private val compilerArguments: List<String> = emptyList(),
  private val captureArguments: ((Map<CompilerSnapshotKind, Path>) -> List<String>)? = null,
) : CompilerHost {
  override fun compile(request: CompilerRequest): CompilerResult {
    require(request.compilerVersion == KotlinCompilerVersion.VERSION) {
      "Requested Kotlin ${request.compilerVersion}, but the embedded compiler is ${KotlinCompilerVersion.VERSION}."
    }
    require(request.captures.isEmpty() || captureArguments != null) { "Configure captureArguments to capture compiler snapshots." }
    val directory = Files.createTempDirectory("knapshots-compiler-")
    try {
      val sourceRoot = Files.createDirectories(directory.resolve("sources"))
      val sources = request.sources.map { source ->
        val relative = Path.of(source.fileName)
        require(!relative.isAbsolute && relative.none { it.toString() == ".." } && source.fileName.endsWith(".kt")) {
          "Invalid Kotlin source file name: ${source.fileName}"
        }
        val path = sourceRoot.resolve(relative).normalize()
        Files.createDirectories(path.parent)
        Files.writeString(path, source.text)
        path
      }
      val diagnostics = mutableListOf<CompilerDiagnostic>()
      val collector = object : MessageCollector {
        var errors = false
        override fun clear() { diagnostics.clear(); errors = false }
        override fun hasErrors(): Boolean = errors
        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
          val mapped = when {
            severity.isError -> CompilerDiagnosticSeverity.ERROR
            severity.isWarning -> CompilerDiagnosticSeverity.WARNING
            else -> return
          }
          if (severity.isError) errors = true
          val position = location?.let { loc ->
            val path = Path.of(loc.path)
            val display = if (path.startsWith(sourceRoot)) sourceRoot.relativize(path).toString() else loc.path
            if (loc.line > 0) "$display:${loc.line}" else display
          }
          diagnostics += CompilerDiagnostic(mapped, message, position)
        }
      }
      val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
      val effectiveClasspath = (classpath + listOf(stdlib)).distinct()
      val captureFiles = request.captures.associateWith { kind ->
        require(kind.name.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid snapshot kind: ${kind.name}" }
        directory.resolve("capture-${kind.name}.txt")
      }
      val arguments = listOf("-no-stdlib", "-no-reflect", "-d", directory.resolve("classes").toString()) +
        listOf("-classpath", effectiveClasspath.joinToString(java.io.File.pathSeparator)) +
        compilerArguments + (captureArguments?.invoke(captureFiles) ?: emptyList()) + sources.map(Path::toString)
      val compiler = K2JVMCompiler()
      val parsed = compiler.createArguments()
      compiler.parseArguments(arguments.toTypedArray(), parsed)
      val exit = compiler.exec(collector, Services.EMPTY, parsed)
      val captures = captureFiles.mapNotNull { (kind, path) ->
        if (Files.isRegularFile(path)) kind to Files.readString(path) else null
      }.toMap()
      val classesDirectory = directory.resolve("classes")
      val classes = if (Files.isDirectory(classesDirectory)) Files.walk(classesDirectory).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }.toList()
          .associate { classesDirectory.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it) }
      } else emptyMap()
      return CompilerResult(exit == ExitCode.OK, diagnostics, captures, classes)
    } finally {
      Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
  }
}
