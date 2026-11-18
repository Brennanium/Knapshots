package dev.knapshots.testing.compatibility

/** Relevant Marketplace update metadata; callers retain control of how metadata is fetched. */
public data class KefsRelease(
  val id: Long,
  val version: String,
  val file: String,
  val since: String?,
  val until: String?,
  val created: Long,
  val approved: Boolean,
  val listed: Boolean,
  val hidden: Boolean,
  val channel: String?,
)

public object KefsReleases {
  public fun select(build: String, releases: List<KefsRelease>, preferred: String? = null): KefsRelease =
    releases.filter { release ->
      release.approved && release.listed && !release.hidden && release.channel.isNullOrEmpty() &&
        (preferred == null || preferred == release.version) && compatible(build, release.since, release.until)
    }.maxWithOrNull(compareBy<KefsRelease> { it.created }.thenBy { it.id })
      ?: throw IllegalArgumentException("No compatible stable KEFS release for IDE build $build${preferred?.let { " and version $it" } ?: ""}.")

  public fun compatible(build: String, since: String?, until: String?): Boolean {
    val actual = parts(build.removePrefix("AI-"), false)
    return (since.isNullOrBlank() || compare(actual, parts(since, false)) >= 0) &&
      (until.isNullOrBlank() || compare(actual, parts(until, true)) <= 0)
  }

  private fun parts(value: String, upper: Boolean): List<Long> = value.removePrefix("AI-").split('.').map {
    if (it == "*" && upper) Long.MAX_VALUE else it.toLong()
  }

  private fun compare(a: List<Long>, b: List<Long>): Int {
    for (index in 0 until maxOf(a.size, b.size)) {
      val comparison = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
      if (comparison != 0) return comparison
    }
    return 0
  }
}
