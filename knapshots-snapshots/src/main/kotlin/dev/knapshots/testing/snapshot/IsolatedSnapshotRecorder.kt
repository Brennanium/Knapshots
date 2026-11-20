package dev.knapshots.testing.snapshot

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** A persistent worker keeps recorder state while isolating compiler classes from the caller. */
public class IsolatedSnapshotRecorder(workerClasspath: List<Path>) : AutoCloseable {
  private val process: Process
  private val input: DataInputStream
  private val output: DataOutputStream

  init {
    require(workerClasspath.isNotEmpty()) { "A recorder worker classpath is required." }
    val javaExecutable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    process = ProcessBuilder(javaExecutable.toString(), "-cp", workerClasspath.joinToString(java.io.File.pathSeparator),
      "dev.knapshots.testing.compiler.InlineRecordingWorker").redirectError(ProcessBuilder.Redirect.INHERIT).start()
    input = DataInputStream(BufferedInputStream(process.inputStream))
    output = DataOutputStream(BufferedOutputStream(process.outputStream))
  }

  /** Record one assertion. The caller must supply the source read before its test started. */
  @Synchronized public fun record(file: Path, line: Int, assertionName: String, values: Map<String, String>, expectedSource: String) {
    require(line > 0 && values.isNotEmpty())
    output.writeBoolean(true)
    output.writeFrame(file.toAbsolutePath().normalize().toString())
    output.writeInt(line)
    output.writeFrame(assertionName)
    output.writeFrame(expectedSource)
    output.writeInt(values.size)
    values.forEach { (name, value) -> output.writeFrame(name); output.writeFrame(value) }
    output.flush()
    val succeeded = try { input.readBoolean() } catch (error: java.io.EOFException) {
      throw IllegalStateException("Snapshot recording worker exited unexpectedly (exit=${if (process.isAlive) "running" else process.exitValue()}).", error)
    }
    val message = input.readFrame()
    if (!succeeded) throw IllegalStateException("Snapshot recording failed: $message")
  }

  @Synchronized override fun close() {
    if (process.isAlive) {
      output.writeBoolean(false)
      output.flush()
      output.close()
      if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor()
      }
    }
    input.close()
  }
}

/** Length-prefixed UTF-8 avoids the 64 KiB limit of DataOutputStream.writeUTF. */
public fun DataOutputStream.writeSnapshotFrame(value: String) {
  val bytes = value.toByteArray(StandardCharsets.UTF_8)
  writeInt(bytes.size)
  write(bytes)
}

public fun DataInputStream.readSnapshotFrame(): String {
  val size = readInt()
  require(size in 0..64 * 1024 * 1024) { "Invalid snapshot frame length: $size" }
  return String(ByteArray(size).also(::readFully), StandardCharsets.UTF_8)
}

private fun DataOutputStream.writeFrame(value: String) = writeSnapshotFrame(value)
private fun DataInputStream.readFrame() = readSnapshotFrame()
