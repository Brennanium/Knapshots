package dev.knapshots.testing.ide

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipInputStream

/** Launches an installed IDE with isolated settings and prebuilt plugin archives. */
public class DirectIdeProcessLauncher(
  private val idePath: Path,
  private val pluginArchives: List<Path>,
  private val timeout: Duration = Duration.ofMinutes(12),
) : EditorProcessLauncher {
  override fun launch(project: MaterializedEditorProject): EditorProcessResult {
    val root = project.directory.parent.resolve("ide-sandbox")
    val config = Files.createDirectories(root.resolve("config"))
    val plugins = Files.createDirectories(root.resolve("plugins"))
    val system = Files.createDirectories(root.resolve("system"))
    val log = Files.createDirectories(root.resolve("log"))
    val common = Files.createDirectories(root.resolve("preferences/common/consentOptions")).parent
    val android = Files.createDirectories(root.resolve("preferences/android"))
    pluginArchives.forEach { archive ->
      ZipInputStream(Files.newInputStream(archive)).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          val target = plugins.resolve(entry.name).normalize()
          require(target.startsWith(plugins) && target != plugins && !entry.name.contains('\\')) { "Invalid plugin archive entry: ${entry.name}" }
          if (entry.isDirectory) Files.createDirectories(target) else {
            Files.createDirectories(target.parent)
            Files.copy(zip, target)
          }
          zip.closeEntry()
        }
      }
    }
    fun xml(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    val options = Files.createDirectories(config.resolve("options"))
    val trustedEntries = trustedProjectPaths(project.directory).joinToString("") { path ->
      "<entry key=\"${xml(path.toString())}\" value=\"true\" />"
    }
    Files.writeString(options.resolve("trusted-paths.xml"), """<application><component name="Trusted.Paths"><option name="TRUSTED_PROJECT_PATHS"><map>$trustedEntries</map></option></component></application>""")
    Files.writeString(common.resolve("consentOptions/accepted"), "rsch.send.usage.stat:1.0:0:${System.currentTimeMillis()}")
    val analytics = """{"userId":"00000000-0000-0000-0000-000000000000","hasOptedIn":true,"debugDisablePublishing":true,"saltSkew":-1,"lastOptinPromptVersion":"9999.9999"}"""
    Files.writeString(android.resolve("analytics.settings"), analytics)
    Files.writeString(Files.createDirectories(android.resolve(".android")).resolve("analytics.settings"), analytics)
    val executable = listOf(idePath.resolve("Contents/MacOS/studio"), idePath.resolve("bin/studio"), idePath.resolve("bin/studio.sh"))
      .firstOrNull(Files::isExecutable) ?: error("No executable Android Studio launcher in $idePath")
    val flags = mapOf(
      "idea.config.path" to config, "idea.system.path" to system, "idea.log.path" to log,
      "idea.plugins.path" to plugins, "idea.common.data.path" to common, "ANDROID_PREFS_ROOT" to android,
      "knapshots.editor.project" to project.directory, "knapshots.editor.request" to project.requestPath,
      "knapshots.editor.response" to project.responsePath,
    ).map { (key, value) -> "-D$key=${value.toAbsolutePath()}" } + listOf(
      "-Djb.consents.confirmation.enabled=false", "-Didea.initially.ask.config=never",
      "-Djb.privacy.policy.text=<!--999.999-->", "-Ddisable.android.first.run=true",
      "-Ddisable.android.analytics.consent.dialog=true", "-Dgoogle.login.enable.startup.sign.in.dialog=false",
      "-Dide.show.tips.on.startup.default.value=false", "-Dgit.showDialogsOnUnversionedFiles=false",
    )
    val vmOptions = root.resolve("studio.vmoptions")
    val defaultOptions = listOf(idePath.resolve("Contents/bin/studio.vmoptions"), idePath.resolve("bin/studio64.vmoptions"), idePath.resolve("bin/studio.vmoptions"))
      .firstOrNull(Files::isRegularFile)?.let(Files::readString).orEmpty()
    Files.writeString(vmOptions, defaultOptions + "\n" + flags.joinToString("\n") + "\n")
    return CommandEditorProcessLauncher(
      command = { listOf(executable.toString(), project.directory.toString()) },
      timeout = timeout,
      environment = mapOf("STUDIO_VM_OPTIONS" to vmOptions.toString()),
    ).launch(project)
  }
}

/** Trust only alternate spellings that resolve to this exact project directory. */
internal fun trustedProjectPaths(project: Path): Set<Path> {
  val absolute = project.toAbsolutePath().normalize()
  val resolved = absolute.toRealPath()
  val result = linkedSetOf(absolute, resolved)
  if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
    val text = resolved.toString()
    if (text.startsWith("/private/var/")) {
      val alias = Path.of(text.removePrefix("/private"))
      if (runCatching { alias.toRealPath() == resolved }.getOrDefault(false)) result.add(alias)
    }
  }
  return result
}
