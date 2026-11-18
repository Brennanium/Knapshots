package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.EditorHostProvider
import dev.knapshots.testing.editor.EditorSourceHost
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Builds the standard temporary Gradle project and launches the Knapshots IDE adapter.
 * Values are provided once by the Gradle test configuration using `knapshots.editor.*` properties.
 */
public class GradleEditorHostProvider : EditorHostProvider {
  override fun createHost(): EditorSourceHost {
    fun required(name: String): String = checkNotNull(System.getProperty("knapshots.editor.$name")) {
      "Missing project editor configuration: knapshots.editor.$name"
    }
    fun optional(name: String): String? = System.getProperty("knapshots.editor.$name")
    fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
    fun xml(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    val runnerRoot = Path.of(required("runnerRoot")).toRealPath()
    val ide = Path.of(required("idePath")).toRealPath()
    val javaHome = listOf(ide.resolve("Contents/jbr/Contents/Home"), ide.resolve("jbr"))
      .firstOrNull { Files.isExecutable(it.resolve("bin/java")) } ?: error("Android Studio JBR missing: $ide")
    val kotlin = required("kotlinVersion")
    val pluginId = required("pluginId")
    val pluginVersion = required("pluginVersion")
    val repository = Path.of(required("repository")).toRealPath()
    val dependencies = required("dependencies").split(';').filter(String::isNotBlank)
    require(dependencies.isNotEmpty()) { "At least one editor project dependency is required." }
    val additional = mutableMapOf<String, String>()
    val sdk = sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
      System.getProperty("user.home") + "/Library/Android/sdk").filterNotNull().map(Path::of)
      .firstOrNull(Files::isDirectory)
    if (sdk != null) additional["local.properties"] = "sdk.dir=" + sdk.toRealPath().toString()
      .replace("\\", "\\\\").replace(":", "\\:").replace(" ", "\\ ") + "\n"
    val kefs = optional("kefsVersion")
    if (kefs != null) {
      val kefsRepo = Path.of(required("kefsRepository")).toRealPath()
      val coordinate = required("kefsCoordinate")
      val enabled = optional("kefsEnabled")?.toBooleanStrict() ?: true
      additional[".idea/kotlin-plugins.xml"] = """
        <project version="4"><component name="KotlinPluginsSettings">
          <option name="repositories"><map><entry key="local" value="${xml(kefsRepo.toString())};PATH" /></map></option>
          <option name="plugins"><map><entry key="compiler" value="EXACT;$enabled;false;${xml(coordinate)}" /></map></option>
          <option name="pluginsRepos"><map><entry key="compiler" value="local" /></map></option>
        </component></project>
      """.trimIndent()
    }
    val wrapper = listOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")
    val template = EditorProjectTemplate(
      settingsGradleKts = """
        pluginManagement { repositories { maven { url = uri(${quote(repository.toString())}) }; gradlePluginPortal(); mavenCentral() } }
        dependencyResolutionManagement { repositories { maven { url = uri(${quote(repository.toString())}) }; mavenCentral() } }
        rootProject.name = "knapshots-editor-consumer"
      """.trimIndent(),
      buildGradleKts = """
        plugins { kotlin("jvm") version ${quote(kotlin)}; id(${quote(pluginId)}) version ${quote(pluginVersion)} }
        dependencies { ${dependencies.joinToString("; ") { "implementation(${quote(it)})" }} }
      """.trimIndent(),
      additionalFiles = additional,
      copiedFiles = wrapper.associateWith { runnerRoot.resolve(it) },
    )
    val launcher = CommandEditorProcessLauncher(
      command = { project -> listOf(
        runnerRoot.resolve("gradlew").toString(), "--no-daemon", "--no-configuration-cache", "-p", runnerRoot.toString(),
        "-Pknapshots.enableIntellijAdapter", "-Pknapshots.idePath=$ide",
      ) + (kefs?.let { listOf("-Pknapshots.kefsVersion=$it") } ?: emptyList()) + listOf(
        "-Pknapshots.isolatedBuildRoot=${project.directory.parent.resolve("nested-build")}",
        "-Pknapshots.editor.project=${project.directory}", "-Pknapshots.editor.request=${project.requestPath}",
        "-Pknapshots.editor.response=${project.responsePath}", ":knapshots-intellij:runIde", "--args=${project.directory}",
      ) },
      timeout = Duration.ofMinutes(12), environment = mapOf("JAVA_HOME" to javaHome.toString()),
    )
    return RunnerEditorSourceHost(template, launcher,
      temporaryParent = optional("temporaryParent")?.let { Files.createDirectories(Path.of(it)) },
      reportDirectory = optional("reportDirectory")?.let(Path::of),
      failureReportFiles = listOf("nested-build/knapshots-intellij/editor-inspection-sandbox/log/idea.log"),
    )
  }
}
