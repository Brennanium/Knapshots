package dev.knapshots.testing.compiler

/** A default snapshot and optional compiler-version-specific snapshots. */
public class VersionedSnapshotExpectations {
  public class Entry internal constructor(val expected: String?, private val location: InlineSnapshotLocation?, private val frames: Array<StackTraceElement>, val call: String) {
    public fun recordingLocation(): InlineSnapshotLocation = location ?: discoverInlineSnapshotLocation(frames)
  }
  private var fallback: Entry? = null
  private val overrides = linkedMapOf<String, Entry>()

  public fun default(expected: String? = null, location: InlineSnapshotLocation? = null) {
    check(fallback == null) { "A default snapshot was already specified." }
    fallback = Entry(expected, location, Throwable().stackTrace, "default")
  }

  public fun versioned(selector: String, expected: String? = null, location: InlineSnapshotLocation? = null) {
    check(selector !in overrides) { "Duplicate version selector: $selector." }
    overrides[selector] = Entry(expected, location, Throwable().stackTrace, "versioned")
  }

  public fun selected(version: String): Entry {
    val default = requireNotNull(fallback) { "A versioned snapshot requires default(...)." }
    val selector = selectCompilerVersion(overrides.keys, version)
    return selector?.let(overrides::getValue) ?: default
  }
}
