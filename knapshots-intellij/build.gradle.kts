import java.nio.file.Path

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.intellij.platform)
}

private fun String.xmlAttribute(): String =
  replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun trustProject(configDirectory: File, projectDirectory: File) {
  val trustedPaths = configDirectory.resolve("options/trusted-paths.xml")
  trustedPaths.parentFile.mkdirs()
  val absolute = projectDirectory.toPath().toAbsolutePath().normalize()
  val resolved = absolute.toRealPath()
  val paths = linkedSetOf(absolute, resolved)
  if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true) && resolved.toString().startsWith("/private/var/")) {
    val alias = Path.of(resolved.toString().removePrefix("/private"))
    if (runCatching { alias.toRealPath() == resolved }.getOrDefault(false)) paths.add(alias)
  }
  val entries = paths.joinToString("\n") { "<entry key=\"${it.toString().xmlAttribute()}\" value=\"true\" />" }
  trustedPaths.writeText(
    """
      <?xml version="1.0" encoding="UTF-8"?>
      <application>
        <component name="Trusted.Paths">
          <option name="TRUSTED_PROJECT_PATHS">
            <map>
              $entries
            </map>
          </option>
        </component>
      </application>
    """.trimIndent(),
  )
}

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  implementation(project(":knapshots-ide-runner"))
  intellijPlatform {
    local(providers.gradleProperty("knapshots.idePath").map(::file))
    bundledPlugin("org.jetbrains.kotlin")
    providers.gradleProperty("knapshots.kefsVersion").orNull?.let { version ->
      plugin("com.github.mr3zee.kotlinPlugins:$version")
    }
  }
}

kotlin {
  jvmToolchain(25)
}

configurations.runtimeClasspath {
  exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

intellijPlatform {
  buildSearchableOptions = false
  instrumentCode = false
}

tasks.runIde {
  notCompatibleWithConfigurationCache("launches an external IDE with a fresh inspection sandbox")
  val inspectedProject = providers.gradleProperty("knapshots.editor.project").orNull
  val request = providers.gradleProperty("knapshots.editor.request").orNull
  val response = providers.gradleProperty("knapshots.editor.response").orNull
  val sandbox = layout.buildDirectory.dir("editor-inspection-sandbox")
  val preferences = sandbox.get().dir("preferences")

  doFirst {
    require(inspectedProject != null && request != null && response != null) {
      "runIde inspection requires -Pknapshots.editor.project, -Pknapshots.editor.request, and -Pknapshots.editor.response."
    }
    trustProject(sandboxConfigDirectory.get().asFile, file(inspectedProject))
    // The IntelliJ Gradle plugin may omit sandbox arguments for directories that do not exist.
    sandboxSystemDirectory.get().asFile.mkdirs()
    sandboxLogDirectory.get().asFile.mkdirs()

    // Keep Android Studio's first-run state inside the sandbox. CI has no user session to answer
    // consent or analytics prompts; local inspection runs should be just as non-interactive.
    val common = preferences.dir("common").asFile
    val android = preferences.dir("android").asFile
    common.resolve("consentOptions").apply {
      mkdirs()
      resolve("accepted").writeText("rsch.send.usage.stat:1.0:0:${System.currentTimeMillis()}")
    }
    val analytics = """{"userId":"00000000-0000-0000-0000-000000000000","hasOptedIn":true,"debugDisablePublishing":true,"saltSkew":-1,"lastOptinPromptVersion":"9999.9999"}"""
    android.mkdirs()
    android.resolve("analytics.settings").writeText(analytics)
    android.resolve(".android").apply {
      mkdirs()
      resolve("analytics.settings").writeText(analytics)
    }
  }
  // `runIde` receives the inspected project as its regular application argument (`--args=…`).
  // The Gradle properties below are deliberately only the adapter protocol: passing a Provider
  // to `args` here launches Android Studio in Light Edit instead of opening the project.
  inspectedProject?.let { systemProperty("knapshots.editor.project", it) }
  request?.let { systemProperty("knapshots.editor.request", it) }
  response?.let { systemProperty("knapshots.editor.response", it) }
  sandboxConfigDirectory.set(sandbox.map { it.dir("config") })
  sandboxSystemDirectory.set(sandbox.map { it.dir("system") })
  sandboxLogDirectory.set(sandbox.map { it.dir("log") })
  systemProperty("jb.consents.confirmation.enabled", "false")
  systemProperty("idea.initially.ask.config", "never")
  systemProperty("jb.privacy.policy.text", "<!--999.999-->")
  systemProperty("disable.android.first.run", "true")
  systemProperty("disable.android.analytics.consent.dialog", "true")
  systemProperty("google.login.enable.startup.sign.in.dialog", "false")
  systemProperty("ide.show.tips.on.startup.default.value", "false")
  systemProperty("git.showDialogsOnUnversionedFiles", "false")
  systemProperty("idea.common.data.path", preferences.dir("common").asFile.absolutePath)
  systemProperty("ANDROID_PREFS_ROOT", preferences.dir("android").asFile.absolutePath)
}

// Publish the resolved distribution path to compatibility drivers without guessing the version.
tasks.register("writeEditorAdapterPath") {
  val archive = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
  val destination = layout.buildDirectory.file("editor-adapter-path.txt")
  dependsOn("buildPlugin")
  inputs.file(archive)
  outputs.file(destination)
  doLast {
    val output = destination.get().asFile
    output.parentFile.mkdirs()
    output.writeText(archive.get().asFile.absolutePath + "\n")
  }
}
