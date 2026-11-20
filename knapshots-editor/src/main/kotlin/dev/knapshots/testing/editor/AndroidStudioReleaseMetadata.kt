package dev.knapshots.testing.editor

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Looks up Android Studio's exact release for a build; only needed for release/name selectors. */
public object AndroidStudioReleaseMetadata {
  private val cache = ConcurrentHashMap<String, EditorRelease>()

  public fun resolve(build: String): EditorRelease? {
    if (!build.startsWith("AI-", ignoreCase = true)) return null
    val environment = System.getenv()
    val suppliedBuild = environment["KNAPSHOTS_IDE_RELEASE_BUILD"]
    if (suppliedBuild != null) {
      require(suppliedBuild.equals(build, true)) { "Release metadata for $suppliedBuild does not match IDE build $build." }
      val version = requireNotNull(environment["KNAPSHOTS_IDE_RELEASE_VERSION"]) { "Missing IDE release version." }
      val name = requireNotNull(environment["KNAPSHOTS_IDE_RELEASE_NAME"]) { "Missing IDE release name." }
      require(version.matches(Regex("[0-9]+(?:\\.[0-9]+)+")) && name.isNotBlank()) { "Invalid Android Studio release metadata for $build." }
      return EditorRelease(build, version, name)
    }
    return cache.computeIfAbsent(build) {
      try {
        val connection = URI("https://jb.gg/android-studio-releases-list.xml").toURL().openConnection().apply {
          connectTimeout = 10_000
          readTimeout = 10_000
        }
        connection.getInputStream().use { input -> find(build, input) }
          ?: throw IllegalStateException("No Android Studio release metadata found for $build.")
      } catch (error: Exception) {
        throw IllegalStateException("Could not resolve Android Studio release metadata for $build; use an IDE build selector or run through editorCompatibility. ${error.message}", error)
      }
    }
  }

  internal fun find(build: String, input: java.io.InputStream): EditorRelease? {
    val factory = DocumentBuilderFactory.newInstance().apply {
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val items = factory.newDocumentBuilder().parse(input).getElementsByTagName("item")
    val matches = (0 until items.length).mapNotNull { index ->
      val item = items.item(index) as org.w3c.dom.Element
      fun value(name: String) = item.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()
      val candidate = value("build").let { if (it.startsWith("AI-", true)) it else "AI-$it" }
      if (candidate.equals(build, true)) EditorRelease(build, value("version"), value("name")) else null
    }.distinct()
    require(matches.size <= 1) { "Conflicting Android Studio release metadata for $build." }
    return matches.singleOrNull()?.also {
      require(it.version.matches(Regex("[0-9]+(?:\\.[0-9]+)+")) && it.name.isNotBlank()) { "Invalid Android Studio release metadata for $build." }
    }
  }
}
