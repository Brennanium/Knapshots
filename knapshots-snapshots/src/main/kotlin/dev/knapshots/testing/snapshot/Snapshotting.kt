package dev.knapshots.testing.snapshot

/** Compares snapshots of a particular format. A null message means that they match. */
public class Diffing<Format>(
  public val difference: (expected: Format, actual: Format) -> String?,
)

/** Captures a value in a format that can be compared independently of its origin. */
public class Snapshotting<Value, Format>(
  public val diffing: Diffing<Format>,
  public val snapshot: (Value) -> Format,
) {
  /** Adapts this strategy to a new input while retaining its comparison behavior. */
  public fun <NewValue> pullback(transform: (NewValue) -> Value): Snapshotting<NewValue, Format> =
    Snapshotting(diffing) { value -> snapshot(transform(value)) }
}

public data class SnapshotDifference<Format>(
  val expected: Format,
  val actual: Format,
  val message: String,
)

/** Captures exactly once and returns a structured mismatch, or null when values match. */
public fun <Value, Format> compareSnapshot(
  value: Value,
  expected: Format,
  using: Snapshotting<Value, Format>,
): SnapshotDifference<Format>? {
  val actual = using.snapshot(value)
  val message = using.diffing.difference(expected, actual) ?: return null
  return SnapshotDifference(expected, actual, message)
}

/**
 * Captures [value] once and fails when it differs from [expected].
 *
 * This is the in-memory assertion primitive. File-backed and inline snapshot storage belong to
 * higher-level recorders so ordinary assertions never mutate a test's expectations.
 */
public fun <Value, Format> assertSnapshot(
  value: Value,
  expected: Format,
  using: Snapshotting<Value, Format>,
  name: String? = null,
) {
  val difference = compareSnapshot(value, expected, using) ?: return
  val label = name?.let { " '$it'" }.orEmpty()
  throw AssertionError(
    "Snapshot$label differs: ${difference.message}\n" +
      "Expected:\n${difference.expected}\n" +
      "Actual:\n${difference.actual}",
  )
}

/** Exact text comparison; callers choose how to normalize and render their output. */
public val textSnapshotting: Snapshotting<String, String> = Snapshotting(
  diffing = Diffing { expected, actual -> if (expected == actual) null else "Text snapshots differ" },
  snapshot = { it },
)

/** Removes snapshot indentation without treating embedded carriage returns as line separators. */
public fun trimSnapshotIndent(value: String): String {
  if ('\r' !in value) return value.trimIndent()
  var marker = "\u0000"
  while (marker in value) marker += "\u0000"
  return value.replace("\r", marker).trimIndent().replace(marker, "\r")
}
