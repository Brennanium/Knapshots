package dev.knapshots.testing.compiler

import dev.knapshots.testing.snapshot.compareSnapshot
import dev.knapshots.testing.snapshot.textSnapshotting

/** A named Kotlin source file supplied to a compiler integration. */
public data class CompilerSource(
  val fileName: String,
  val text: String,
)

/** A compiler invocation assembled from an assertion's expectations. */
public data class CompilerRequest(
  val sources: List<CompilerSource>,
  val compilerVersion: String,
  val captures: Set<CompilerSnapshotKind>,
)

/** A compiler diagnostic captured by an integration. */
public data class CompilerDiagnostic(
  val severity: CompilerDiagnosticSeverity,
  val message: String,
  val location: String? = null,
  /** One-based column when supplied by the compiler. */
  val column: Int? = null,
)

public enum class CompilerDiagnosticSeverity { ERROR, WARNING, INFO }

/** The result of one compiler invocation, including only the captures that were available. */
public data class CompilerResult(
  val succeeded: Boolean,
  val diagnostics: List<CompilerDiagnostic> = emptyList(),
  val captures: Map<CompilerSnapshotKind, String> = emptyMap(),
  /** Compiled JVM class files keyed by their relative resource paths. */
  val classFiles: Map<String, ByteArray> = emptyMap(),
) {
  /** Loads compiled classes and exposes their bytecode as classpath resources. */
  public fun classLoader(parent: ClassLoader = Thread.currentThread().contextClassLoader): ClassLoader {
    val files = classFiles.mapValues { it.value.copyOf() }
    return object : ClassLoader(parent) {
      override fun findClass(name: String): Class<*> {
        val bytes = files[name.replace('.', '/') + ".class"] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
      }
      override fun getResourceAsStream(name: String): java.io.InputStream? =
        files[name]?.inputStream() ?: super.getResourceAsStream(name)
    }
  }
}

/** Implemented by a compiler backend; Knapshots does not prescribe a compiler API. */
public fun interface CompilerHost {
  public fun compile(request: CompilerRequest): CompilerResult
}

/**
 * Compiles one source file and checks its result.
 *
 * The expectation block must state whether compilation succeeds or fails. That makes a test for
 * an expected compiler error fail when the source unexpectedly compiles successfully.
 */
public fun assertCompiler(
  source: String,
  compilerVersion: String,
  compiler: CompilerHost,
  fileName: String = "Main.kt",
  block: CompilerExpectations.() -> Unit,
) {
  assertCompiler(
    sources = listOf(CompilerSource(fileName, source)),
    compilerVersion = compilerVersion,
    compiler = compiler,
    block = block,
  )
}

/** Compiles multiple source files and checks one unified compiler result. */
public fun assertCompiler(
  sources: List<CompilerSource>,
  compilerVersion: String,
  compiler: CompilerHost,
  block: CompilerExpectations.() -> Unit,
) {
  require(sources.isNotEmpty()) { "At least one compiler source is required." }
  require(compilerVersion.isNotBlank()) { "A compiler version is required." }
  sources.forEach { source ->
    require(source.fileName.isNotBlank()) { "A compiler source file name must not be blank." }
  }

  val expectations = CompilerExpectations().apply(block)
  val result = compiler.compile(
    CompilerRequest(
      sources = sources.map { source -> source.copy(text = source.text.trimIndent()) },
      compilerVersion = compilerVersion,
      captures = expectations.snapshotKinds(),
    ),
  )
  expectations.verify(result, compilerVersion, sources.first().fileName)
}

/** Configures the result expected from [assertCompiler]. */
public class CompilerExpectations internal constructor() {
  private var expectedSuccess: Boolean? = null
  private val expectedDiagnostics = mutableListOf<ExpectedCompilerDiagnostic>()
  private val expectedSnapshots = linkedMapOf<CompilerSnapshotKind, SnapshotExpectation>()

  /** Expects the compiler to complete successfully. */
  public fun succeeds() {
    expectSuccess(true)
  }

  /** Expects compilation to fail. */
  public fun fails() {
    expectSuccess(false)
  }

  /** Checks individual diagnostics. Unmatched error diagnostics fail by default. */
  public fun diagnostics(block: CompilerDiagnosticExpectations.() -> Unit) {
    CompilerDiagnosticExpectations(expectedDiagnostics).apply(block)
  }

  /** Expects one named compiler capture, with optional compiler-version-specific output. */
  public fun snapshot(kind: CompilerSnapshotKind, expected: SnapshotExpectation) {
    check(expectedSnapshots.put(kind, expected) == null) {
      "A ${kind.name} compiler snapshot was already requested."
    }
  }

  /** Convenience for an exact, version-independent capture. */
  public fun snapshot(kind: CompilerSnapshotKind, expected: String) {
    snapshot(kind, SnapshotExpectation(expected))
  }

  /** Convenience for an FIR capture. */
  public fun fir(expected: SnapshotExpectation) {
    snapshot(CompilerSnapshotKind.FIR, expected)
  }

  /** Convenience for an exact, version-independent FIR capture. */
  public fun fir(expected: String) {
    fir(SnapshotExpectation(expected))
  }

  /** Convenience for an IR capture. */
  public fun ir(expected: SnapshotExpectation) {
    snapshot(CompilerSnapshotKind.IR, expected)
  }

  /** Convenience for an exact, version-independent IR capture. */
  public fun ir(expected: String) {
    ir(SnapshotExpectation(expected))
  }

  /** Convenience for a JVM bytecode capture supplied by the compiler host. */
  public fun bytecode(expected: SnapshotExpectation) {
    snapshot(CompilerSnapshotKind.BYTECODE, expected)
  }

  /** Convenience for an exact, version-independent JVM bytecode capture. */
  public fun bytecode(expected: String) {
    bytecode(SnapshotExpectation(expected))
  }

  internal fun snapshotKinds(): Set<CompilerSnapshotKind> = expectedSnapshots.keys

  internal fun verify(result: CompilerResult, compilerVersion: String, primaryFile: String) {
    val failures = mutableListOf<String>()
    when (expectedSuccess) {
      true -> if (!result.succeeded) failures += "Expected compilation to succeed, but it failed."
      false -> if (result.succeeded) failures += "Expected compilation to fail, but it succeeded."
      null -> failures += "Specify whether compilation succeeds or fails using succeeds() or fails()."
    }
    verifyDiagnostics(result.diagnostics, failures, primaryFile)
    expectedSnapshots.forEach { (kind, expected) ->
      val actual = result.captures[kind]
      if (actual == null) {
        failures += "The compiler did not provide the requested ${kind.name} snapshot."
      } else {
        val difference = compareSnapshot(
          actual.trimEnd('\n', '\r'),
          expected.forVersion(compilerVersion).trimIndent().trimEnd('\n', '\r'),
          textSnapshotting,
        )
        if (difference != null) {
          failures += "${kind.name} snapshot for Kotlin $compilerVersion differs:\n" +
            "Expected:\n${difference.expected}\nActual:\n${difference.actual}"
        }
      }
    }
    if (failures.isNotEmpty()) {
      throw AssertionError(failures.joinToString("\n", prefix = "Compiler assertions failed:\n"))
    }
  }

  private fun expectSuccess(value: Boolean) {
    check(expectedSuccess == null) { "Compilation outcome can only be specified once." }
    expectedSuccess = value
  }

  private fun verifyDiagnostics(actual: List<CompilerDiagnostic>, failures: MutableList<String>, primaryFile: String) {
    val owner = IntArray(actual.size) { -1 }
    fun assign(expectedIndex: Int, visited: BooleanArray): Boolean {
      val expected = expectedDiagnostics[expectedIndex]
      for (index in actual.indices) {
        val diagnostic = actual[index]
        if (visited[index] || diagnostic.severity != expected.severity ||
          !diagnostic.message.contains(expected.messageContains) ||
          (expected.location != null && expected.location != diagnostic.location) ||
          (expected.line != null && diagnostic.location != "$primaryFile:${expected.line}")) continue
        visited[index] = true
        if (owner[index] == -1 || assign(owner[index], visited)) {
          owner[index] = expectedIndex
          return true
        }
      }
      return false
    }

    expectedDiagnostics.forEachIndexed { index, expected ->
      if (!assign(index, BooleanArray(actual.size))) {
        failures += "Missing ${expected.severity} diagnostic containing '${expected.messageContains}'" +
          (expected.location?.let { " at $it" } ?: expected.line?.let { " on line $it of $primaryFile" } ?: "")
      }
    }
    actual.filterIndexed { index, diagnostic ->
      owner[index] == -1 && diagnostic.severity == CompilerDiagnosticSeverity.ERROR
    }.forEach { diagnostic ->
      failures += "Unexpected ERROR${diagnostic.location?.let { " at $it" } ?: ""}: ${diagnostic.message}"
    }
  }
}

internal data class ExpectedCompilerDiagnostic(
  val severity: CompilerDiagnosticSeverity,
  val messageContains: String,
  val location: String?,
  val line: Int?,
)

/** Builder for expected compiler diagnostics. */
public class CompilerDiagnosticExpectations internal constructor(
  private val expected: MutableList<ExpectedCompilerDiagnostic>,
) {
  public fun error(messageContains: String, location: String? = null, line: Int? = null) {
    expect(CompilerDiagnosticSeverity.ERROR, messageContains, location, line)
  }

  public fun warning(messageContains: String, location: String? = null, line: Int? = null) {
    expect(CompilerDiagnosticSeverity.WARNING, messageContains, location, line)
  }

  private fun expect(severity: CompilerDiagnosticSeverity, messageContains: String, location: String?, line: Int?) {
    require(line == null || line > 0) { "Diagnostic line must be positive." }
    require(messageContains.isNotBlank()) { "Expected diagnostic message must not be blank." }
    expected += ExpectedCompilerDiagnostic(severity, messageContains, location, line)
  }
}

/** A source location to update when recording inline compiler snapshots. */
public data class InlineSnapshotLocation(val file: java.nio.file.Path, val line: Int)

/**
 * Compiles a successful snippet and compares or records compiler output. Recording requires
 * a discoverable test source or an explicit source location; edits during compilation are detected.
 */
public fun assertCompiler(
  source: String,
  compilerVersion: String,
  compiler: CompilerHost,
  fir: SnapshotExpectation? = null,
  ir: SnapshotExpectation? = null,
  record: SnapshotRecording = SnapshotRecording.NONE,
  recordVersion: Boolean = false,
  location: InlineSnapshotLocation? = null,
  bytecode: SnapshotExpectation? = null,
  diagnostics: SnapshotExpectation? = null,
  additionalSources: List<CompilerSource> = emptyList(),
) {
  val expected = buildMap {
    fir?.let { put(CompilerSnapshotKind.FIR, it) }
    ir?.let { put(CompilerSnapshotKind.IR, it) }
    bytecode?.let { put(CompilerSnapshotKind.BYTECODE, it) }
    if (isEmpty() && diagnostics == null) {
      put(CompilerSnapshotKind.FIR, snapshot())
      put(CompilerSnapshotKind.IR, snapshot())
    }
  }
  val recordingLocation = if (record != SnapshotRecording.NONE) location ?: discoverInlineSnapshotLocation() else null
  val original = recordingLocation?.let { java.nio.file.Files.readString(it.file) }
  val sources = (listOf(CompilerSource("Main.kt", source)) + additionalSources).map { it.copy(text = it.text.trimIndent()) }
  require(sources.map { it.fileName }.distinct().size == sources.size) { "Source file names must be unique." }
  val result = compiler.compile(CompilerRequest(sources, compilerVersion, expected.keys))
  if (diagnostics == null || expected.isNotEmpty()) {
    check(result.succeeded && result.diagnostics.none { it.severity == CompilerDiagnosticSeverity.ERROR }) {
      "Cannot record or compare compiler captures: compilation failed. ${result.diagnostics}"
    }
  }
  if (!result.succeeded && result.diagnostics.none { it.severity == CompilerDiagnosticSeverity.ERROR }) {
    error("Compilation failed without an error diagnostic.")
  }
  val diagnosticOutput = diagnostics?.let { renderCompilerDiagnostics(sources, result.diagnostics) }
  val captures = expected.keys.associateWith { kind ->
    result.captures[kind] ?: error("The compiler did not provide the requested ${kind.name} snapshot.")
  }
  val updates = expected.filter { (kind, expectation) ->
    shouldRecordSnapshot(expectation, captures.getValue(kind), compilerVersion, record, recordVersion)
  }
  val updateDiagnostics = diagnostics != null && shouldRecordSnapshot(diagnostics, checkNotNull(diagnosticOutput), compilerVersion, record, recordVersion)
  if (updates.isNotEmpty() || updateDiagnostics) {
    val target = checkNotNull(recordingLocation)
    val names = mapOf(CompilerSnapshotKind.FIR to "fir", CompilerSnapshotKind.IR to "ir", CompilerSnapshotKind.BYTECODE to "bytecode")
    val values = captures.filterKeys { it in updates }.mapKeys { names.getValue(it.key) }.toMutableMap()
    if (updateDiagnostics) values["diagnostics"] = checkNotNull(diagnosticOutput)
    try {
      recordInlineSnapshots(target, "assertCompiler", values, compilerVersion, recordVersion, original)
    } catch (error: Exception) {
      throw AssertionError("Could not record inline snapshots at ${target.file}:${target.line}: ${error.message}", error)
    }
    throw AssertionError("Recorded inline snapshots at ${target.file}:${target.line}; review and rerun the test.")
  }
  if (diagnostics != null) {
    check(!diagnostics.missing) { "Missing diagnostic snapshot; enable recording to insert it." }
    val difference = compareSnapshot(checkNotNull(diagnosticOutput).trimEnd('\n', '\r'),
      diagnostics.forVersion(compilerVersion).trimIndent().trimEnd('\n', '\r'), textSnapshotting)
    if (difference != null) throw AssertionError("Diagnostic snapshot differs:\nExpected:\n${difference.expected}\nActual:\n${difference.actual}")
  }
  expected.forEach { (kind, expectation) ->
    check(!expectation.missing) { "Missing ${kind.name} snapshot; enable recording to insert it." }
  }
  if (expected.isNotEmpty()) assertCompilerSnapshots(source, compilerVersion, expected) { _, _, _ -> captures }
}

public fun discoverInlineSnapshotLocation(): InlineSnapshotLocation {
  val root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath()
  val sourceDirectory = root.resolve("src")
  val roots = if (java.nio.file.Files.isDirectory(sourceDirectory)) {
    java.nio.file.Files.list(sourceDirectory).use { directories ->
      directories.filter { java.nio.file.Files.isDirectory(it) && (it.fileName.toString() == "test" || it.fileName.toString().endsWith("Test")) }
        .flatMap { directory -> java.util.stream.Stream.of(directory.resolve("kotlin"), directory.resolve("java")) }
        .toList()
    }
  } else emptyList()
  return discoverInlineSnapshotLocation(roots)
}

/** Locate a recording call using explicit source roots, such as a subproject's test sources. */
public fun discoverInlineSnapshotLocation(sourceRoots: List<java.nio.file.Path>): InlineSnapshotLocation {
  val caller = Throwable().stackTrace.firstNotNullOfOrNull { frame ->
    if (frame.lineNumber <= 0 || frame.fileName == null || frame.className.startsWith("dev.knapshots.testing.compiler.CompilerAssertions")) return@firstNotNullOfOrNull null
    val packagePath = frame.className.substringBeforeLast('.', "").replace('.', '/')
    sourceRoots.map { it.resolve(packagePath).resolve(frame.fileName) }
      .firstOrNull(java.nio.file.Files::isRegularFile)?.let { InlineSnapshotLocation(it, frame.lineNumber) }
  }
  return requireNotNull(caller) { "Cannot locate the test source for inline recording; provide location explicitly." }
}
