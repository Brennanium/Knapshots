package dev.knapshots.testing.ide

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Validates the generated Gradle project before an IDE attempts its first import. */
internal class GradlePreflightLauncher(
  private val delegate: EditorProcessLauncher,
  private val javaHome: Path,
  private val timeout: Duration = Duration.ofMinutes(5),
  private val command: (MaterializedEditorProject) -> List<String> = { project ->
    listOf(project.directory.resolve("gradlew").toString(), "help", "--no-daemon", "--no-configuration-cache", "--stacktrace")
  },
) : EditorProcessLauncher {
  override fun launch(project: MaterializedEditorProject): EditorProcessResult {
    val log = project.directory.resolve(".knapshots/gradle-preflight.log")
    Files.createDirectories(log.parent)
    val process = ProcessBuilder(command(project))
      .directory(project.directory.toFile())
      .redirectErrorStream(true)
      .redirectOutput(log.toFile())
      .apply { environment()["JAVA_HOME"] = javaHome.toString() }
      .start()
    val finished = try { process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) }
    catch (interrupted: InterruptedException) {
      stop(process); Thread.currentThread().interrupt(); throw interrupted
    }
    if (!finished) {
      stop(process)
      return EditorProcessResult(-1, "Gradle preflight timed out after $timeout. Log: $log")
    }
    if (process.exitValue() != 0) {
      return EditorProcessResult(process.exitValue(), "Gradle preflight failed. Log: $log")
    }
    return delegate.launch(project)
  }

  private fun stop(process: Process) {
    val descendants = process.descendants().use { it.toList() }.asReversed()
    descendants.forEach { it.destroyForcibly() }
    process.destroyForcibly()
  }
}
