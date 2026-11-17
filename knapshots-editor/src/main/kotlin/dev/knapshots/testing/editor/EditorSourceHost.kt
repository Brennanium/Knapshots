package dev.knapshots.testing.editor

/** A named Kotlin source file supplied to an editor host. */
public data class EditorSource(
  val fileName: String,
  val text: String,
)

/**
 * The complete request supplied to an editor host.
 *
 * It contains observations to collect, never expected outcomes. Expectations stay in the calling
 * test process, so an IDE adapter only reports what it actually observed.
 */
public data class EditorRequest(
  val sources: List<EditorSource>,
  val referenceExpressions: Set<String>,
  val captureDiagnostics: Boolean,
) {
  init {
    require(sources.isNotEmpty()) { "At least one editor source is required." }
    require(sources.all { it.fileName.isNotBlank() }) { "An editor source file name must not be blank." }
    require(sources.map { it.fileName }.distinct().size == sources.size) {
      "Every editor source file must have a unique file name."
    }
    require(referenceExpressions.none { it.isBlank() }) { "A requested reference expression must not be blank." }
  }
}

/** Opens a prepared source request in an editor environment and returns actual observations. */
public fun interface EditorSourceHost {
  public fun open(request: EditorRequest): EditorObservations
}

/**
 * Opens inline source in a test host and checks the resulting editor observations.
 *
 * The host owns IDE/project lifecycle and dependency setup. The assertion gathers requested
 * references before opening the host, allowing a remote IDE process to inspect exactly the
 * expressions that the caller asserted.
 */
public fun assertEditor(
  source: String,
  host: EditorSourceHost,
  fileName: String = "Test.kt",
  additionalSources: List<EditorSource> = emptyList(),
  checkDiagnostics: Boolean = true,
  block: EditorExpectations.() -> Unit,
) {
  require(fileName.isNotBlank()) { "The source file name must not be blank." }
  val sources = listOf(EditorSource(fileName, source.trimIndent())) + additionalSources.map { sourceFile ->
    require(sourceFile.fileName.isNotBlank()) { "An additional source file name must not be blank." }
    sourceFile.copy(text = sourceFile.text.trimIndent())
  }
  val expectations = EditorExpectations(checkDiagnostics).apply(block)
  val observations = host.open(
    EditorRequest(
      sources = sources,
      referenceExpressions = expectations.requestedReferences(),
      captureDiagnostics = checkDiagnostics,
    ),
  )
  expectations.verify(observations)
}
