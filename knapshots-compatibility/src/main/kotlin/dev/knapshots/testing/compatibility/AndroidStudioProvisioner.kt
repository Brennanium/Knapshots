package dev.knapshots.testing.compatibility

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/** Provisions a checksum-verified distribution for the duration of [useIde]. */
public class AndroidStudioProvisioner(private val cache: Path) {
  public fun <T> useIde(release: AndroidStudioRelease, platform: IdePlatform, action: (Path) -> T): T {
    require(release.version.matches(Regex("[0-9]+(?:\\.[0-9]+)+"))) { "Invalid IDE version." }
    Files.createDirectories(cache)
    val archive = cache.resolve(release.version + platform.archiveSuffix)
    download(release, archive)
    val temporary = Files.createTempDirectory("knapshots-studio-")
    var mounted = false
    try {
      val ide = when (platform) {
        IdePlatform.LINUX_X64 -> extractLinux(archive, temporary)
        IdePlatform.MAC_ARM64 -> {
          command("hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", temporary.toString(), archive.toString())
          mounted = true
          temporary.resolve("Android Studio.app")
        }
      }
      try {
        require(Files.isDirectory(ide)) { "Android Studio distribution has no IDE directory." }
        return action(ide)
      } finally {
        if (mounted) {
          command("hdiutil", "detach", temporary.toString())
          mounted = false
        }
      }
    } finally {
      if (!mounted) Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
  }

  private fun download(release: AndroidStudioRelease, archive: Path) {
    require(release.url.startsWith("https://") && release.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid download metadata." }
    if (Files.isRegularFile(archive) && checksum(archive) == release.sha256) return
    val temp = Files.createTempFile(cache, ".studio-", ".download")
    try {
      val connection = URI(release.url).toURL().openConnection().apply { connectTimeout = 120_000; readTimeout = 120_000 }
      connection.getInputStream().use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
      require(checksum(temp) == release.sha256) { "Android Studio download failed SHA-256 verification." }
      Files.move(temp, archive, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temp) }
  }

  private fun checksum(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
      val buffer = ByteArray(64 * 1024)
      while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun extractLinux(archive: Path, destination: Path): Path {
    val root = destination.toAbsolutePath().normalize()
    val links = mutableListOf<Pair<Path, Path>>()
    TarArchiveInputStream(GzipCompressorInputStream(Files.newInputStream(archive))).use { tar ->
      while (true) {
        val entry = tar.nextEntry ?: break
        val target = root.resolve(entry.name).normalize()
        require(target.startsWith(root) && entry.name.isNotBlank()) { "Unsafe archive path: ${entry.name}" }
        require(!entry.isLink && !entry.isCharacterDevice && !entry.isBlockDevice && !entry.isFIFO) {
          "Unsupported archive entry: ${entry.name}"
        }
        if (entry.isSymbolicLink) {
          val link = Path.of(entry.linkName)
          require(!link.isAbsolute && target.parent.resolve(link).normalize().startsWith(root)) { "Unsafe archive link: ${entry.name}" }
          links += target to link
        }
        else if (entry.isDirectory) Files.createDirectories(target)
        else {
          require(entry.isFile) { "Unsupported archive entry: ${entry.name}" }
          Files.createDirectories(target.parent)
          Files.copy(tar, target)
          if (entry.mode and 0b001001001 != 0) target.toFile().setExecutable(true, false)
        }
      }
    }
    // Create links last so no later archive entry can write through a link outside its directory.
    links.forEach { (target, link) -> Files.createDirectories(target.parent); Files.createSymbolicLink(target, link) }
    return root.resolve("android-studio")
  }

  private fun command(vararg args: String) {
    val process = ProcessBuilder(*args).inheritIO().start()
    check(process.waitFor() == 0) { "IDE provisioning command failed: ${args.first()}" }
  }
}
