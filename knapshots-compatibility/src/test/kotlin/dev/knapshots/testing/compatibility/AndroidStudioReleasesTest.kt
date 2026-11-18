package dev.knapshots.testing.compatibility

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class AndroidStudioReleasesTest {
  private fun item(version: String, channel: String, digest: String = "a".repeat(64)) = """
    <item><name>Android Studio $version</name><version>$version</version><build>AI-$version</build>
    <channel>$channel</channel><download><link>https://example.test/studio-linux.tar.gz</link>
    <checksum>$digest</checksum></download></item>
  """.trimIndent()

  @Test fun `latest stable ignores preview and compares numbers`() {
    val xml = "<releases>${item("2026.2.9", "Stable")}${item("2026.2.10", "Patch")}${item("2027.1.1", "Canary")}</releases>"
    assertEquals("2026.2.10", AndroidStudioReleases.select("latest-stable", xml.byteInputStream(), IdePlatform.LINUX_X64).version)
  }

  @Test fun `rejects missing checksum and duplicate matches`() {
    assertFailsWith<IllegalArgumentException> {
      AndroidStudioReleases.select("2026.2.2", "<releases>${item("2026.2.2", "Stable", "bad")}</releases>".byteInputStream(), IdePlatform.LINUX_X64)
    }
    assertFailsWith<IllegalArgumentException> {
      AndroidStudioReleases.select("2026.2.2", "<releases>${item("2026.2.2", "Stable")}${item("2026.2.2", "Patch")}</releases>".byteInputStream(), IdePlatform.LINUX_X64)
    }
  }
}
