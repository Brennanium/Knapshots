package dev.knapshots.testing.compatibility

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Discovers the IDE compiler version, resolving Android Studio's special version via KEFS. */
public object IdeKotlinVersion {
  public fun discover(ide: Path, build: String, kefsArchive: Path? = null): String {
    val relative = "plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar"
    val compiler = listOf(ide.resolve("Contents/$relative"), ide.resolve(relative)).firstOrNull(Files::isRegularFile)
      ?: throw IllegalArgumentException("No bundled Kotlin compiler found in $ide")
    val raw = ZipFile(compiler.toFile()).use { jar ->
      val entry = jar.getEntry("META-INF/compiler.version")
        ?: throw IllegalArgumentException("Bundled Kotlin compiler has no version metadata.")
      jar.getInputStream(entry).bufferedReader().use { it.readText().trim() }
    }
    require(raw.isNotEmpty()) { "Bundled Kotlin compiler version is empty." }
    if (!raw.matches(Regex("[0-9]+\\.[0-9]+\\.255-dev-[0-9]+"))) return raw
    requireNotNull(kefsArchive) { "Android Studio compiler $raw requires a KEFS version mapping for build $build." }
    ZipFile(kefsArchive.toFile()).use { archive ->
      val entries = archive.entries()
      while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (!entry.name.endsWith(".jar")) continue
        val bytes = archive.getInputStream(entry).use { it.readBytes() }
        ZipInputStream(ByteArrayInputStream(bytes)).use { jar ->
          while (true) {
            val nested = jar.nextEntry ?: break
            if (nested.name == "android-studio-kotlin-versions.properties") {
              val properties = Properties().apply { load(jar) }
              val key = build.removePrefix("AI-").split('.').take(3).joinToString(".")
              return properties.getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("KEFS has no Kotlin mapping for Android Studio build $key.")
            }
          }
        }
      }
    }
    throw IllegalArgumentException("KEFS archive contains no Android Studio Kotlin version mapping.")
  }
}
