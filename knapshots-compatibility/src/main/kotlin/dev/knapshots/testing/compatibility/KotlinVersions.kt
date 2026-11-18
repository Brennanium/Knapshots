package dev.knapshots.testing.compatibility

import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Resolves version selectors to concrete Kotlin compiler and Gradle plugin versions. */
public class KotlinVersions(
  private val fetch: (channel: String, artifact: String) -> Set<String> = ::fetchVersions,
) {
  /** Preserves input order, and chooses the latest dev build available for both artifacts. */
  public fun resolve(selectors: List<String>): List<KotlinVersionSelection> {
    require(selectors.isNotEmpty()) { "At least one Kotlin version is required." }
    selectors.forEach { selector ->
      require(EXACT.matches(selector) || FAMILY.matches(selector)) { "Invalid Kotlin version selector: $selector" }
    }
    val families = selectors.mapNotNull { FAMILY.matchEntire(it)?.groupValues?.get(1) }.distinct()
    val resolved = if (families.isEmpty()) emptyMap() else {
      val available = ARTIFACTS.map { artifact -> CHANNELS.flatMap { channel -> fetch(channel, artifact) }.toSet() }
        .reduce(Set<String>::intersect)
      families.associateWith { family ->
        val pattern = Regex(Regex.escape(family) + "-dev-([0-9]+)")
        available.mapNotNull { version -> pattern.matchEntire(version)?.let { it.groupValues[1].toBigInteger() to version } }
          .maxByOrNull { it.first }?.second
          ?: throw IllegalArgumentException("No $family-dev build has both compiler and Gradle plugin artifacts.")
      }
    }
    return selectors.map { KotlinVersionSelection(it, FAMILY.matchEntire(it)?.let { match -> resolved.getValue(match.groupValues[1]) } ?: it) }
  }

  private companion object {
    val EXACT = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?")
    val FAMILY = Regex("([0-9]+\\.[0-9]+\\.[0-9]+)-dev-?\\*")
    val CHANNELS = listOf("dev", "experimental", "bootstrap")
    val ARTIFACTS = listOf("kotlin-gradle-plugin", "kotlin-compiler-embeddable")

    fun fetchVersions(channel: String, artifact: String): Set<String> {
      val url = "https://packages.jetbrains.team/maven/p/kt/$channel/org/jetbrains/kotlin/$artifact/maven-metadata.xml"
      val connection = URI(url).toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 30_000 }
      val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
      }
      return connection.getInputStream().use { input ->
        val nodes = factory.newDocumentBuilder().parse(input).getElementsByTagName("version")
        (0 until nodes.length).map { nodes.item(it).textContent }.toSet()
      }
    }
  }
}

public data class KotlinVersionSelection(val selector: String, val version: String)
