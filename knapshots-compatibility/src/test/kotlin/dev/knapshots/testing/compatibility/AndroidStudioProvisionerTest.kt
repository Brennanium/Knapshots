package dev.knapshots.testing.compatibility

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AndroidStudioProvisionerTest {
  @Test fun `uses verified cache and provisions an IDE`(@TempDir directory: Path) {
    val archive = directory.resolve("2026.2.2-linux.tar.gz")
    archive(archive, "android-studio/bin/studio.sh")
    val release = release(archive)
    val value = AndroidStudioProvisioner(directory).useIde(release, IdePlatform.LINUX_X64) { ide ->
      Files.readString(ide.resolve("bin/studio.sh"))
    }
    assertEquals("ok", value)
  }

  @Test fun `rejects archive traversal`(@TempDir directory: Path) {
    val archive = directory.resolve("2026.2.2-linux.tar.gz")
    archive(archive, "../escape")
    assertFailsWith<IllegalArgumentException> {
      AndroidStudioProvisioner(directory).useIde(release(archive), IdePlatform.LINUX_X64) { }
    }
  }

  private fun release(path: Path): AndroidStudioRelease {
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    return AndroidStudioRelease("Studio", "2026.2.2", "build", "https://example.test/unused", digest)
  }

  private fun archive(path: Path, name: String) {
    TarArchiveOutputStream(GzipCompressorOutputStream(Files.newOutputStream(path))).use { tar ->
      val bytes = "ok".toByteArray()
      tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
      tar.write(bytes)
      tar.closeArchiveEntry()
    }
  }
}
