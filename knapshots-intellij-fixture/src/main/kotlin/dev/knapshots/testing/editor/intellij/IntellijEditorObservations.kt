package dev.knapshots.testing.editor.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import dev.knapshots.testing.editor.DiagnosticSeverity
import dev.knapshots.testing.editor.EditorDiagnostic
import dev.knapshots.testing.editor.EditorObservations
import dev.knapshots.testing.editor.ReferenceObservation
import dev.knapshots.testing.editor.QuickFixRequest
import dev.knapshots.testing.editor.QuickFixObservation
import dev.knapshots.testing.editor.EditorSource

/** Adapts IntelliJ's focused test fixture to the editor assertion layer. */
public class IntellijEditorObservations(
  private val fixture: CodeInsightTestFixture,
  // Opt in only for the light fixture's known stub-classpath diagnostic.
  private val ignoreMissingDependency: Boolean = false,
  private val sources: List<EditorSource> = emptyList(),
) : EditorObservations {
  override fun ideBuild(): String = ApplicationInfo.getInstance().build.asString()
  override fun ideRelease(): dev.knapshots.testing.editor.EditorRelease? = dev.knapshots.testing.editor.AndroidStudioReleaseMetadata.resolve(ideBuild())

  override fun diagnostics(): List<EditorDiagnostic> = fixture.doHighlighting()
    .filterNot { ignoreMissingDependency && it.description?.contains("MISSING_DEPENDENCY") == true }
    // Syntax-coloring highlights have no diagnostic message. Never hide errors or warnings.
    .filterNot {
      it.description == null && it.severity != HighlightSeverity.ERROR &&
        it.severity != HighlightSeverity.WARNING && it.severity != HighlightSeverity.WEAK_WARNING
    }
    .map {
      EditorDiagnostic(
        severity = when (it.severity) {
          HighlightSeverity.ERROR -> DiagnosticSeverity.ERROR
          HighlightSeverity.WARNING, HighlightSeverity.WEAK_WARNING -> DiagnosticSeverity.WARNING
          else -> DiagnosticSeverity.INFO
        },
        message = it.description ?: "<no description>",
        location = fixture.editor.document.let { document ->
          val offset = it.actualStartOffset.coerceIn(0, document.textLength)
          val line = document.getLineNumber(offset)
          "${fixture.file.name}:${line + 1}:${offset - document.getLineStartOffset(line) + 1}"
        },
      )
    }

  override fun quickFix(request: QuickFixRequest): QuickFixObservation {
    val main = sources.firstOrNull() ?: return QuickFixObservation.Unavailable("original source is unavailable")
    fixture.configureByText(main.fileName, main.text)
    val document = fixture.editor.document
    val highlights = fixture.doHighlighting().filter { it.description != null }
    val candidates = mutableListOf<Pair<Int, com.intellij.codeInsight.intention.IntentionAction>>()
    val available = linkedSetOf<String>()
    for (offset in highlights.map { it.actualStartOffset.coerceIn(0, document.textLength) }.distinct()) {
      val line = document.getLineNumber(offset) + 1
      if (request.line != null && line != request.line) continue
      fixture.editor.caretModel.moveToOffset(offset)
      for (action in fixture.availableIntentions) {
        available += action.text
        if (action.text.contains(request.description)) candidates += offset to action
      }
    }
    if (candidates.isEmpty()) return QuickFixObservation.Unavailable("no matching fix at a diagnostic; available: ${available.joinToString().ifEmpty { "none" }}")
    if (candidates.size != 1) return QuickFixObservation.Unavailable("ambiguous match: ${candidates.size} fixes; specify a line or a more precise description")
    val (offset, action) = candidates.single()
    fixture.editor.caretModel.moveToOffset(offset)
    return try {
      fixture.launchAction(action)
      val result = fixture.editor.document.text
      val changedOther = sources.drop(1).firstOrNull { source ->
        val file = fixture.findFileInTempDir(source.fileName)
        val otherDocument = file?.let { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(it) }
        file == null || (otherDocument?.text ?: String(file.contentsToByteArray(), Charsets.UTF_8)) != source.text
      }
      if (changedOther != null) QuickFixObservation.Unavailable("fix also changed ${changedOther.fileName}; multi-file fixes are not supported yet")
      else QuickFixObservation.Applied(result)
    } finally {
      sources.drop(1).forEach { source ->
        val file = fixture.findFileInTempDir(source.fileName)
        val otherDocument = file?.let { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(it) }
        if (otherDocument != null && otherDocument.text != source.text) {
          com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(fixture.project) { otherDocument.setText(source.text) }
        }
      }
      fixture.configureByText(main.fileName, main.text)
    }
  }

  override fun reference(expression: String): ReferenceObservation {
    val file = fixture.file ?: return ReferenceObservation.Unavailable("no editor file")
    val content = file.text
    val start = content.indexOf(expression)
    if (start < 0) return ReferenceObservation.Unavailable("expression not found")
    if (content.indexOf(expression, start + 1) >= 0) return ReferenceObservation.Unavailable("expression is ambiguous")
    val lastPart = expression.substringAfterLast('.')
    if (lastPart.isEmpty()) return ReferenceObservation.Unavailable("expression has no final reference")
    val element = file.findElementAt(start + expression.length - lastPart.length)?.parent
      ?: return ReferenceObservation.Unavailable("reference element not found")
    val references = element.references
    if (references.isEmpty()) return ReferenceObservation.Unavailable("element has no reference")
    val target = references.firstNotNullOfOrNull { it.resolve() }
    return if (target == null) ReferenceObservation.Unresolved else ReferenceObservation.Resolved(target.text)
  }
}
