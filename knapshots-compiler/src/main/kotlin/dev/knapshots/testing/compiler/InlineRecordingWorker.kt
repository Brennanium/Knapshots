package dev.knapshots.testing.compiler

import dev.knapshots.testing.snapshot.readSnapshotFrame
import dev.knapshots.testing.snapshot.writeSnapshotFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path

/** Internal process entry point; never put compiler classes on an IDE's classpath to use it. */
public object InlineRecordingWorker {
  @JvmStatic public fun main(args: Array<String>) {
    val input = DataInputStream(BufferedInputStream(System.`in`))
    val output = DataOutputStream(BufferedOutputStream(System.out))
    while (input.readBoolean()) {
      val updateCount = input.readInt()
      require(updateCount in 1..1000) { "Invalid snapshot update count." }
      val updates = List(updateCount) {
        val file = Path.of(input.readSnapshotFrame())
        val line = input.readInt()
        val assertion = input.readSnapshotFrame()
        val original = input.readSnapshotFrame()
        val count = input.readInt()
        require(count in 1..1000) { "Invalid snapshot count." }
        val values = buildMap { repeat(count) { put(input.readSnapshotFrame(), input.readSnapshotFrame()) } }
        InlineSnapshotUpdate(InlineSnapshotLocation(file, line), assertion, values, original)
      }
      try {
        recordInlineSnapshotBatch(updates, "0.0.0")
        output.writeBoolean(true)
        output.writeSnapshotFrame("")
      } catch (error: Exception) {
        output.writeBoolean(false)
        output.writeSnapshotFrame(error.message ?: error.javaClass.name)
      }
      output.flush()
    }
  }
}
