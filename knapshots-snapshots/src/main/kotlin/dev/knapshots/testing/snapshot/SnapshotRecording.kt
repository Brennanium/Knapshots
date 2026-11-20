package dev.knapshots.testing.snapshot

/** Selects when an inline snapshot is written. */
public enum class SnapshotRecording { NONE, MISSING, FAILED, ALL }

/** Applies the same recording policy regardless of the source of a snapshot. */
public fun shouldRecordSnapshot(
  recording: SnapshotRecording,
  missing: Boolean,
  differs: Boolean,
  versionMissing: Boolean = false,
): Boolean = when (recording) {
  SnapshotRecording.NONE -> false
  SnapshotRecording.MISSING -> missing || versionMissing
  SnapshotRecording.FAILED -> differs
  SnapshotRecording.ALL -> true
}

/** Whether a recording mode could write this expectation; useful for capturing its source safely. */
public fun mayRecordSnapshot(recording: SnapshotRecording, missing: Boolean, versionMissing: Boolean = false): Boolean =
  when (recording) {
    SnapshotRecording.NONE -> false
    SnapshotRecording.MISSING -> missing || versionMissing
    SnapshotRecording.FAILED, SnapshotRecording.ALL -> true
  }
