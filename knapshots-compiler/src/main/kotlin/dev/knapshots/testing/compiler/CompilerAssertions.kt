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
  /** One-based, exclusive end position when the compiler supplies a range. */
  val endLine: Int? = null,
  val endColumn: Int? = null,
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
  /** FIR and IR captures available to an input-only assertion without additional configuration. */
  public fun defaultSnapshotKinds(): Set<CompilerSnapshotKind> = emptySet()
}

/** Set the FIR and IR captures an input-only assertion should request from this host. */
public fun CompilerHost.withDefaultSnapshotKinds(kinds: Set<CompilerSnapshotKind>): CompilerHost {
  val underlying = this
  val defaults = kinds.toSet()
  require(defaults.all { it == CompilerSnapshotKind.FIR || it == CompilerSnapshotKind.IR }) {
    "Only FIR and IR can be default captures; request bytecode or custom captures explicitly."
  }
  return object : CompilerHost {
    override fun defaultSnapshotKinds(): Set<CompilerSnapshotKind> = defaults
    override fun compile(request: CompilerRequest): CompilerResult = underlying.compile(request)
  }
}

/**
 * Compiles one source file and checks its result.
 *
 * Compilation outcome checks are optional; specify succeeds() or fails() for an outcome-only check.
 */
public fun assertCompiler(
  source: String,
  compilerVersion: String,
  compiler: CompilerHost,
  fileName: String = "Main.kt",
  record: SnapshotRecording = configuredRecording(),
  recordVersion: Boolean = configuredRecordVersion(),
  location: InlineSnapshotLocation? = null,
  block: CompilerExpectations.() -> Unit,
) {
  assertCompiler(
    sources = listOf(CompilerSource(fileName, source)),
    compilerVersion = compilerVersion,
    compiler = compiler,
    record = record,
    recordVersion = recordVersion,
    location = location,
    block = block,
  )
}

/** Compiles multiple source files and checks one unified compiler result. */
public fun assertCompiler(
  sources: List<CompilerSource>,
  compilerVersion: String,
  compiler: CompilerHost,
  record: SnapshotRecording = configuredRecording(),
  recordVersion: Boolean = configuredRecordVersion(),
  location: InlineSnapshotLocation? = null,
  block: CompilerExpectations.() -> Unit,
) {
  require(sources.isNotEmpty()) { "At least one compiler source is required." }
  require(compilerVersion.isNotBlank()) { "A compiler version is required." }
  sources.forEach { source ->
    require(source.fileName.isNotBlank()) { "A compiler source file name must not be blank." }
  }

  val expectations = CompilerExpectations(record, recordVersion).apply(block)
  val autoTarget = if (record != SnapshotRecording.NONE && expectations.shouldAutomaticallyCaptureDiagnostics()) location ?: discoverInlineSnapshotLocation() else null
  val autoOriginal = autoTarget?.let { java.nio.file.Files.readString(it.file) }
  val autoKinds = if (autoTarget != null) compiler.defaultSnapshotKinds().intersect(setOf(CompilerSnapshotKind.FIR, CompilerSnapshotKind.IR)) else emptySet()
  val result = compiler.compile(
    CompilerRequest(
      sources = sources.map { source -> source.copy(text = source.text.trimIndent()) },
      compilerVersion = compilerVersion,
      captures = expectations.snapshotKinds() + autoKinds,
    ),
  )
  expectations.verify(result, compilerVersion, sources.map { it.copy(text = it.text.trimIndent()) }, autoTarget, autoOriginal, autoKinds)
}

/** Configures the result expected from [assertCompiler]. */
public class CompilerExpectations internal constructor(
  private val recording: SnapshotRecording = SnapshotRecording.NONE,
  private val recordVersion: Boolean = false,
) {
  private var expectedSuccess: Boolean? = null
  private val expectedDiagnostics = mutableListOf<ExpectedCompilerDiagnostic>()
  private var hasDiagnosticSnapshot = false
  private var hasDiagnosticBlock = false
  private var diagnosticSnapshot: SnapshotExpectation? = null
  private var diagnosticLocation: InlineSnapshotLocation? = null
  private var diagnosticCall: String = "snapshot"
  private var originalTestSource: String? = null
  private val expectedSnapshots = linkedMapOf<CompilerSnapshotKind, SnapshotExpectation>()
  private val phaseUpdates = mutableMapOf<CompilerSnapshotKind, Triple<InlineSnapshotLocation, String, String>>()

  /** Expects the compiler to complete successfully. */
  public fun succeeds() {
    expectSuccess(true)
  }

  /** Expects compilation to fail. */
  public fun fails() {
    expectSuccess(false)
  }

  /** Record a diagnostic snapshot, expanding to a diagnostics block. */
  public fun diagnostics(location: InlineSnapshotLocation? = null) {
    diagnostics { snapshot(location = location) }
    diagnosticCall = "diagnostics"
  }

  /** Checks individual diagnostics. Unmatched error diagnostics fail by default. */
  public fun diagnostics(block: CompilerDiagnosticExpectations.() -> Unit) {
    hasDiagnosticBlock = true
    CompilerDiagnosticExpectations(expectedDiagnostics) { expected, location ->
      check(!hasDiagnosticSnapshot && expectedDiagnostics.isEmpty()) { "Diagnostic snapshots and individual expectations cannot be combined." }
      hasDiagnosticSnapshot = true
      diagnosticSnapshot = expected
      if (dev.knapshots.testing.snapshot.mayRecordSnapshot(recording, expected == null || expected.missing, recordVersion)) {
        diagnosticLocation = location ?: discoverInlineSnapshotLocation()
        originalTestSource = java.nio.file.Files.readString(checkNotNull(diagnosticLocation).file)
      }
    }.apply(block)
    check(!hasDiagnosticSnapshot || expectedDiagnostics.isEmpty()) { "Diagnostic snapshots and individual expectations cannot be combined." }
  }

  private fun phase(kind: CompilerSnapshotKind, expected: SnapshotExpectation, call: String, location: InlineSnapshotLocation?) {
    check(expectedSnapshots.put(kind, expected) == null) { "A ${kind.name} compiler snapshot was already requested." }
    if (dev.knapshots.testing.snapshot.mayRecordSnapshot(recording, expected.missing, recordVersion)) {
      val target = location ?: discoverInlineSnapshotLocation()
      phaseUpdates[kind] = Triple(target, call, java.nio.file.Files.readString(target.file))
    }
  }

  /** Expects one named compiler capture, with optional compiler-version-specific output. */
  public fun snapshot(kind: CompilerSnapshotKind, expected: SnapshotExpectation, location: InlineSnapshotLocation? = null) = phase(kind, expected, "snapshot", location)
  public fun snapshot(kind: CompilerSnapshotKind, expected: String, location: InlineSnapshotLocation? = null) = phase(kind, SnapshotExpectation(expected), "snapshot", location)

  /** Compare or record a FIR capture. */
  public fun fir(expected: SnapshotExpectation = dev.knapshots.testing.compiler.snapshot(), location: InlineSnapshotLocation? = null) = phase(CompilerSnapshotKind.FIR, expected, "fir", location)
  public fun fir(expected: String, location: InlineSnapshotLocation? = null) = phase(CompilerSnapshotKind.FIR, SnapshotExpectation(expected), "fir", location)

  /** Compare or record an IR capture. */
  public fun ir(expected: SnapshotExpectation = dev.knapshots.testing.compiler.snapshot(), location: InlineSnapshotLocation? = null) = phase(CompilerSnapshotKind.IR, expected, "ir", location)
  public fun ir(expected: String, location: InlineSnapshotLocation? = null) = phase(CompilerSnapshotKind.IR, SnapshotExpectation(expected), "ir", location)

  /** Compare or record a JVM bytecode capture supplied by the compiler host. */
  public fun bytecode(expected: SnapshotExpectation = dev.knapshots.testing.compiler.snapshot(), location: InlineSnapshotLocation? = null) = phase(CompilerSnapshotKind.BYTECODE, expected, "bytecode", location)
  public fun bytecode(expected: String, location: InlineSnapshotLocation? = null) = phase(CompilerSnapshotKind.BYTECODE, SnapshotExpectation(expected), "bytecode", location)

  internal fun snapshotKinds(): Set<CompilerSnapshotKind> = expectedSnapshots.keys
  internal fun shouldAutomaticallyCaptureDiagnostics(): Boolean = expectedSuccess == null && !hasDiagnosticBlock && expectedSnapshots.isEmpty()

  internal fun verify(result: CompilerResult, compilerVersion: String, sources: List<CompilerSource>, autoTarget: InlineSnapshotLocation? = null, autoOriginal: String? = null, autoKinds: Set<CompilerSnapshotKind> = emptySet()) {
    val failures = mutableListOf<String>()
    when (expectedSuccess) {
      true -> if (!result.succeeded) failures += "Expected compilation to succeed, but it failed."
      false -> if (result.succeeded) failures += "Expected compilation to fail, but it succeeded."
      null -> Unit
    }
    val inputOnly = shouldAutomaticallyCaptureDiagnostics()
    val autoDiagnostics = inputOnly && recording != SnapshotRecording.NONE
    if (inputOnly && recording == SnapshotRecording.NONE) failures += "Missing diagnostic snapshot; enable recording to insert it."
    if (!hasDiagnosticSnapshot && (hasDiagnosticBlock || expectedSuccess == true || (inputOnly && !autoDiagnostics))) verifyDiagnostics(result.diagnostics, failures, sources.first().fileName)
    val updates = mutableListOf<InlineSnapshotUpdate>()
    expectedSnapshots.forEach { (kind, expected) ->
      val actual = result.captures[kind]
      if (actual == null) {
        failures += "The compiler did not provide the requested ${kind.name} snapshot."
      } else if (shouldRecordSnapshot(expected, actual, compilerVersion, recording, recordVersion)) {
        val (target, call, original) = checkNotNull(phaseUpdates[kind])
        updates += InlineSnapshotUpdate(target, call, mapOf("expected" to actual), original)
      } else if (expected.missing) {
        failures += "Missing ${kind.name} snapshot; enable recording to insert it."
      } else {
        val difference = compareSnapshot(actual.trimEnd('\n', '\r'), expected.forVersion(compilerVersion).trimIndent().trimEnd('\n', '\r'), textSnapshotting)
        if (difference != null) failures += "${kind.name} snapshot for Kotlin $compilerVersion differs:\nExpected:\n${difference.expected}\nActual:\n${difference.actual}"
      }
    }
    if (hasDiagnosticSnapshot) {
      val actual = renderCompilerDiagnostics(sources, result.diagnostics)
      val expectation = diagnosticSnapshot ?: dev.knapshots.testing.compiler.snapshot()
      if (shouldRecordSnapshot(expectation, actual, compilerVersion, recording, recordVersion)) {
        updates += InlineSnapshotUpdate(checkNotNull(diagnosticLocation), diagnosticCall, mapOf("expected" to actual), originalTestSource)
      } else if (expectation.missing) {
        failures += "Missing diagnostic snapshot; enable recording to insert it."
      } else {
        val difference = compareSnapshot(actual, expectation.forVersion(compilerVersion).trimIndent().trimEnd('\n', '\r'), textSnapshotting)
        if (difference != null) failures += "Diagnostic snapshot differs:\nExpected:\n${difference.expected}\nActual:\n${difference.actual}"
      }
    }
    if (autoDiagnostics) {
      val implicit = linkedMapOf<String, String>()
      autoKinds.sortedBy { it.name }.forEach { kind ->
        val capture = result.captures[kind]
        if (capture != null) implicit["__${kind.name.lowercase()}Block"] = capture
        else if (result.succeeded) failures += "The compiler did not provide the default ${kind.name} snapshot."
      }
      implicit["__diagnosticsBlock"] = renderCompilerDiagnostics(sources, result.diagnostics)
      updates += InlineSnapshotUpdate(checkNotNull(autoTarget), "assertCompiler", implicit, autoOriginal)
    }
    if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n", prefix = "Compiler assertions failed:\n"))
    if (updates.isNotEmpty()) {
      try {
        if (updates.size == 1) {
          val update = updates.single()
          recordInlineSnapshots(update.location, update.assertionName, update.values, compilerVersion, if (autoDiagnostics) false else recordVersion, update.expectedSource)
        } else recordInlineSnapshotBatch(updates, compilerVersion, if (autoDiagnostics) false else recordVersion)
      } catch (error: Exception) {
        throw AssertionError("Could not record compiler snapshots: ${error.message}", error)
      }
      val description = if (updates.size == 1 && hasDiagnosticSnapshot && updates.single().location == diagnosticLocation) "diagnostics" else "compiler snapshots"
      throw AssertionError("Recorded $description; review and rerun the test.")
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
          (expected.file != null && diagnostic.location?.substringBeforeLast(':') != expected.file) ||
          (expected.file == null && expected.line == null && expected.column != null && diagnostic.location?.substringBeforeLast(':') != primaryFile) ||
          (expected.line != null && diagnostic.location != "${expected.file ?: primaryFile}:${expected.line}") ||
          (expected.column != null && diagnostic.column != expected.column)) continue
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
  val file: String?,
  val column: Int?,
)

/** Builder for expected compiler diagnostics. */
public class CompilerDiagnosticExpectations internal constructor(
  private val expected: MutableList<ExpectedCompilerDiagnostic>,
  private val recordSnapshot: (SnapshotExpectation?, InlineSnapshotLocation?) -> Unit,
) {
  /** Compare or record all diagnostics rendered against the source. */
  public fun snapshot(expected: String? = null, location: InlineSnapshotLocation? = null) = recordSnapshot(expected?.let(::SnapshotExpectation), location)
  public fun snapshot(expected: SnapshotExpectation, location: InlineSnapshotLocation? = null) = recordSnapshot(expected, location)
  public fun error(messageContains: String, location: String? = null, line: Int? = null, file: String? = null, column: Int? = null) {
    expect(CompilerDiagnosticSeverity.ERROR, messageContains, location, line, file, column)
  }

  public fun warning(messageContains: String, location: String? = null, line: Int? = null, file: String? = null, column: Int? = null) {
    expect(CompilerDiagnosticSeverity.WARNING, messageContains, location, line, file, column)
  }

  private fun expect(severity: CompilerDiagnosticSeverity, messageContains: String, location: String?, line: Int?, file: String?, column: Int?) {
    require(column == null || column > 0) { "Diagnostic column must be positive." }
    require(file == null || file.isNotBlank()) { "Diagnostic file must not be blank." }
    require(line == null || line > 0) { "Diagnostic line must be positive." }
    require(messageContains.isNotBlank()) { "Expected diagnostic message must not be blank." }
    expected += ExpectedCompilerDiagnostic(severity, messageContains, location, line, file, column)
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
  record: SnapshotRecording = configuredRecording(),
  recordVersion: Boolean = configuredRecordVersion(),
  location: InlineSnapshotLocation? = null,
  bytecode: SnapshotExpectation? = null,
  diagnostics: SnapshotExpectation? = null,
  additionalSources: List<CompilerSource> = emptyList(),
) {
  if (fir == null && ir == null && bytecode == null && diagnostics == null) {
    assertCompiler(listOf(CompilerSource("Main.kt", source)) + additionalSources, compilerVersion, compiler,
      record = record, recordVersion = recordVersion, location = location) {}
    return
  }
  val expected = buildMap {
    fir?.let { put(CompilerSnapshotKind.FIR, it) }
    ir?.let { put(CompilerSnapshotKind.IR, it) }
    bytecode?.let { put(CompilerSnapshotKind.BYTECODE, it) }
    if (isEmpty() && diagnostics == null) {
      put(CompilerSnapshotKind.FIR, snapshot())
      put(CompilerSnapshotKind.IR, snapshot())
    }
  }
  val couldRecord = expected.values.any { dev.knapshots.testing.snapshot.mayRecordSnapshot(record, it.missing, recordVersion) } ||
    (diagnostics != null && dev.knapshots.testing.snapshot.mayRecordSnapshot(record, diagnostics.missing, recordVersion))
  val recordingLocation = if (couldRecord) location ?: discoverInlineSnapshotLocation() else null
  val original = recordingLocation?.let { java.nio.file.Files.readString(it.file) }
  val sources = (listOf(CompilerSource("Main.kt", source)) + additionalSources).map { it.copy(text = it.text.trimIndent()) }
  require(sources.map { it.fileName }.distinct().size == sources.size) { "Source file names must be unique." }
  val result = compiler.compile(CompilerRequest(sources, compilerVersion, expected.keys))
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
