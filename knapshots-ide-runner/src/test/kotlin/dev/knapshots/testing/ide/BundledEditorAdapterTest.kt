package dev.knapshots.testing.ide

import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundledEditorAdapterTest {
  @Test fun `extracts the adapter resource as an IDE plugin archive`() {
    val bytes = ByteArrayOutputStream().also { buffer ->
      ZipOutputStream(buffer).use { zip ->
        zip.putNextEntry(ZipEntry("fixture/adapter.txt"))
        zip.write("adapter test fixture".toByteArray())
      }
    }.toByteArray()
    val archive = extractEditorAdapter(ByteArrayInputStream(bytes))
    try {
      ZipFile(archive.toFile()).use { zip ->
        assertEquals("adapter test fixture", zip.getInputStream(zip.getEntry("fixture/adapter.txt")).bufferedReader().readText())
      }
    } finally {
      Files.deleteIfExists(archive)
    }
  }
}
