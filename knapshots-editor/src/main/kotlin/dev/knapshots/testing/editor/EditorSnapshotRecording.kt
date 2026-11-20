package dev.knapshots.testing.editor

import dev.knapshots.testing.snapshot.IsolatedSnapshotRecorder
import dev.knapshots.testing.snapshot.SnapshotRecordUpdate
import dev.knapshots.testing.snapshot.SnapshotRecording
import java.nio.file.Files
import java.nio.file.Path

/** Explicit source location for environments where the test source cannot be discovered. */
public class EditorSnapshotLocation(public val file: Path, public val line: Int) {
  internal val original: String = Files.readString(file)
}

public fun configuredEditorRecording(): SnapshotRecording {
  val mode = System.getProperty("knapshots.editor.record", "missing").uppercase(java.util.Locale.ROOT)
  return try { SnapshotRecording.valueOf(mode) }
  catch (_: IllegalArgumentException) { error("Invalid editor snapshot recording mode: $mode") }
}

internal fun discoverEditorSnapshotLocation(frames: Array<StackTraceElement> = Throwable().stackTrace): EditorSnapshotLocation {
  val sourceRoot = Path.of(System.getProperty("user.dir"), "src")
  val roots = if (Files.isDirectory(sourceRoot)) Files.list(sourceRoot).use { directories ->
    directories.filter { Files.isDirectory(it) && (it.fileName.toString() == "test" || it.fileName.toString().endsWith("Test")) }
      .flatMap { directory -> java.util.stream.Stream.of(directory.resolve("kotlin"), directory.resolve("java")) }
      .toList()
  } else emptyList()
  val caller = frames.firstNotNullOfOrNull { frame ->
    if (frame.lineNumber <= 0 || frame.fileName == null || frame.className == EditorExpectations::class.java.name || frame.className == QuickFixExpectations::class.java.name) return@firstNotNullOfOrNull null
    val packagePath = frame.className.substringBeforeLast('.', "").replace('.', '/')
    roots.map { it.resolve(packagePath).resolve(frame.fileName) }.firstOrNull(Files::isRegularFile)
      ?.let { EditorSnapshotLocation(it, frame.lineNumber) }
  }
  return requireNotNull(caller) { "Cannot locate the test source for editor recording; provide an EditorSnapshotLocation." }
}

private object EditorRecorder {
  private var recorder: IsolatedSnapshotRecorder? = null
  @Synchronized fun record(updates: List<SnapshotRecordUpdate>) {
    val current = recorder ?: run {
      val classpath = requireNotNull(System.getProperty("knapshots.editor.recorderClasspath")) {
        "The isolated editor recording worker is not configured."
      }.split(java.io.File.pathSeparator).filter(String::isNotBlank).map(Path::of)
      IsolatedSnapshotRecorder(classpath).also { worker ->
        recorder = worker
        Runtime.getRuntime().addShutdownHook(Thread { worker.close() })
      }
    }
    current.recordBatch(updates)
  }
}

internal fun editorSnapshotUpdate(location: EditorSnapshotLocation, output: String, assertionName: String, argumentName: String = "expected") =
  SnapshotRecordUpdate(location.file, location.line, assertionName, mapOf(argumentName to output), location.original)

internal fun recordEditorSnapshot(location: EditorSnapshotLocation, output: String, assertionName: String = "sourceDiagnosticsSnapshot", argumentName: String = "expected") =
  EditorRecorder.record(listOf(editorSnapshotUpdate(location, output, assertionName, argumentName)))

internal fun recordEditorSnapshots(updates: List<SnapshotRecordUpdate>) = EditorRecorder.record(updates)
