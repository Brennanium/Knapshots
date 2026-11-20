package dev.knapshots.testing.editor

/** Selects exact IDE builds before a single matching build-family selector. */
internal fun selectEditorBuild(selectors: Set<String>, build: String): String? {
  val buildMatch = Regex("^([A-Za-z]+)-([0-9]+(?:\\.[0-9]+)*)$").matchEntire(build)
    ?: throw IllegalArgumentException("Invalid IDE build identifier: $build")
  val product = buildMatch.groupValues[1].uppercase()
  val numbers = buildMatch.groupValues[2].split('.')
  val parsed = selectors.associateWith { selector ->
    Regex("^([A-Za-z]+)-([0-9]+(?:\\.[0-9]+)*)(\\.\\*)?$").matchEntire(selector)
      ?: throw IllegalArgumentException("Invalid IDE build selector: $selector; use a product-prefixed build such as AI-262.*.")
  }
  val exact = parsed.filter { (_, match) -> match.groupValues[3].isEmpty() &&
    match.groupValues[1].uppercase() == product && match.groupValues[2].split('.') == numbers }.keys
  require(exact.size <= 1) { "Ambiguous exact IDE build selectors for $build: ${exact.sorted().joinToString()}." }
  exact.singleOrNull()?.let { return it }
  val matches = parsed.filter { (_, match) ->
    val prefix = match.groupValues[2].split('.')
    match.groupValues[3].isNotEmpty() && match.groupValues[1].uppercase() == product &&
      numbers.size > prefix.size && numbers.take(prefix.size) == prefix
  }.keys
  require(matches.size <= 1) { "Ambiguous IDE build selectors for $build: ${matches.sorted().joinToString()}." }
  return matches.singleOrNull()
}

/** Source-context diagnostic snapshots selected using the actual IDE build. */
public class EditorVersionedSnapshots {
  internal data class Entry(val expected: String?, val location: EditorSnapshotLocation?, val frames: Array<StackTraceElement>, val call: String)
  private var fallback: Entry? = null
  private val overrides = linkedMapOf<String, Entry>()

  public fun default(expected: String? = null, location: EditorSnapshotLocation? = null) {
    check(fallback == null) { "A default snapshot was already specified." }
    fallback = Entry(expected, location, Throwable().stackTrace, "default")
  }

  public fun versioned(selector: String, expected: String? = null, location: EditorSnapshotLocation? = null) {
    check(selector !in overrides) { "Duplicate IDE build selector: $selector." }
    overrides[selector] = Entry(expected, location, Throwable().stackTrace, "versioned")
  }

  internal fun selected(build: String?): Entry {
    val default = requireNotNull(fallback) { "A versioned editor snapshot requires default(...)." }
    if (overrides.isEmpty()) return default
    val actual = requireNotNull(build) { "The editor host did not provide an IDE build; versioned editor assertions require the actual build." }
    return selectEditorBuild(overrides.keys, actual)?.let(overrides::getValue) ?: default
  }
}
