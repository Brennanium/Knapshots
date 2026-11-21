package dev.knapshots.testing.compatibility

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IdeKotlinVersionTest {
  @Test fun `reads ordinary bundled compiler version`(@TempDir dir: Path) {
    compiler(dir, "2.4.21")
    assertEquals("2.4.21", IdeKotlinVersion.discover(dir, "262.123.4"))
  }

  @Test fun `discovers renamed IDE compiler on macOS without selecting CLI compiler`(@TempDir dir: Path) {
    val plugin = dir.resolve("Contents/plugins/Kotlin/lib")
    Files.createDirectories(plugin)
    Files.write(plugin.resolve("intellij.libraries.kotlinc.kotlin.compiler.common.jar"), zip("META-INF/compiler.version", "2.5.0".toByteArray()))
    Files.write(plugin.resolve("unrelated.jar"), zip("META-INF/other", byteArrayOf()))
    val cli = dir.resolve("Contents/plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar")
    Files.createDirectories(cli.parent)
    Files.write(cli, zip("META-INF/compiler.version", "2.4.10".toByteArray()))
    assertEquals("2.5.0", IdeKotlinVersion.discover(dir, "263.123.4"))
  }

  @Test fun `maps renamed Linux IDE compiler through KEFS`(@TempDir dir: Path) {
    val plugin = dir.resolve("plugins/Kotlin/lib")
    Files.createDirectories(plugin)
    Files.write(plugin.resolve("intellij.libraries.kotlinc.kotlin.compiler.common.jar"), zip("META-INF/compiler.version", "2.5.255-dev-255".toByteArray()))
    val nested = zip("android-studio-kotlin-versions.properties", "263.7307.200=2.5.0-test\n".toByteArray())
    val archive = dir.resolve("kefs.zip")
    Files.write(archive, zip("plugin/lib/mapping.jar", nested))
    assertEquals("2.5.0-test", IdeKotlinVersion.discover(dir, "263.7307.200.2631.16977666", archive))
  }

  @Test fun `rejects conflicting IDE compiler versions`(@TempDir dir: Path) {
    compiler(dir, "2.4.21")
    val second = dir.resolve("plugins/Kotlin/lib/renamed.jar")
    Files.write(second, zip("META-INF/compiler.version", "2.5.0".toByteArray()))
    val error = assertFailsWith<IllegalArgumentException> { IdeKotlinVersion.discover(dir, "263.123.4") }
    kotlin.test.assertContains(error.message.orEmpty(), "Conflicting bundled IDE Kotlin compiler versions")
  }

  @Test fun `maps Android Studio compiler with KEFS metadata`(@TempDir dir: Path) {
    compiler(dir, "2.4.255-dev-123")
    assertFailsWith<IllegalArgumentException> { IdeKotlinVersion.discover(dir, "262.123.4.5") }
    val nested = zip("android-studio-kotlin-versions.properties", "262.123.4=2.4.20-ide\n".toByteArray())
    val archive = dir.resolve("kefs.zip")
    Files.write(archive, zip("plugin/lib/mapping.jar", nested))
    assertEquals("2.4.20-ide", IdeKotlinVersion.discover(dir, "262.123.4.5", archive))
    assertFailsWith<IllegalArgumentException> { IdeKotlinVersion.discover(dir, "263.1.2", archive) }
  }

  private fun compiler(root: Path, version: String) {
    val jar = root.resolve("plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar")
    Files.createDirectories(jar.parent)
    Files.write(jar, zip("META-INF/compiler.version", version.toByteArray()))
  }

  private fun zip(name: String, contents: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(contents); zip.closeEntry() }
    return output.toByteArray()
  }
}
