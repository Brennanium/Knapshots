package dev.knapshots.testing.editor

/** Android Studio release information associated with one exact, product-prefixed IDE build. */
public data class EditorRelease(val build: String, val version: String, val name: String)

/** Selects exact builds/releases, then the uniquely narrowest matching family. */
internal fun selectEditorBuild(selectors: Set<String>, build: String, release: () -> EditorRelease? = { null }): String? {
  val buildMatch = Regex("^([A-Za-z]+)-([0-9]+(?:\\.[0-9]+)*)$").matchEntire(build)
    ?: throw IllegalArgumentException("Invalid IDE build identifier: $build")
  val product = buildMatch.groupValues[1].uppercase()
  val numbers = buildMatch.groupValues[2].split('.')
  val buildPattern = Regex("^([A-Za-z]+)-([0-9]+(?:\\.[0-9]+)*)(\\.\\*)?$")
  val releasePattern = Regex("^([0-9]+(?:\\.[0-9]+)+)(\\.\\*)?$")
  val namePattern = Regex("^[A-Za-z]+(?: [0-9]+)?$")
  val parsed = selectors.associateWith { selector ->
    val buildSelector = buildPattern.matchEntire(selector)
    val releaseSelector = releasePattern.matchEntire(selector)
    require(buildSelector != null || releaseSelector != null || namePattern.matches(selector)) {
      "Invalid IDE selector: $selector; use a build, release version, or Android Studio release name."
    }
    buildSelector to releaseSelector
  }
  val needsRelease = parsed.any { (_, patterns) -> patterns.first == null }
  val metadata = if (needsRelease && product == "AI") requireNotNull(release()) {
    "Android Studio release metadata is unavailable for $build; provide verified release metadata or use a build selector."
  }.also { require(it.build.equals(build, ignoreCase = true)) { "Release metadata for ${it.build} does not match IDE build $build." } } else null
  val matching = parsed.mapNotNull { (selector, patterns) ->
    val (buildSelector, releaseSelector) = patterns
    when {
      buildSelector != null -> {
        val prefix = buildSelector.groupValues[2].split('.')
        val family = buildSelector.groupValues[3].isNotEmpty()
        val same = buildSelector.groupValues[1].uppercase() == product
        if (same && if (family) numbers.size > prefix.size && numbers.take(prefix.size) == prefix else numbers == prefix) selector to !family else null
      }
      releaseSelector != null -> {
        val current = metadata?.version?.split('.').orEmpty()
        val prefix = releaseSelector.groupValues[1].split('.')
        val family = releaseSelector.groupValues[2].isNotEmpty()
        if (metadata != null && if (family) current.size > prefix.size && current.take(prefix.size) == prefix else current == prefix) selector to !family else null
      }
      metadata != null -> {
        val name = metadata.name.substringBefore('|').trim().removePrefix("Android Studio ").trim()
        if (name.equals(selector, true) || name.startsWith("$selector ", true)) selector to false else null
      }
      else -> null
    }
  }
  val exact = matching.filter { it.second }.map { it.first }
  require(exact.size <= 1) { "Ambiguous exact IDE selectors for $build: ${exact.sorted().joinToString()}." }
  exact.singleOrNull()?.let { return it }
  val families = matching.map { it.first }
  if (families.size <= 1) return families.singleOrNull()
  fun isNarrower(candidate: String, other: String): Boolean {
    val candidateBuild = parsed.getValue(candidate).first
    val otherBuild = parsed.getValue(other).first
    val candidateRelease = parsed.getValue(candidate).second
    val otherRelease = parsed.getValue(other).second
    if (candidateBuild != null && otherBuild != null) {
      val prefix = candidateBuild.groupValues[2].split('.')
      val broader = otherBuild.groupValues[2].split('.')
      return candidateBuild.groupValues[1].equals(otherBuild.groupValues[1], true) &&
        prefix.size > broader.size && prefix.take(broader.size) == broader
    }
    if (candidateRelease != null && otherRelease != null) {
      val prefix = candidateRelease.groupValues[1].split('.')
      val broader = otherRelease.groupValues[1].split('.')
      return prefix.size > broader.size && prefix.take(broader.size) == broader
    }
    if (candidateBuild == null && otherBuild == null && candidateRelease == null && otherRelease == null) {
      return candidate.startsWith("$other ", ignoreCase = true)
    }
    return false
  }
  val narrowest = families.filter { candidate -> families.all { other -> candidate == other || isNarrower(candidate, other) } }
  require(narrowest.size == 1) { "Ambiguous IDE selectors for $build: ${families.sorted().joinToString()}." }
  return narrowest.single()
}

/** Source-context diagnostic snapshots selected using the actual IDE build and release. */
public class EditorVersionedSnapshots {
  internal data class Entry(val expected: String?, val location: EditorSnapshotLocation?, val frames: Array<StackTraceElement>, val call: String)
  private var fallback: Entry? = null
  private val overrides = linkedMapOf<String, Entry>()

  public fun default(expected: String? = null, location: EditorSnapshotLocation? = null) {
    check(fallback == null) { "A default snapshot was already specified." }
    fallback = Entry(expected, location, Throwable().stackTrace, "default")
  }

  public fun versioned(selector: String, expected: String? = null, location: EditorSnapshotLocation? = null) {
    check(selector !in overrides) { "Duplicate IDE selector: $selector." }
    overrides[selector] = Entry(expected, location, Throwable().stackTrace, "versioned")
  }

  internal fun selected(build: String?, release: () -> EditorRelease?): Entry {
    val default = requireNotNull(fallback) { "A versioned editor snapshot requires default(...)." }
    if (overrides.isEmpty()) return default
    val actual = requireNotNull(build) { "The editor host did not provide an IDE build; versioned editor assertions require the actual build." }
    return selectEditorBuild(overrides.keys, actual, release)?.let(overrides::getValue) ?: default
  }
}
