package dev.knapshots.testing.editor

import dev.knapshots.testing.snapshot.IsolatedSnapshotRecorder
import java.nio.file.Files
import java.nio.file.Path

/** Explicit source location for environments where the test source cannot be discovered. */
public class EditorSnapshotLocation(public val file: Path, public val line: Int) {
  internal val original: String = Files.readString(file)
}

internal fun editorRecordingMode(): String {
  val mode = System.getProperty("knapshots.editor.record", "none").lowercase(java.util.Locale.ROOT)
  require(mode in setOf("none", "missing", "failed", "all")) { "Invalid editor snapshot recording mode: $mode" }
  return mode
}

internal fun discoverEditorSnapshotLocation(): EditorSnapshotLocation {
  val sourceRoot = Path.of(System.getProperty("user.dir"), "src")
  val roots = if (Files.isDirectory(sourceRoot)) Files.list(sourceRoot).use { directories ->
    directories.filter { Files.isDirectory(it) && (it.fileName.toString() == "test" || it.fileName.toString().endsWith("Test")) }
      .flatMap { directory -> java.util.stream.Stream.of(directory.resolve("kotlin"), directory.resolve("java")) }
      .toList()
  } else emptyList()
  val caller = Throwable().stackTrace.firstNotNullOfOrNull { frame ->
    if (frame.lineNumber <= 0 || frame.fileName == null || frame.className == EditorExpectations::class.java.name) return@firstNotNullOfOrNull null
    val packagePath = frame.className.substringBeforeLast('.', "").replace('.', '/')
    roots.map { it.resolve(packagePath).resolve(frame.fileName) }.firstOrNull(Files::isRegularFile)
      ?.let { EditorSnapshotLocation(it, frame.lineNumber) }
  }
  return requireNotNull(caller) { "Cannot locate the test source for editor recording; provide an EditorSnapshotLocation." }
}

private object EditorRecorder {
  private var recorder: IsolatedSnapshotRecorder? = null
  @Synchronized fun record(location: EditorSnapshotLocation, output: String) {
    val current = recorder ?: run {
      val classpath = requireNotNull(System.getProperty("knapshots.editor.recorderClasspath")) {
        "The isolated editor recording worker is not configured."
      }.split(java.io.File.pathSeparator).filter(String::isNotBlank).map(Path::of)
      IsolatedSnapshotRecorder(classpath).also { worker ->
        recorder = worker
        Runtime.getRuntime().addShutdownHook(Thread { worker.close() })
      }
    }
    current.record(location.file, location.line, "sourceDiagnosticsSnapshot", mapOf("expected" to output), location.original)
  }
}

internal fun recordEditorSnapshot(location: EditorSnapshotLocation, output: String) = EditorRecorder.record(location, output)
