package dev.knapshots.testing.editor

import dev.knapshots.testing.snapshot.trimSnapshotIndent

import dev.knapshots.testing.snapshot.SnapshotRecording
import dev.knapshots.testing.snapshot.DiagnosticSource
import dev.knapshots.testing.snapshot.SourceDiagnostic
import dev.knapshots.testing.snapshot.renderSourceDiagnostics
import dev.knapshots.testing.snapshot.compareSnapshot
import dev.knapshots.testing.snapshot.textSnapshotting

/** A diagnostic captured from the editor after the project and analysis are ready. */
public data class EditorDiagnostic(
  val severity: DiagnosticSeverity,
  val message: String,
  val location: String? = null,
)

public enum class DiagnosticSeverity { ERROR, WARNING, INFO }

/** An unavailable observation is different from an IDE successfully reporting no target. */
public sealed interface ReferenceObservation {
  public data class Resolved(val target: String? = null) : ReferenceObservation
  public data object Unresolved : ReferenceObservation
  public data class Unavailable(val reason: String) : ReferenceObservation
}

/** Supplied by an IDE adapter; failures must not be reported as unresolved references. */
public interface EditorObservations {
  public fun diagnostics(): List<EditorDiagnostic>
  public fun reference(expression: String): ReferenceObservation
}

/**
 * Check observations from an already prepared editor. Unexpected errors fail by default;
 * warnings are checked only when explicitly expected. Set [checkDiagnostics] to false only for
 * a reference-only adapter that cannot capture diagnostics. This function does not launch an IDE.
 */
public fun assertEditor(
  observations: EditorObservations,
  checkDiagnostics: Boolean = true,
  record: SnapshotRecording = configuredEditorRecording(),
  block: EditorExpectations.() -> Unit,
) {
  EditorExpectations(checkDiagnostics, record).apply(block).verify(observations)
}

private val diagnosticSnapshotting = textSnapshotting.pullback<List<EditorDiagnostic>> { diagnostics ->
  diagnostics.joinToString("\n") { diagnostic ->
    "${diagnostic.severity}${diagnostic.location?.let { " at $it" } ?: ""}: ${diagnostic.message}"
  }
}

public class EditorExpectations internal constructor(
  private val checkDiagnostics: Boolean,
  private val recording: SnapshotRecording,
) {
  private val expectedDiagnostics = mutableListOf<ExpectedDiagnostic>()
  private val references = mutableListOf<ReferenceExpectation>()
  private var expectedDiagnosticSnapshot: String? = null
  private var expectedSourceDiagnosticSnapshot: String? = null
  private var hasSourceDiagnosticSnapshot = false
  private var sourceSnapshotLocation: EditorSnapshotLocation? = null
  private var sourceSnapshotCall: String = "sourceDiagnosticsSnapshot"
  private var hasDiagnosticBlock = false

  /** Record a diagnostic snapshot, expanding to a diagnostics block. */
  public fun diagnostics(location: EditorSnapshotLocation? = null) {
    diagnostics { snapshot(location = location) }
    sourceSnapshotCall = "diagnostics"
  }

  public fun diagnostics(block: DiagnosticExpectations.() -> Unit) {
    check(checkDiagnostics) { "Diagnostic checking is disabled for this editor assertion." }
    check(expectedDiagnosticSnapshot == null && !hasSourceDiagnosticSnapshot) { "Diagnostic snapshots and individual diagnostic expectations cannot be combined." }
    hasDiagnosticBlock = true
    DiagnosticExpectations(expectedDiagnostics) { expected, location ->
      check(expectedDiagnostics.isEmpty() && !hasSourceDiagnosticSnapshot) { "Diagnostic snapshots and individual diagnostic expectations cannot be combined." }
      hasSourceDiagnosticSnapshot = true
      expectedSourceDiagnosticSnapshot = expected?.let(::trimSnapshotIndent)?.trimEnd('\n', '\r')
      sourceSnapshotCall = "snapshot"
      if (dev.knapshots.testing.snapshot.mayRecordSnapshot(recording, expected == null)) sourceSnapshotLocation = location ?: discoverEditorSnapshotLocation()
    }.apply(block)
    check(!hasSourceDiagnosticSnapshot || expectedDiagnostics.isEmpty()) { "Diagnostic snapshots and individual diagnostic expectations cannot be combined." }
  }

  /** Compare all captured diagnostics as text, including warnings. Recording is not yet supported. */
  public fun diagnosticsSnapshot(expected: String) {
    check(checkDiagnostics) { "Diagnostic checking is disabled for this editor assertion." }
    check(expectedDiagnosticSnapshot == null && !hasSourceDiagnosticSnapshot && !hasDiagnosticBlock) {
      "Diagnostic snapshots and individual diagnostic expectations cannot be combined or repeated."
    }
    expectedDiagnosticSnapshot = trimSnapshotIndent(expected).trimEnd('\n', '\r')
  }

  /** Compare diagnostics in source context; requires a source-hosted assertion. */
  public fun sourceDiagnosticsSnapshot(expected: String? = null, location: EditorSnapshotLocation? = null) {
    check(checkDiagnostics) { "Diagnostic checking is disabled for this editor assertion." }
    check(expectedDiagnosticSnapshot == null && !hasSourceDiagnosticSnapshot && !hasDiagnosticBlock) {
      "Diagnostic snapshots and individual diagnostic expectations cannot be combined or repeated."
    }
    hasSourceDiagnosticSnapshot = true
    expectedSourceDiagnosticSnapshot = expected?.let(::trimSnapshotIndent)?.trimEnd('\n', '\r')
    if (dev.knapshots.testing.snapshot.mayRecordSnapshot(recording, expected == null)) sourceSnapshotLocation = location ?: discoverEditorSnapshotLocation()
  }

  public fun reference(expression: String): ReferenceExpectation {
    require(expression.isNotBlank()) { "A reference expression must not be blank." }
    return ReferenceExpectation(expression).also(references::add)
  }

  internal fun shouldAutomaticallyCaptureDiagnostics(): Boolean = checkDiagnostics && !hasDiagnosticBlock &&
    expectedDiagnosticSnapshot == null && !hasSourceDiagnosticSnapshot && references.isEmpty()

  internal fun requestedReferences(): Set<String> = references.mapTo(linkedSetOf()) { it.expression }

  internal fun verify(observations: EditorObservations, sources: List<EditorSource>? = null) {
    val failures = mutableListOf<String>()
    for (expected in references) {
      val shouldResolve = expected.shouldResolve
      if (shouldResolve == null) {
        failures += "No expected resolution specified for reference '${expected.expression}'"
        continue
      }
      when (val result = observations.reference(expected.expression)) {
        is ReferenceObservation.Resolved -> if (!shouldResolve) failures += "Reference '${expected.expression}' unexpectedly resolved"
        ReferenceObservation.Unresolved -> if (shouldResolve) failures += "Reference '${expected.expression}' did not resolve"
        is ReferenceObservation.Unavailable -> failures += "Cannot inspect reference '${expected.expression}': ${result.reason}"
      }
    }
    if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n", prefix = "Editor assertions failed:\n"))
    if (checkDiagnostics) verifyDiagnostics(observations, failures, sources)
    if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n", prefix = "Editor assertions failed:\n"))
  }

  private fun verifyDiagnostics(observations: EditorObservations, failures: MutableList<String>, sources: List<EditorSource>?) {
    val actual = observations.diagnostics()
    val expectedSnapshot = expectedDiagnosticSnapshot
    val sourceSnapshot = expectedSourceDiagnosticSnapshot
    if (hasSourceDiagnosticSnapshot) {
      checkNotNull(sources) { "Source diagnostic snapshots require a source-hosted assertEditor." }
      val rendered = renderEditorDiagnostics(sources, actual)
      val difference = sourceSnapshot?.let { compareSnapshot(rendered, it, textSnapshotting) }
      val shouldRecord = dev.knapshots.testing.snapshot.shouldRecordSnapshot(
        recording, missing = sourceSnapshot == null, differs = sourceSnapshot == null || difference != null,
      )
      if (shouldRecord) {
        val location = checkNotNull(sourceSnapshotLocation)
        recordEditorSnapshot(location, rendered, sourceSnapshotCall)
        throw AssertionError("Recorded editor diagnostics at ${location.file}:${location.line}; review and rerun the test.")
      }
      if (sourceSnapshot == null) throw AssertionError("Missing source diagnostic snapshot; enable recording to insert it.")
      if (difference != null) failures += "Source diagnostic snapshot differs:\nExpected:\n${difference.expected}\nActual:\n${difference.actual}"
    } else if (expectedSnapshot != null) {
      val difference = compareSnapshot(actual, expectedSnapshot, diagnosticSnapshotting)
      if (difference != null) failures += "Diagnostic snapshot differs:\nExpected:\n${difference.expected}\nActual:\n${difference.actual}"
    } else {
      // Find a one-to-one match even when a broad expected message overlaps a more specific one.
      val owner = IntArray(actual.size) { -1 }
      fun assign(expectedIndex: Int, visited: BooleanArray): Boolean {
        val expected = expectedDiagnostics[expectedIndex]
        for (index in actual.indices) {
          val diagnostic = actual[index]
          val normalized = diagnostic.location?.replace('\\', '/')
          val match = normalized?.let { Regex("^(.*):(\\d+):(\\d+)$").matchEntire(it) }
          val lineOnly = if (match == null) normalized?.let { Regex("^(.*):(\\d+)$").matchEntire(it) } else null
          val wantedFile = expected.file ?: if (expected.line != null || expected.column != null) {
            checkNotNull(sources) { "Line-only editor diagnostics require a source-hosted assertEditor or an explicit file." }.first().fileName
          } else null
          if (visited[index] || diagnostic.severity != expected.severity ||
            !diagnostic.message.contains(expected.messageContains) ||
            (expected.location != null && expected.location != diagnostic.location) ||
            (wantedFile != null && (match?.groupValues?.get(1) ?: lineOnly?.groupValues?.get(1)) != wantedFile.replace('\\', '/')) ||
            (expected.line != null && (match?.groupValues?.get(2) ?: lineOnly?.groupValues?.get(2))?.toIntOrNull() != expected.line) ||
            (expected.column != null && match?.groupValues?.get(3)?.toIntOrNull() != expected.column)) continue
          visited[index] = true
          if (owner[index] == -1 || assign(owner[index], visited)) {
            owner[index] = expectedIndex
            return true
          }
        }
        return false
      }
      for ((index, expected) in expectedDiagnostics.withIndex()) {
        if (!assign(index, BooleanArray(actual.size))) failures += "Missing ${expected.severity} diagnostic containing '${expected.messageContains}'" +
          (expected.location?.let { " at $it" } ?: "")
      }
      actual.filterIndexed { index, it -> owner[index] == -1 && it.severity == DiagnosticSeverity.ERROR }.forEach {
        failures += "Unexpected ERROR${it.location?.let { location -> " at $location" } ?: ""}: ${it.message}"
      }
    }
  }
}

internal data class ExpectedDiagnostic(val severity: DiagnosticSeverity, val messageContains: String, val location: String?, val file: String?, val line: Int?, val column: Int?)

public class DiagnosticExpectations internal constructor(
  private val expected: MutableList<ExpectedDiagnostic>,
  private val recordSnapshot: (String?, EditorSnapshotLocation?) -> Unit,
) {
  /** Compare or record all diagnostics rendered against the source. */
  public fun snapshot(expected: String? = null, location: EditorSnapshotLocation? = null) = recordSnapshot(expected, location)
  public fun error(messageContains: String, location: String? = null, file: String? = null, line: Int? = null, column: Int? = null) = expect(DiagnosticSeverity.ERROR, messageContains, location, file, line, column)
  public fun warning(messageContains: String, location: String? = null, file: String? = null, line: Int? = null, column: Int? = null) = expect(DiagnosticSeverity.WARNING, messageContains, location, file, line, column)

  private fun expect(severity: DiagnosticSeverity, message: String, location: String?, file: String?, line: Int?, column: Int?) {
    require(message.isNotBlank()) { "Expected diagnostic message must not be blank." }
    require(file == null || file.isNotBlank()) { "Diagnostic file must not be blank." }
    require(line == null || line > 0) { "Diagnostic line must be positive." }
    require(column == null || column > 0) { "Diagnostic column must be positive." }
    expected += ExpectedDiagnostic(severity, message, location, file, line, column)
  }
}

public class ReferenceExpectation internal constructor(internal val expression: String) {
  internal var shouldResolve: Boolean? = null
    private set

  public fun resolves() { setExpectation(true) }
  public fun isUnresolved() { setExpectation(false) }

  private fun setExpectation(value: Boolean) {
    check(shouldResolve == null) { "A reference expectation can only be specified once." }
    shouldResolve = value
  }
}

internal fun renderEditorDiagnostics(sources: List<EditorSource>, actual: List<EditorDiagnostic>): String {
  return renderSourceDiagnostics(sources.map { DiagnosticSource(it.fileName, it.text) }, actual.map { diagnostic ->
        val match = diagnostic.location?.replace('\\', '/')?.let { Regex("^(.*):(\\d+):(\\d+)$").matchEntire(it) }
        val lineOnly = if (match == null) diagnostic.location?.replace('\\', '/')?.let { Regex("^(.*):(\\d+)$").matchEntire(it) } else null
        SourceDiagnostic(diagnostic.severity.name, diagnostic.message, match?.groupValues?.get(1) ?: lineOnly?.groupValues?.get(1),
          match?.groupValues?.get(2)?.toIntOrNull() ?: lineOnly?.groupValues?.get(2)?.toIntOrNull(),
          match?.groupValues?.get(3)?.toIntOrNull(), diagnostic.location)
      })
}
