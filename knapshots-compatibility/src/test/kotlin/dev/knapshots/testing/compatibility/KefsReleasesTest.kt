package dev.knapshots.testing.compatibility

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class KefsReleasesTest {
  private fun release(version: String, created: Long, channel: String? = null) = KefsRelease(
    id = created, version = version, file = "plugin.zip", since = "262.1", until = "262.*",
    created = created, approved = true, listed = true, hidden = false, channel = channel,
  )

  @Test fun `selects newest stable compatible release or preferred version`() {
    val versions = listOf(release("1", 1), release("2", 2), release("3", 3, "eap"))
    assertEquals("2", KefsReleases.select("262.123.4", versions).version)
    assertEquals("1", KefsReleases.select("262.123.4", versions, "1").version)
    assertFailsWith<IllegalArgumentException> { KefsReleases.select("263.1", versions) }
  }

  @Test fun `handles wildcard build ranges`() {
    assertTrue(KefsReleases.compatible("262.123.4", "262.1", "262.*"))
    assertFalse(KefsReleases.compatible("263.1", "262.1", "262.*"))
  }
}
