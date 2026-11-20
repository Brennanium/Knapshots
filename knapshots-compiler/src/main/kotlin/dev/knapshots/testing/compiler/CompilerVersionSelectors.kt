package dev.knapshots.testing.compiler

import java.math.BigInteger

/** Exact versions win; otherwise select the uniquely narrowest matching selector. */
public fun selectCompilerVersion(selectors: Set<String>, compilerVersion: String): String? {
  val version = CompilerVersion.parse(compilerVersion)
  val patterns = selectors.associateWith(::parseSelector)
  if (compilerVersion in selectors) return compilerVersion
  val matches = patterns.filterValues { it.matches(version) }
  if (matches.size <= 1) return matches.keys.singleOrNull()

  // Choose a selector only when it is a strict subset of every other match.
  // Overlaps that extend outside one another remain ambiguous.
  val narrowed = matches.filter { (selector, pattern) ->
    matches.all { (otherSelector, other) ->
      selector == otherSelector || pattern.isNarrowerThan(other)
    }
  }.keys
  require(narrowed.size == 1) {
    "Ambiguous compiler snapshot selectors for $compilerVersion: ${matches.keys.sorted().joinToString()}."
  }
  return narrowed.single()
}

private data class Bound(val version: CompilerVersion, val inclusive: Boolean)
private data class VersionInterval(val lower: Bound? = null, val upper: Bound? = null) {
  fun intersect(other: VersionInterval): VersionInterval = VersionInterval(
    listOfNotNull(lower, other.lower).maxWithOrNull { a, b ->
      val order = a.version.compareTo(b.version)
      if (order != 0) order else a.inclusive.compareTo(b.inclusive) * -1
    },
    listOfNotNull(upper, other.upper).minWithOrNull { a, b ->
      val order = a.version.compareTo(b.version)
      if (order != 0) order else a.inclusive.compareTo(b.inclusive)
    },
  )

  fun isSubsetOf(other: VersionInterval): Boolean {
    val lowerContained = other.lower == null || (lower != null &&
      (lower.version > other.lower.version || (lower.version.compareTo(other.lower.version) == 0 && (!lower.inclusive || other.lower.inclusive))))
    val upperContained = other.upper == null || (upper != null &&
      (upper.version < other.upper.version || (upper.version.compareTo(other.upper.version) == 0 && (!upper.inclusive || other.upper.inclusive))))
    return lowerContained && upperContained
  }

  fun strictlyInside(other: VersionInterval): Boolean = isSubsetOf(other) && !other.isSubsetOf(this)
}

private data class VersionSelector(val interval: VersionInterval, val exact: String? = null) {
  fun matches(version: CompilerVersion): Boolean {
    if (exact != null) return version.original == exact
    val low = interval.lower
    val high = interval.upper
    return (low == null || version > low.version || (low.inclusive && version.compareTo(low.version) == 0)) &&
      (high == null || version < high.version || (high.inclusive && version.compareTo(high.version) == 0))
  }

  fun isNarrowerThan(other: VersionSelector): Boolean = interval.strictlyInside(other.interval)
}

private fun familyInterval(major: BigInteger, minor: BigInteger): VersionInterval = VersionInterval(
  Bound(CompilerVersion.parse("$major.$minor.0-0"), true),
  Bound(CompilerVersion.parse("$major.${minor + BigInteger.ONE}.0-0"), false),
)

private fun parseSelector(selector: String): VersionSelector {
  val wildcard = Regex("^(\\d+)\\.(\\d+)\\.\\*$").matchEntire(selector)
  if (wildcard != null) {
    return VersionSelector(familyInterval(wildcard.groupValues[1].toBigInteger(), wildcard.groupValues[2].toBigInteger()))
  }
  val parts = selector.trim().split(Regex("\\s+"))
  var interval = VersionInterval()
  for (part in parts) {
    val match = Regex("^(>=|<=|>|<)(.+)$").matchEntire(part)
    if (match == null) {
      require(parts.size == 1) { "Invalid compiler version selector: $selector" }
      CompilerVersion.parse(part)
      return VersionSelector(interval, part)
    }
    val specified = CompilerVersion.parse(match.groupValues[2])
    // A final-release boundary for < and >= means the start of that release's
    // entire family, including its prereleases. Explicit prerelease boundaries
    // retain their ordinary ordering.
    val boundary = if (specified.qualifier.isEmpty() && match.groupValues[1] in setOf("<", ">="))
      CompilerVersion.parse("${specified.major}.${specified.minor}.${specified.patch}-0") else specified
    val constraint = when (match.groupValues[1]) {
      ">=" -> VersionInterval(lower = Bound(boundary, true))
      ">" -> VersionInterval(lower = Bound(boundary, false))
      "<=" -> VersionInterval(upper = Bound(boundary, true))
      else -> VersionInterval(upper = Bound(boundary, false))
    }
    interval = interval.intersect(constraint)
  }
  return VersionSelector(interval)
}

/** Kotlin-style version ordering: numeric core and qualifier components; final follows previews. */
private data class CompilerVersion(
  val original: String,
  val major: BigInteger,
  val minor: BigInteger,
  val patch: BigInteger,
  val qualifier: List<String>,
) : Comparable<CompilerVersion> {
  override fun compareTo(other: CompilerVersion): Int {
    for ((left, right) in listOf(major to other.major, minor to other.minor, patch to other.patch)) {
      val result = left.compareTo(right)
      if (result != 0) return result
    }
    if (qualifier.isEmpty() || other.qualifier.isEmpty()) return when {
      qualifier.isEmpty() && other.qualifier.isEmpty() -> 0
      qualifier.isEmpty() -> 1
      else -> -1
    }
    for (index in 0 until minOf(qualifier.size, other.qualifier.size)) {
      val left = qualifier[index]
      val right = other.qualifier[index]
      val leftNumber = left.toBigIntegerOrNull()
      val rightNumber = right.toBigIntegerOrNull()
      val result = when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        leftNumber != null -> -1
        rightNumber != null -> 1
        else -> left.compareTo(right)
      }
      if (result != 0) return result
    }
    return qualifier.size.compareTo(other.qualifier.size)
  }

  companion object {
    fun parse(value: String): CompilerVersion {
      val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*))?$").matchEntire(value)
        ?: throw IllegalArgumentException("Invalid compiler version: $value")
      val qualifier = match.groupValues[4]
      return CompilerVersion(value, match.groupValues[1].toBigInteger(), match.groupValues[2].toBigInteger(),
        match.groupValues[3].toBigInteger(), if (qualifier.isEmpty()) emptyList() else qualifier.split('.', '-'))
    }
  }
}
