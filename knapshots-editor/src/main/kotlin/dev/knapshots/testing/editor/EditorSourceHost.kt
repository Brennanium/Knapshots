package dev.knapshots.testing.editor

import dev.knapshots.testing.snapshot.SnapshotRecording

/** A named Kotlin source file supplied to an editor host. */
public data class EditorSource(
  val fileName: String,
  val text: String,
)

/**
 * The complete request supplied to an editor host.
 *
 * Assertions stay in the calling test process. Readiness references are optional explicit
 * prerequisites for asynchronous plugin initialization; they are separate from assertions.
 */
public data class EditorRequest(
  val sources: List<EditorSource>,
  val referenceExpressions: Set<String>,
  val captureDiagnostics: Boolean,
  val readinessReferences: Set<String> = emptySet(),
) {
  init {
    require(sources.isNotEmpty()) { "At least one editor source is required." }
    require(sources.all { it.fileName.isNotBlank() }) { "An editor source file name must not be blank." }
    require(sources.map { it.fileName }.distinct().size == sources.size) {
      "Every editor source file must have a unique file name."
    }
    require(referenceExpressions.none { it.isBlank() }) { "A requested reference expression must not be blank." }
    require(readinessReferences.none { it.isBlank() }) { "A readiness reference must not be blank." }
  }
}

/**
 * Opens a prepared source request in an editor environment and returns actual observations.
 * Hosts must wait for [EditorRequest.readinessReferences] to resolve or report a failure; they
 * must not silently ignore those prerequisites.
 */
public fun interface EditorSourceHost {
  public fun open(request: EditorRequest): EditorObservations
}

/**
 * An [EditorObservations] value whose host needs the final assertion result to release resources.
 *
 * Source-driven [assertEditor] calls complete these sessions automatically. Callers that invoke
 * [EditorSourceHost.open] directly must call [complete] themselves.
 */
public interface EditorObservationSession : EditorObservations {
  public fun complete(successful: Boolean)
}

/**
 * Opens inline source in a test host and checks the resulting editor observations.
 *
 * The host owns IDE/project lifecycle and dependency setup. The assertion gathers requested
 * references before opening the host, allowing a remote IDE process to inspect exactly the
 * expressions that the caller asserted. [awaitReferences] can identify positive readiness
 * prerequisites for asynchronously loaded plugins; it is distinct from the assertions and
 * should not be used for a reference whose intended result is unresolved.
 */
public fun assertEditor(
  source: String,
  host: EditorSourceHost,
  fileName: String = "Test.kt",
  additionalSources: List<EditorSource> = emptyList(),
  checkDiagnostics: Boolean = true,
  awaitReferences: Set<String> = emptySet(),
  record: SnapshotRecording = configuredEditorRecording(),
  location: EditorSnapshotLocation? = null,
  block: EditorExpectations.() -> Unit = {},
) {
  require(fileName.isNotBlank()) { "The source file name must not be blank." }
  val sources = listOf(EditorSource(fileName, source.trimIndent())) + additionalSources.map { sourceFile ->
    require(sourceFile.fileName.isNotBlank()) { "An additional source file name must not be blank." }
    sourceFile.copy(text = sourceFile.text.trimIndent())
  }
  val expectations = EditorExpectations(checkDiagnostics, record).apply(block)
  val autoTarget = if (record != SnapshotRecording.NONE && expectations.shouldAutomaticallyCaptureDiagnostics()) location ?: discoverEditorSnapshotLocation() else null
  val observations = host.open(
    EditorRequest(
      sources = sources,
      referenceExpressions = expectations.requestedReferences(),
      captureDiagnostics = checkDiagnostics,
      readinessReferences = awaitReferences,
    ),
  )
  var successful = false
  try {
    if (record == SnapshotRecording.NONE && expectations.shouldAutomaticallyCaptureDiagnostics()) {
      throw AssertionError("Missing editor diagnostic snapshot; enable recording to insert it.")
    }
    if (autoTarget != null) {
      val actual = observations.diagnostics()
      try { recordEditorSnapshot(autoTarget, renderEditorDiagnostics(sources, actual), "assertEditor", "__diagnosticsBlock") }
      catch (error: Exception) { throw AssertionError("Could not record editor diagnostics at ${autoTarget.file}:${autoTarget.line}: ${error.message}", error) }
      throw AssertionError("Recorded editor diagnostics at ${autoTarget.file}:${autoTarget.line}; review and rerun the test.")
    }
    expectations.verify(observations, sources)
    successful = true
  } finally {
    (observations as? EditorObservationSession)?.complete(successful)
  }
}

/** Project-level factory discovered by a test-framework integration. */
public fun interface EditorHostProvider {
  public fun createHost(): EditorSourceHost
}

private val editorHostContext = ThreadLocal<EditorSourceHost>()

/** Runs editor assertions with a shared host on the current thread; nested scopes are restored. */
public fun <T> withEditorHost(host: EditorSourceHost, block: () -> T): T {
  val previous = editorHostContext.get()
  editorHostContext.set(host)
  return try { block() } finally {
    if (previous == null) editorHostContext.remove() else editorHostContext.set(previous)
  }
}

/** Opens source using the host installed by [withEditorHost]. */
public fun assertEditor(
  source: String,
  fileName: String = "Test.kt",
  additionalSources: List<EditorSource> = emptyList(),
  checkDiagnostics: Boolean = true,
  awaitReferences: Set<String> = emptySet(),
  record: SnapshotRecording = configuredEditorRecording(),
  location: EditorSnapshotLocation? = null,
  block: EditorExpectations.() -> Unit = {},
) {
  val host = checkNotNull(editorHostContext.get()) {
    "No editor host is configured; use withEditorHost or pass host to assertEditor."
  }
  assertEditor(source, host, fileName, additionalSources, checkDiagnostics, awaitReferences, record, location, block)
}
