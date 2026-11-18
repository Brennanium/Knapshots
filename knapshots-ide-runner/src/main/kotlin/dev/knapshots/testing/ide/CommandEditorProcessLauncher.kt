package dev.knapshots.testing.ide

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Launches an externally supplied IDE command with a materialized editor project as its working
 * directory. Callers may use an IDE launcher directly or wrap it in a platform display tool such
 * as `xvfb-run`; this class intentionally has no Android Studio or OS-specific behavior.
 */
public class CommandEditorProcessLauncher(
  private val command: (MaterializedEditorProject) -> List<String>,
  private val timeout: Duration = Duration.ofMinutes(5),
  private val environment: Map<String, String> = emptyMap(),
) : EditorProcessLauncher {
  init {
    require(!timeout.isNegative && !timeout.isZero) { "IDE process timeout must be positive." }
  }

  override fun launch(project: MaterializedEditorProject): EditorProcessResult {
    val arguments = command(project)
    require(arguments.isNotEmpty()) { "IDE process command must not be empty." }
    val log = project.directory.resolve(".knapshots/ide-process.log")
    Files.createDirectories(checkNotNull(log.parent))
    val process = ProcessBuilder(arguments)
      .directory(project.directory.toFile())
      .redirectErrorStream(true)
      .redirectOutput(log.toFile())
      .apply {
        environment().putAll(environment)
        environment()["KNAPSHOTS_EDITOR_PROJECT"] = project.directory.toString()
        environment()["KNAPSHOTS_EDITOR_REQUEST"] = project.requestPath.toString()
        environment()["KNAPSHOTS_EDITOR_RESPONSE"] = project.responsePath.toString()
      }
      .start()

    val finished = try {
      process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
      terminateTree(process)
      Thread.currentThread().interrupt()
      throw interrupted
    }
    if (!finished) {
      terminateTree(process)
      return EditorProcessResult(
        exitCode = -1,
        summary = "IDE process timed out after $timeout. Log: $log",
      )
    }
    val exitCode = process.exitValue()
    return EditorProcessResult(
      exitCode = exitCode,
      summary = if (exitCode == 0) null else "IDE process exited with code $exitCode. Log: $log",
    )
  }

  private fun terminateTree(process: Process) {
    // Capture descendants before stopping the parent: once orphaned they may no longer be
    // discoverable through ProcessHandle. Only processes launched by this invocation are targeted.
    val children = process.descendants().use { it.toList() }.asReversed()
    children.forEach { it.destroy() }
    process.destroy()
    try {
      process.waitFor(5, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
    children.filter { it.isAlive }.forEach { it.destroyForcibly() }
    if (process.isAlive) process.destroyForcibly()
  }
}
