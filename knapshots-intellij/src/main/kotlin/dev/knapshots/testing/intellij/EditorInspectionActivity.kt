package dev.knapshots.testing.intellij

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.knapshots.testing.editor.DiagnosticSeverity
import dev.knapshots.testing.editor.EditorDiagnostic
import dev.knapshots.testing.editor.EditorRequest
import dev.knapshots.testing.editor.ReferenceObservation
import dev.knapshots.testing.ide.EditorInspectionProtocol
import dev.knapshots.testing.ide.EditorInspectionResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path

/**
 * IDE-side half of Knapshots' editor protocol.
 *
 * The process launcher supplies the project, request, and response paths through environment
 * variables. This activity ignores every other opened IDE project, letting a runner launch a
 * hermetic inspection without altering a developer's ordinary IDE session.
 */
public class EditorInspectionActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    val requestPath = System.getenv("KNAPSHOTS_EDITOR_REQUEST")?.let(Path::of) ?: return
    val responsePath = System.getenv("KNAPSHOTS_EDITOR_RESPONSE")?.let(Path::of) ?: return
    val expectedProject = System.getenv("KNAPSHOTS_EDITOR_PROJECT")?.let(Path::of) ?: return
    val projectPath = project.basePath?.let(Path::of) ?: return
    if (!samePath(projectPath, expectedProject)) return

    val request = try {
      EditorInspectionProtocol.readRequest(requestPath)
    } catch (error: Exception) {
      writeAndExit(responsePath, EditorInspectionResponse.Failed("Could not read editor request: ${error.message}"))
      return
    }

    try {
      inspect(project, expectedProject, request, responsePath)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      writeAndExit(responsePath, EditorInspectionResponse.Failed("IDE inspection failed: ${error.javaClass.name}: ${error.message}"))
    }
  }

  private suspend fun inspect(
    project: Project,
    projectDirectory: Path,
    request: EditorRequest,
    responsePath: Path,
  ) {
    val sourcePaths = request.sources.map { source ->
      projectDirectory.resolve("src/main/kotlin").resolve(source.fileName).normalize()
    }
    val missing = sourcePaths.filterNot(Files::isRegularFile)
    if (missing.isNotEmpty()) {
      writeAndExit(responsePath, EditorInspectionResponse.Failed("Generated source files are missing: ${missing.joinToString()}"))
      return
    }
    ApplicationManager.getApplication().invokeLater {
      if (project.isDisposed) return@invokeLater
      sourcePaths.forEach { source ->
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)?.let { virtualFile ->
          FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
      }
    }

    val deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS
    var lastPending = "waiting for project import and indexing"
    while (System.currentTimeMillis() < deadline && !project.isDisposed) {
      delay(POLL_INTERVAL_MILLIS)
      if (DumbService.isDumb(project)) {
        lastPending = "waiting for indexing"
        continue
      }
      when (val attempt = inspectWhenReady(project, sourcePaths, request)) {
        is InspectionAttempt.Pending -> lastPending = attempt.reason
        is InspectionAttempt.Completed -> {
          writeAndExit(responsePath, attempt.response)
          return
        }
      }
    }
    writeAndExit(responsePath, EditorInspectionResponse.Failed("IDE inspection timed out: $lastPending"))
  }

  private fun inspectWhenReady(
    project: Project,
    sourcePaths: List<Path>,
    request: EditorRequest,
  ): InspectionAttempt = ReadAction.compute<InspectionAttempt, Throwable> {
    val files = sourcePaths.map { path ->
      val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        ?: return@compute InspectionAttempt.Pending("waiting for source file $path")
      val psi = PsiManager.getInstance(project).findFile(virtualFile)
        ?: return@compute InspectionAttempt.Pending("waiting for PSI for ${virtualFile.name}")
      val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        ?: return@compute InspectionAttempt.Pending("waiting for document for ${virtualFile.name}")
      InspectionFile(psi, document)
    }

    if (request.captureDiagnostics) {
      val analyzer = DaemonCodeAnalyzerEx.getInstanceEx(project)
      val incomplete = files.firstOrNull { file -> !analyzer.isErrorAnalyzingFinished(file.psi) }
      if (incomplete != null) return@compute InspectionAttempt.Pending("waiting for highlighting for ${incomplete.psi.name}")
    }

    val diagnostics = if (request.captureDiagnostics) files.flatMap { file -> diagnostics(project, file) } else null
    val references = request.referenceExpressions.associateWith { expression -> reference(files, expression) }
    InspectionAttempt.Completed(EditorInspectionResponse.Completed(diagnostics, references))
  }

  private fun diagnostics(project: Project, file: InspectionFile): List<EditorDiagnostic> {
    val diagnostics = mutableListOf<EditorDiagnostic>()
    DaemonCodeAnalyzerEx.processHighlights(
      file.document,
      project,
      HighlightSeverity.WARNING,
      0,
      file.document.textLength,
    ) { highlight ->
      val offset = highlight.actualStartOffset.coerceIn(0, file.document.textLength)
      val line = file.document.getLineNumber(offset)
      diagnostics += EditorDiagnostic(
        severity = when (highlight.severity) {
          HighlightSeverity.ERROR -> DiagnosticSeverity.ERROR
          HighlightSeverity.WARNING, HighlightSeverity.WEAK_WARNING -> DiagnosticSeverity.WARNING
          else -> DiagnosticSeverity.INFO
        },
        message = highlight.description ?: "<no description>",
        location = "${file.psi.name}:${line + 1}:${offset - file.document.getLineStartOffset(line) + 1}",
      )
      true
    }
    return diagnostics
  }

  private fun reference(files: List<InspectionFile>, expression: String): ReferenceObservation {
    val matches = files.flatMap { file -> occurrences(file.psi.text, expression).map { offset -> file to offset } }
    if (matches.isEmpty()) return ReferenceObservation.Unavailable("Expression was not found in generated sources: $expression")
    if (matches.size > 1) return ReferenceObservation.Unavailable("Expression is ambiguous in generated sources: $expression")
    val finalName = expression.substringAfterLast('.')
    if (finalName.isBlank()) return ReferenceObservation.Unavailable("Expression has no final reference: $expression")
    val (file, start) = matches.single()
    val element = file.psi.findElementAt(start + expression.length - finalName.length)?.parent
      ?: return ReferenceObservation.Unavailable("Could not find a reference element for: $expression")
    val target = element.references.firstNotNullOfOrNull { it.resolve() }
    return if (target == null) ReferenceObservation.Unresolved else ReferenceObservation.Resolved(target.text)
  }

  private fun occurrences(text: String, expression: String): Sequence<Int> = sequence {
    var offset = text.indexOf(expression)
    while (offset >= 0) {
      yield(offset)
      offset = text.indexOf(expression, offset + expression.length)
    }
  }

  private fun samePath(left: Path, right: Path): Boolean = try {
    left.toRealPath() == right.toRealPath()
  } catch (_: Exception) {
    left.toAbsolutePath().normalize() == right.toAbsolutePath().normalize()
  }

  private fun writeAndExit(path: Path, response: EditorInspectionResponse) {
    try {
      EditorInspectionProtocol.writeResponse(response, path)
    } finally {
      ApplicationManager.getApplication().invokeLater {
        ApplicationManager.getApplication().exit(true, true, false)
      }
    }
  }

  private data class InspectionFile(
    val psi: PsiFile,
    val document: com.intellij.openapi.editor.Document,
  )

  private sealed interface InspectionAttempt {
    data class Pending(val reason: String) : InspectionAttempt
    data class Completed(val response: EditorInspectionResponse.Completed) : InspectionAttempt
  }

  private companion object {
    const val POLL_INTERVAL_MILLIS: Long = 2_000
    const val READY_TIMEOUT_MILLIS: Long = 10 * 60 * 1_000
  }
}
