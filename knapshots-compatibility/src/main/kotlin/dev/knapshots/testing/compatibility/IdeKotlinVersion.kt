package dev.knapshots.testing.compatibility

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipException

/** Discovers the IDE compiler version, resolving Android Studio's special version via KEFS. */
public object IdeKotlinVersion {
  public fun discover(ide: Path, build: String, kefsArchive: Path? = null): String {
    // The IDE compiler JAR's name has changed between platform builds. Inspect only
    // the IDE plugin libraries, not kotlinc/lib: the latter can ship a different CLI compiler.
    val pluginDirs = listOf(ide.resolve("Contents/plugins/Kotlin/lib"), ide.resolve("plugins/Kotlin/lib"))
    val versions = pluginDirs.filter(Files::isDirectory).flatMap { directory ->
      Files.list(directory).use { files ->
        files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
          .sorted().toList().mapNotNull { jar ->
            try {
              ZipFile(jar.toFile()).use { archive ->
                archive.getEntry("META-INF/compiler.version")?.let { entry ->
                  jar to archive.getInputStream(entry).bufferedReader().use { it.readText().trim() }
                }
              }
            } catch (error: ZipException) {
              throw IllegalArgumentException("Invalid IDE Kotlin plugin JAR: $jar", error)
            }
          }
      }
    }
    require(versions.isNotEmpty()) { "No bundled IDE Kotlin compiler version found in $ide" }
    require(versions.none { it.second.isEmpty() }) { "Bundled Kotlin compiler version is empty." }
    require(versions.map { it.second }.distinct().size == 1) {
      "Conflicting bundled IDE Kotlin compiler versions: ${versions.joinToString { "${it.first.fileName}=${it.second}" }}"
    }
    val raw = versions.first().second
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
