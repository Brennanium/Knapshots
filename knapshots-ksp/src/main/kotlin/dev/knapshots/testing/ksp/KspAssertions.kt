package dev.knapshots.testing.ksp

import dev.knapshots.testing.compiler.CompilerDiagnostic
import dev.knapshots.testing.compiler.CompilerDiagnosticSeverity
import dev.knapshots.testing.compiler.CompilerSource
import dev.knapshots.testing.compiler.renderCompilerDiagnostics
import dev.knapshots.testing.compiler.InlineSnapshotLocation
import dev.knapshots.testing.compiler.InlineSnapshotUpdate
import dev.knapshots.testing.compiler.recordInlineSnapshotBatch
import dev.knapshots.testing.compiler.deferredInlineSnapshotLocation
import dev.knapshots.testing.compiler.VersionedSnapshotExpectations
import dev.knapshots.testing.compiler.selectCompilerVersion
import dev.knapshots.testing.compiler.SnapshotExpectation
import dev.knapshots.testing.compiler.SnapshotRecording
import dev.knapshots.testing.compiler.discoverInlineSnapshotLocation
import dev.knapshots.testing.compiler.recordInlineSnapshots
import dev.knapshots.testing.compiler.shouldRecordSnapshot
import dev.knapshots.testing.snapshot.trimSnapshotIndent
import dev.knapshots.testing.snapshot.compareSnapshot
import dev.knapshots.testing.snapshot.textSnapshotting
import java.nio.file.Files

public data class KspSource(val fileName: String, val text: String)
public data class KspDependency(val name: String, val sources: List<KspSource>)
public data class KspProcessorProject(val name: String, val sources: List<KspSource>, val providers: List<String>)
public enum class KspDiagnosticSeverity { ERROR, WARNING, INFO }
public data class KspDiagnostic(val severity: KspDiagnosticSeverity, val message: String, val file: String? = null, val line: Int? = null, val column: Int? = null)
public data class KspDiagnosticExpectation(val severity: KspDiagnosticSeverity, val messageContains: String, val file: String? = null, val line: Int? = null, val column: Int? = null)
public data class KspRequest(val sources: List<KspSource>, val compilerVersion: String, val dependencies: List<KspDependency> = emptyList(), val mainClass: String? = null, val processorProjects: List<KspProcessorProject> = emptyList())
public data class KspResult(val succeeded: Boolean, val generatedFiles: Map<String, String>, val output: String = "", val diagnostics: List<KspDiagnostic> = emptyList(), val executionSucceeded: Boolean? = null)
public fun interface KspHost { public fun process(request: KspRequest): KspResult }

/** Expectations supplied in the trailing assertion block. */
public class KspExpectations internal constructor(private val recording: SnapshotRecording, private val recordVersion: Boolean, private val compilerVersion: String) {
  private val versionBranches = linkedMapOf<String, KspExpectations.() -> Unit>()
  private var defaultBranch = false
  private var isBranch = false
  internal var generatedCall = "generated"
  private var generatedTarget: (() -> InlineSnapshotLocation)? = null
  private var diagnosticTarget: (() -> InlineSnapshotLocation)? = null
  internal var outcome: Boolean? = null
  internal var hasGenerated = false
  internal var expectedGenerated: SnapshotExpectation? = null
  internal var generatedLocation: InlineSnapshotLocation? = null
  internal var generatedOriginal: String? = null
  internal val diagnosticItems = mutableListOf<KspDiagnosticExpectation>()
  internal var hasDiagnosticBlock = false
  internal var hasDiagnosticSnapshot = false
  internal var expectedSnapshot: SnapshotExpectation? = null
  internal var snapshotLocation: InlineSnapshotLocation? = null
  internal var snapshotCall: String = "snapshot"
  internal var originalSource: String? = null

  public fun default(block: KspExpectations.() -> Unit) {
    check(!isBranch && !defaultBranch) { "Only one default block is allowed." }
    defaultBranch = true
    block()
  }
  public fun versioned(selector: String, block: KspExpectations.() -> Unit) {
    check(!isBranch) { "Version blocks cannot be nested." }
    check(selector !in versionBranches) { "Duplicate version selector: $selector." }
    versionBranches[selector] = block
  }
  internal fun resolveVersions(): KspExpectations {
    if (versionBranches.isEmpty()) return this
    val selected = selectCompilerVersion(versionBranches.keys, compilerVersion) ?: return this
    val branch = KspExpectations(recording, recordVersion, compilerVersion).also { it.isBranch = true }.apply(versionBranches.getValue(selected))
    branch.outcome?.let { outcome = it }
    if (branch.hasGenerated) {
      hasGenerated = true; expectedGenerated = branch.expectedGenerated; generatedLocation = branch.generatedLocation
      generatedOriginal = branch.generatedOriginal; generatedCall = branch.generatedCall; generatedTarget = branch.generatedTarget
    }
    if (branch.hasDiagnosticBlock) {
      hasDiagnosticBlock = true; hasDiagnosticSnapshot = branch.hasDiagnosticSnapshot
      expectedSnapshot = branch.expectedSnapshot; snapshotLocation = branch.snapshotLocation
      snapshotCall = branch.snapshotCall; originalSource = branch.originalSource; diagnosticTarget = branch.diagnosticTarget
      diagnosticItems.clear(); diagnosticItems.addAll(branch.diagnosticItems)
    }
    return this
  }
  internal fun prepareRecordingTargets(): KspExpectations {
    generatedTarget?.let { generatedLocation = it(); generatedOriginal = Files.readString(checkNotNull(generatedLocation).file) }
    diagnosticTarget?.let { snapshotLocation = it(); originalSource = Files.readString(checkNotNull(snapshotLocation).file) }
    return this
  }
  public fun succeeds() { check(outcome == null) { "Compilation outcome can only be specified once." }; outcome = true }
  public fun fails() { check(outcome == null) { "Compilation outcome can only be specified once." }; outcome = false }
  /** Compare or record the processor's generated source files. */
  public fun generated(expected: String? = null, location: InlineSnapshotLocation? = null) = configureGenerated(expected?.let(::SnapshotExpectation), location)
  public fun generated(expected: SnapshotExpectation, location: InlineSnapshotLocation? = null) = configureGenerated(expected, location)
  public fun generated(block: VersionedSnapshotExpectations.() -> Unit) {
    val entry = VersionedSnapshotExpectations().apply(block).selected(compilerVersion)
    check(!hasGenerated) { "Generated output can only be specified once." }
    hasGenerated = true
    expectedGenerated = entry.expected?.let(::SnapshotExpectation)
    generatedCall = entry.call
    if (dev.knapshots.testing.snapshot.mayRecordSnapshot(recording, entry.expected == null, false)) {
      generatedTarget = { entry.recordingLocation() }
    }
  }
  private fun configureGenerated(expected: SnapshotExpectation?, location: InlineSnapshotLocation?) {
    check(!hasGenerated) { "Generated output can only be specified once." }
    hasGenerated = true
    expectedGenerated = expected
    if (dev.knapshots.testing.snapshot.mayRecordSnapshot(recording, expected == null || expected.missing, recordVersion)) {
      generatedTarget = deferredInlineSnapshotLocation(location)
    }
  }
  /** Record a diagnostic snapshot, expanding to a diagnostics block. */
  public fun diagnostics(location: InlineSnapshotLocation? = null) {
    diagnostics { snapshot(location = location) }
    snapshotCall = "diagnostics"
  }
  public fun diagnostics(block: KspDiagnosticExpectations.() -> Unit) {
    check(!hasDiagnosticBlock) { "Diagnostics can only be specified once." }
    hasDiagnosticBlock = true
    KspDiagnosticExpectations(diagnosticItems, compilerVersion, { expected, location ->
      check(!hasDiagnosticSnapshot && diagnosticItems.isEmpty()) { "Diagnostic snapshots and individual expectations cannot be combined." }
      hasDiagnosticSnapshot = true
      expectedSnapshot = expected
      if (dev.knapshots.testing.snapshot.mayRecordSnapshot(recording, expected == null || expected.missing, recordVersion)) {
        diagnosticTarget = deferredInlineSnapshotLocation(location)
      }
    }, { entry ->
      check(!hasDiagnosticSnapshot && diagnosticItems.isEmpty()) { "Diagnostic snapshots and individual expectations cannot be combined." }
      hasDiagnosticSnapshot = true
      expectedSnapshot = entry.expected?.let(::SnapshotExpectation)
      snapshotCall = entry.call
      if (dev.knapshots.testing.snapshot.mayRecordSnapshot(recording, entry.expected == null, false)) {
        diagnosticTarget = { entry.recordingLocation() }
      }
    }).apply(block).finish()
    check(!hasDiagnosticSnapshot || diagnosticItems.isEmpty()) { "Diagnostic snapshots and individual expectations cannot be combined." }
  }
}

public class KspDiagnosticExpectations internal constructor(
  private val expected: MutableList<KspDiagnosticExpectation>,
  private val compilerVersion: String,
  private val recordSnapshot: (SnapshotExpectation?, InlineSnapshotLocation?) -> Unit,
  private val recordVersioned: (VersionedSnapshotExpectations.Entry) -> Unit,
) {
  private var versions: VersionedSnapshotExpectations? = null
  public fun default(expected: String? = null, location: InlineSnapshotLocation? = null) {
    (versions ?: VersionedSnapshotExpectations().also { versions = it }).default(expected, location)
  }
  public fun versioned(selector: String, expected: String? = null, location: InlineSnapshotLocation? = null) {
    (versions ?: VersionedSnapshotExpectations().also { versions = it }).versioned(selector, expected, location)
  }
  internal fun finish() { versions?.let { recordVersioned(it.selected(compilerVersion)) } }

  public fun snapshot(expected: String? = null, location: InlineSnapshotLocation? = null) = recordSnapshot(expected?.let(::SnapshotExpectation), location)
  public fun snapshot(expected: SnapshotExpectation, location: InlineSnapshotLocation? = null) = recordSnapshot(expected, location)
  public fun error(messageContains: String, file: String? = null, line: Int? = null, column: Int? = null) =
    expect(KspDiagnosticSeverity.ERROR, messageContains, file, line, column)
  public fun warning(messageContains: String, file: String? = null, line: Int? = null, column: Int? = null) =
    expect(KspDiagnosticSeverity.WARNING, messageContains, file, line, column)
  private fun expect(severity: KspDiagnosticSeverity, message: String, file: String?, line: Int?, column: Int?) {
    require(message.isNotBlank()) { "Expected diagnostic text must not be blank." }
    require(line == null || line > 0) { "Diagnostic line must be positive." }
    require(column == null || column > 0) { "Diagnostic column must be positive." }
    expected += KspDiagnosticExpectation(severity, message, file, line, column)
  }
}

/** Asserts processor output and compilation outcome. File names are part of the snapshot. */
public fun assertKsp(
  source: String,
  compilerVersion: String = System.getProperty("knapshots.ksp.kotlinVersion") ?: org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION,
  host: KspHost = configuredKspHost(),
  generated: SnapshotExpectation? = null,
  additionalSources: List<KspSource> = emptyList(),
  fileName: String = "Main.kt",
  succeeds: Boolean? = null,
  diagnostics: List<String> = emptyList(),
  expectedDiagnostics: List<KspDiagnosticExpectation>? = null,
  dependencies: List<KspDependency> = emptyList(),
  processorProjects: List<KspProcessorProject> = emptyList(),
  mainClass: String? = null,
  executionSucceeds: Boolean = true,
  record: SnapshotRecording = configuredRecording(),
  recordVersion: Boolean = System.getProperty("knapshots.ksp.recordVersion", "false").toBooleanStrict(),
  location: InlineSnapshotLocation? = null,
  diagnosticSnapshot: SnapshotExpectation? = null,
  block: (KspExpectations.() -> Unit)? = null,
) {
  require(compilerVersion.isNotBlank()) { "A compiler version is required." }
  val expectations = KspExpectations(record, recordVersion, compilerVersion).also { if (block != null) it.block() }.resolveVersions().prepareRecordingTargets()
  check(!expectations.hasGenerated || generated == null) { "Generated output cannot be specified both in the block and in a parameter." }
  check(!expectations.hasDiagnosticBlock || (expectedDiagnostics == null && diagnosticSnapshot == null && diagnostics.isEmpty())) {
    "The diagnostics block cannot be combined with diagnostic parameters."
  }
  val wantedSuccess = expectations.outcome ?: succeeds
  val inputOnly = wantedSuccess == null && !expectations.hasDiagnosticBlock && expectedDiagnostics == null &&
    diagnosticSnapshot == null && diagnostics.isEmpty() && generated == null && !expectations.hasGenerated && mainClass == null
  val autoDiagnostics = inputOnly && record != SnapshotRecording.NONE
  val autoGenerated = autoDiagnostics
  val wantedDiagnostics = if (expectations.hasDiagnosticBlock && !expectations.hasDiagnosticSnapshot) expectations.diagnosticItems else expectedDiagnostics
  val sources = listOf(KspSource(fileName, source)) + additionalSources
  require(sources.map { it.fileName }.distinct().size == sources.size) { "Source file names must be unique." }
  val couldRecordLegacy = listOfNotNull(generated, diagnosticSnapshot).any {
    dev.knapshots.testing.snapshot.mayRecordSnapshot(record, it.missing, recordVersion)
  }
  val target = if (couldRecordLegacy || autoDiagnostics) location ?: discoverInlineSnapshotLocation() else null
  val original = target?.let { Files.readString(it.file) }
  val result = host.process(KspRequest(sources.map { it.copy(text = it.text.trimIndent()) }, compilerVersion,
    dependencies.map { dependency -> dependency.copy(sources = dependency.sources.map { it.copy(text = it.text.trimIndent()) }) }, mainClass, processorProjects.map { processor -> processor.copy(sources = processor.sources.map { it.copy(text = it.text.trimIndent()) }) }))
  if (wantedSuccess != null && result.succeeded != wantedSuccess) throw AssertionError("Expected KSP compilation to ${if (wantedSuccess) "succeed" else "fail"}, but it ${if (result.succeeded) "succeeded" else "failed"}.\n${result.output}")
  if (inputOnly && record == SnapshotRecording.NONE) throw AssertionError("Missing generated output and diagnostic snapshots; enable recording to insert them.")
  diagnostics.forEach { expected ->
    if (!result.output.contains(expected)) throw AssertionError("Missing diagnostic containing '$expected'.\n${result.output}")
  }
  if (mainClass != null && result.succeeded && result.executionSucceeded != executionSucceeds) {
    throw AssertionError("Expected execution to ${if (executionSucceeds) "succeed" else "fail"}, but it ${when (result.executionSucceeded) { true -> "succeeded"; false -> "failed"; null -> "did not run" }}.\n${result.output}")
  }
  wantedDiagnostics?.let { expected ->
    val owner = IntArray(result.diagnostics.size) { -1 }
    fun assign(expectedIndex: Int, visited: BooleanArray): Boolean {
      val item = expected[expectedIndex]
      require(item.messageContains.isNotBlank()) { "Expected diagnostic text must not be blank." }
      for (index in result.diagnostics.indices) {
        val actual = result.diagnostics[index]
        if (visited[index] || actual.severity != item.severity || !actual.message.contains(item.messageContains) ||
          (item.file != null && if (expectations.hasDiagnosticBlock)
            actual.file?.replace('\\', '/')?.removePrefix("src/main/kotlin/") != item.file.replace('\\', '/').removePrefix("src/main/kotlin/")
            else actual.file != item.file) ||
          (expectations.hasDiagnosticBlock && item.file == null && (item.line != null || item.column != null) &&
            actual.file?.replace('\\', '/')?.removePrefix("src/main/kotlin/") != fileName.replace('\\', '/')) ||
          (item.line != null && actual.line != item.line) ||
          (item.column != null && actual.column != item.column)) continue
        visited[index] = true
        if (owner[index] == -1 || assign(owner[index], visited)) { owner[index] = expectedIndex; return true }
      }
      return false
    }
    expected.forEachIndexed { index, item ->
      if (!assign(index, BooleanArray(result.diagnostics.size))) throw AssertionError("Missing diagnostic $item. Actual: ${result.diagnostics}\n${result.output}")
    }
    val unexpected = result.diagnostics.filterIndexed { index, diagnostic -> owner[index] == -1 && diagnostic.severity == KspDiagnosticSeverity.ERROR }
    if (unexpected.isNotEmpty()) throw AssertionError("Unexpected error diagnostics: $unexpected\n${result.output}")
  }
  val actual = result.generatedFiles.toSortedMap().entries.joinToString("\n\n") { (name, text) ->
    "// FILE: $name\n${text.trimEnd('\n', '\r')}"
  }
  val renderedDiagnostics = if (diagnosticSnapshot != null || expectations.hasDiagnosticSnapshot || autoDiagnostics) {
    renderCompilerDiagnostics(sources.map { source -> CompilerSource(source.fileName, source.text.trimIndent()) },
      result.diagnostics.map { item ->
        CompilerDiagnostic(when (item.severity) {
          KspDiagnosticSeverity.ERROR -> CompilerDiagnosticSeverity.ERROR
          KspDiagnosticSeverity.WARNING -> CompilerDiagnosticSeverity.WARNING
          KspDiagnosticSeverity.INFO -> CompilerDiagnosticSeverity.INFO
        }, item.message, item.file?.let { file ->
          val normalized = file.replace('\\', '/').removePrefix("src/main/kotlin/")
          item.line?.let { line -> "$normalized:$line" } ?: normalized
        }, item.column)
      })
  } else null
  val updates = mutableMapOf<String, String>()
  if (generated != null && shouldRecordSnapshot(generated, actual, compilerVersion, record, recordVersion)) updates["generated"] = actual
  if (diagnosticSnapshot != null && shouldRecordSnapshot(diagnosticSnapshot, checkNotNull(renderedDiagnostics), compilerVersion, record, recordVersion)) updates["diagnosticSnapshot"] = renderedDiagnostics
  if (autoGenerated) updates["__generatedBlock"] = actual
  if (autoDiagnostics) updates["__diagnosticsBlock"] = checkNotNull(renderedDiagnostics)
  val generatedExpectation = if (expectations.hasGenerated) expectations.expectedGenerated ?: dev.knapshots.testing.compiler.snapshot() else null
  val generatedDifference = if (generatedExpectation != null && !generatedExpectation.missing)
    compareSnapshot(actual, trimSnapshotIndent(generatedExpectation.forVersion(compilerVersion)).trimEnd('\n', '\r'), textSnapshotting) else null
  val recordGenerated = generatedExpectation != null && shouldRecordSnapshot(generatedExpectation, actual, compilerVersion, record, if (expectations.generatedCall in setOf("default", "versioned")) false else recordVersion)
  val dslExpectation = if (expectations.hasDiagnosticSnapshot) expectations.expectedSnapshot ?: dev.knapshots.testing.compiler.snapshot() else null
  val dslDifference = if (dslExpectation != null && !dslExpectation.missing)
    compareSnapshot(checkNotNull(renderedDiagnostics), trimSnapshotIndent(dslExpectation.forVersion(compilerVersion)).trimEnd('\n', '\r'), textSnapshotting) else null
  val recordDsl = dslExpectation != null && shouldRecordSnapshot(dslExpectation, checkNotNull(renderedDiagnostics), compilerVersion, record, if (expectations.snapshotCall in setOf("default", "versioned")) false else recordVersion)
  val inlineUpdates = mutableListOf<InlineSnapshotUpdate>()
  if (updates.isNotEmpty()) inlineUpdates += InlineSnapshotUpdate(checkNotNull(target), "assertKsp", updates, original)
  if (recordGenerated) inlineUpdates += InlineSnapshotUpdate(checkNotNull(expectations.generatedLocation), expectations.generatedCall, mapOf("expected" to actual), expectations.generatedOriginal)
  if (recordDsl) inlineUpdates += InlineSnapshotUpdate(checkNotNull(expectations.snapshotLocation), expectations.snapshotCall, mapOf("expected" to checkNotNull(renderedDiagnostics)), expectations.originalSource)
  if (inlineUpdates.isNotEmpty()) {
    try {
      if (inlineUpdates.size == 1) {
        val update = inlineUpdates.single()
        recordInlineSnapshots(update.location, update.assertionName, update.values, compilerVersion, if (autoDiagnostics || update.assertionName in setOf("default", "versioned")) false else recordVersion, update.expectedSource)
      } else recordInlineSnapshotBatch(inlineUpdates, compilerVersion, if (autoDiagnostics) false else recordVersion)
    } catch (error: Exception) {
      throw AssertionError("Could not record inline snapshots: ${error.message}", error)
    }
    val description = when {
      recordGenerated && inlineUpdates.size == 1 -> "generated source"
      recordDsl && inlineUpdates.size == 1 -> "diagnostics"
      else -> "inline snapshot"
    }
    throw AssertionError("Recorded $description; review and rerun the test.")
  }
  if (generatedExpectation != null) {
    if (generatedExpectation.missing) throw AssertionError("Missing generated source snapshot; enable recording to insert it.")
    if (generatedDifference != null) throw AssertionError("Generated KSP source differs:\n${generatedDifference.message}")
  }
  if (expectations.hasDiagnosticSnapshot) {
    if (checkNotNull(dslExpectation).missing) throw AssertionError("Missing diagnostic snapshot; enable recording to insert it.")
    if (dslDifference != null) throw AssertionError("KSP diagnostic snapshot differs:\n${dslDifference.message}")
  }
  fun compare(name: String, expectation: SnapshotExpectation, output: String) {
    if (expectation.missing) throw AssertionError("Missing $name snapshot; enable recording to insert it.")
    val difference = compareSnapshot(output.trimEnd('\n', '\r'), trimSnapshotIndent(expectation.forVersion(compilerVersion)).trimEnd('\n', '\r'), textSnapshotting)
    if (difference != null) throw AssertionError("$name for Kotlin $compilerVersion differs.\n${difference.message}")
  }
  if (generated != null) compare("Generated KSP source", generated, actual)
  if (diagnosticSnapshot != null) compare("KSP diagnostic snapshot", diagnosticSnapshot, checkNotNull(renderedDiagnostics))
}

/** Provides a processor host for all assertions in a test runtime. */
public fun interface KspHostProvider { public fun createHost(): KspHost }

private fun configuredKspHost(): KspHost {
  val providers = java.util.ServiceLoader.load(KspHostProvider::class.java).toList()
  check(providers.size <= 1) { "Expected at most one KspHostProvider service, found ${providers.size}." }
  providers.singleOrNull()?.let { return it.createHost() }
  val version = System.getProperty("knapshots.ksp.version") ?: error("Configure knapshots.ksp.version or register a KspHostProvider.")
  fun paths(name: String) = System.getProperty(name).orEmpty().split(java.io.File.pathSeparator).filter(String::isNotBlank).map(java.nio.file.Path::of)
  return GradleKspHost(version, paths("knapshots.ksp.processorClasspath"), paths("knapshots.ksp.classpath"))
}

private fun configuredRecording(): SnapshotRecording {
  val value = System.getProperty("knapshots.ksp.record", "missing")
  return try { SnapshotRecording.valueOf(value.uppercase(java.util.Locale.ROOT)) }
  catch (_: IllegalArgumentException) { error("Invalid knapshots.ksp.record '$value'; use none, missing, failed, or all.") }
}
