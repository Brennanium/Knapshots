package dev.knapshots.testing.compatibility

/** A project-specific regression suite for one concrete compiler version. */
public fun interface CompilerCompatibilityDriver {
  public fun run(kotlinVersion: String): CompatibilityResult
}

public enum class CompatibilityStatus { PASSED, FAILED, NOT_RUN }

/** A result may report separate JVM, Native, and consumer checks. */
public data class CompatibilityResult(
  val checks: Map<String, CompatibilityStatus>,
  val detail: String? = null,
) {
  init { require(checks.isNotEmpty()) { "A compatibility result must describe at least one check." } }
}

public data class CompilerCompatibilityEntry(
  val version: String,
  val result: CompatibilityResult,
)

public data class CompilerCompatibilityReport(
  val selections: List<KotlinVersionSelection>,
  val entries: List<CompilerCompatibilityEntry>,
) {
  public val successful: Boolean get() = entries.all { entry ->
    entry.result.checks.values.any { it == CompatibilityStatus.PASSED } &&
      entry.result.checks.values.none { it == CompatibilityStatus.FAILED }
  }
}

/** Resolves every selector first, then runs each distinct version once, including after failures. */
public class CompilerCompatibility(
  private val versions: KotlinVersions = KotlinVersions(),
  private val driver: CompilerCompatibilityDriver,
) {
  public fun run(selectors: List<String>): CompilerCompatibilityReport {
    val selections = versions.resolve(selectors)
    val entries = selections.map { it.version }.distinct().map { version ->
      val result = try {
        driver.run(version)
      } catch (error: Exception) {
        CompatibilityResult(mapOf("driver" to CompatibilityStatus.FAILED), error.message ?: error.javaClass.name)
      }
      CompilerCompatibilityEntry(version, result)
    }
    return CompilerCompatibilityReport(selections, entries)
  }
}
