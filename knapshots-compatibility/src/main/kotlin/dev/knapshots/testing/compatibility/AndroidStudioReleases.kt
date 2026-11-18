package dev.knapshots.testing.compatibility

import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

public enum class IdePlatform(public val archiveSuffix: String) {
  MAC_ARM64("-mac_arm.dmg"), LINUX_X64("-linux.tar.gz"),
}

public data class AndroidStudioRelease(
  val name: String,
  val version: String,
  val build: String,
  val url: String,
  val sha256: String,
)

/** Parses the Android Studio release feed and selects an exact release or latest stable. */
public object AndroidStudioReleases {
  public fun select(selector: String, feed: InputStream, platform: IdePlatform): AndroidStudioRelease {
    val factory = DocumentBuilderFactory.newInstance().apply {
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val items = factory.newDocumentBuilder().parse(feed).getElementsByTagName("item")
    val candidates = (0 until items.length).map { items.item(it) as Element }.filter { item ->
      if (selector == "latest-stable") item.childText("channel") in setOf("Stable", "Release", "Patch")
      else selector == item.childText("version") || selector == item.childText("build").removePrefix("AI-")
    }
    val item = if (selector == "latest-stable") candidates.maxWithOrNull { a, b ->
      compareVersions(a.childText("version"), b.childText("version"))
    } else candidates.singleOrNull()
    requireNotNull(item) { "No unique Android Studio release for '$selector'." }
    val downloads = item.getElementsByTagName("download")
    val download = (0 until downloads.length).map { downloads.item(it) as Element }
      .filter { it.childText("link").endsWith(platform.archiveSuffix) }.singleOrNull()
    requireNotNull(download) { "No unique Android Studio download for $platform." }
    val url = download.childText("link")
    val digest = download.childText("checksum")
    require(url.startsWith("https://") && digest.matches(Regex("[0-9a-fA-F]{64}"))) {
      "Invalid Android Studio download URL or SHA-256 checksum."
    }
    return AndroidStudioRelease(item.childText("name"), item.childText("version"),
      item.childText("build").removePrefix("AI-"), url, digest.lowercase())
  }

  private fun Element.childText(name: String): String = getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()

  private fun compareVersions(first: String, second: String): Int {
    val a = first.split('.').map { it.toInt() }
    val b = second.split('.').map { it.toInt() }
    for (i in 0 until maxOf(a.size, b.size)) {
      val result = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
      if (result != 0) return result
    }
    return 0
  }
}
