package dev.knapshots.testing.editor

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
  block: EditorExpectations.() -> Unit,
) {
  EditorExpectations(checkDiagnostics).apply(block).verify(observations)
}

private val diagnosticSnapshotting = textSnapshotting.pullback<List<EditorDiagnostic>> { diagnostics ->
  diagnostics.joinToString("\n") { diagnostic ->
    "${diagnostic.severity}${diagnostic.location?.let { " at $it" } ?: ""}: ${diagnostic.message}"
  }
}

public class EditorExpectations internal constructor(
  private val checkDiagnostics: Boolean,
) {
  private val expectedDiagnostics = mutableListOf<ExpectedDiagnostic>()
  private val references = mutableListOf<ReferenceExpectation>()
  private var expectedDiagnosticSnapshot: String? = null
  private var expectedSourceDiagnosticSnapshot: String? = null
  private var hasSourceDiagnosticSnapshot = false
  private var sourceSnapshotLocation: EditorSnapshotLocation? = null
  private var hasDiagnosticBlock = false

  public fun diagnostics(block: DiagnosticExpectations.() -> Unit) {
    check(checkDiagnostics) { "Diagnostic checking is disabled for this editor assertion." }
    check(expectedDiagnosticSnapshot == null && !hasSourceDiagnosticSnapshot) { "Diagnostic snapshots and individual diagnostic expectations cannot be combined." }
    hasDiagnosticBlock = true
    DiagnosticExpectations(expectedDiagnostics).apply(block)
  }

  /** Compare all captured diagnostics as text, including warnings. Recording is not yet supported. */
  public fun diagnosticsSnapshot(expected: String) {
    check(checkDiagnostics) { "Diagnostic checking is disabled for this editor assertion." }
    check(expectedDiagnosticSnapshot == null && !hasSourceDiagnosticSnapshot && !hasDiagnosticBlock) {
      "Diagnostic snapshots and individual diagnostic expectations cannot be combined or repeated."
    }
    expectedDiagnosticSnapshot = expected.trimIndent().trimEnd('\n', '\r')
  }

  /** Compare diagnostics in source context; requires a source-hosted assertion. */
  public fun sourceDiagnosticsSnapshot(expected: String? = null, location: EditorSnapshotLocation? = null) {
    check(checkDiagnostics) { "Diagnostic checking is disabled for this editor assertion." }
    check(expectedDiagnosticSnapshot == null && !hasSourceDiagnosticSnapshot && !hasDiagnosticBlock) {
      "Diagnostic snapshots and individual diagnostic expectations cannot be combined or repeated."
    }
    hasSourceDiagnosticSnapshot = true
    expectedSourceDiagnosticSnapshot = expected?.trimIndent()?.trimEnd('\n', '\r')
    if (editorRecordingMode() != "none") sourceSnapshotLocation = location ?: discoverEditorSnapshotLocation()
  }

  public fun reference(expression: String): ReferenceExpectation {
    require(expression.isNotBlank()) { "A reference expression must not be blank." }
    return ReferenceExpectation(expression).also(references::add)
  }

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
      val rendered = renderSourceDiagnostics(sources.map { DiagnosticSource(it.fileName, it.text) }, actual.map { diagnostic ->
        val match = diagnostic.location?.replace('\\', '/')?.let { Regex("^(.*):(\\d+):(\\d+)$").matchEntire(it) }
        val lineOnly = if (match == null) diagnostic.location?.replace('\\', '/')?.let { Regex("^(.*):(\\d+)$").matchEntire(it) } else null
        SourceDiagnostic(diagnostic.severity.name, diagnostic.message, match?.groupValues?.get(1) ?: lineOnly?.groupValues?.get(1),
          match?.groupValues?.get(2)?.toIntOrNull() ?: lineOnly?.groupValues?.get(2)?.toIntOrNull(),
          match?.groupValues?.get(3)?.toIntOrNull(), diagnostic.location)
      })
      val difference = sourceSnapshot?.let { compareSnapshot(rendered, it, textSnapshotting) }
      val shouldRecord = when (editorRecordingMode()) {
        "none" -> false
        "missing" -> sourceSnapshot == null
        "failed" -> sourceSnapshot == null || difference != null
        "all" -> true
        else -> error("Invalid editor snapshot recording mode.")
      }
      if (shouldRecord) {
        val location = checkNotNull(sourceSnapshotLocation)
        recordEditorSnapshot(location, rendered)
        throw AssertionError("Recorded editor diagnostics at ${location.file}:${location.line}; review and rerun the test.")
      }
      checkNotNull(sourceSnapshot) { "Missing source diagnostic snapshot; enable recording to insert it." }
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
          if (visited[index] || diagnostic.severity != expected.severity ||
            !diagnostic.message.contains(expected.messageContains) ||
            (expected.location != null && expected.location != diagnostic.location)) continue
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

internal data class ExpectedDiagnostic(val severity: DiagnosticSeverity, val messageContains: String, val location: String?)

public class DiagnosticExpectations internal constructor(private val expected: MutableList<ExpectedDiagnostic>) {
  public fun error(messageContains: String, location: String? = null) = expect(DiagnosticSeverity.ERROR, messageContains, location)
  public fun warning(messageContains: String, location: String? = null) = expect(DiagnosticSeverity.WARNING, messageContains, location)

  private fun expect(severity: DiagnosticSeverity, message: String, location: String?) {
    require(message.isNotBlank()) { "Expected diagnostic message must not be blank." }
    expected += ExpectedDiagnostic(severity, message, location)
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
