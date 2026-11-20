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
    val adapterArchive = optional("adapterArchive")?.let { Path.of(it).toRealPath() }
    val runnerRoot = optional("runnerRoot")?.let { Path.of(it).toRealPath() }
    val wrapperRoot = optional("wrapperRoot")?.let { Path.of(it).toRealPath() }
      ?: runnerRoot ?: Path.of(System.getProperty("user.dir")).toRealPath()
    require(adapterArchive != null || runnerRoot != null) { "Configure the IDE adapter archive or launcher checkout." }
    val ide = Path.of(required("idePath")).toRealPath()
    val javaHome = ideJavaHome(ide)
    val kotlin = required("kotlinVersion")
    val pluginId = optional("pluginId")
    val pluginVersion = optional("pluginVersion")
    require(pluginId == null || pluginVersion != null) { "A plugin version is required when a plugin ID is supplied." }
    val repository = optional("repository")?.let { Path.of(it).toRealPath() }
    val dependencies = optional("dependencies")?.split(';')?.filter(String::isNotBlank).orEmpty()
    val classpath = optional("classpath")?.split(java.io.File.pathSeparator)?.filter(String::isNotBlank).orEmpty()
    val localRepository = repository?.let { "maven { url = uri(${quote(it.toString())}) }; " }.orEmpty()
    val pluginDeclaration = pluginId?.let { "; id(${quote(it)}) version ${quote(checkNotNull(pluginVersion))}" }.orEmpty()
    val classpathDeclaration = if (classpath.isEmpty()) "" else
      "implementation(files(${classpath.joinToString(", ") { quote(it) }}))"
    val additional = mutableMapOf(".idea/gradle.xml" to editorGradleSettings())
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
        pluginManagement { repositories { ${localRepository}gradlePluginPortal(); mavenCentral() } }
        dependencyResolutionManagement { repositories { ${localRepository}mavenCentral() } }
        rootProject.name = "knapshots-editor-consumer"
      """.trimIndent(),
      buildGradleKts = """
        plugins { kotlin("jvm") version ${quote(kotlin)}$pluginDeclaration }
        dependencies { ${dependencies.joinToString("; ") { "implementation(${quote(it)})" }}${if (dependencies.isNotEmpty() && classpathDeclaration.isNotEmpty()) "; " else ""}$classpathDeclaration }
      """.trimIndent(),
      additionalFiles = additional,
      copiedFiles = wrapper.associateWith { wrapperRoot.resolve(it) },
    )
    val launcher = if (adapterArchive != null) {
      val archives = listOf(adapterArchive) + listOfNotNull(optional("kefsArchive")?.let { Path.of(it).toRealPath() })
      require(kefs == null || archives.size == 2) { "Configure a KEFS archive for the direct IDE launcher." }
      DirectIdeProcessLauncher(ide, archives)
    } else CommandEditorProcessLauncher(
      command = { project -> listOf(
        checkNotNull(runnerRoot).resolve("gradlew").toString(), "--no-daemon", "--no-configuration-cache", "-p", runnerRoot.toString(),
        "-Pknapshots.enableIntellijAdapter", "-Pknapshots.idePath=$ide",
      ) + (kefs?.let { listOf("-Pknapshots.kefsVersion=$it") } ?: emptyList()) + listOf(
        "-Pknapshots.isolatedBuildRoot=${project.directory.parent.resolve("nested-build")}",
        "-Pknapshots.editor.project=${project.directory}", "-Pknapshots.editor.request=${project.requestPath}",
        "-Pknapshots.editor.response=${project.responsePath}", ":knapshots-intellij:runIde", "--args=${project.directory}",
      ) },
      timeout = Duration.ofMinutes(12), environment = mapOf("JAVA_HOME" to javaHome.toString()),
    )
    return RunnerEditorSourceHost(template, launcher,
      ideBuild = installedIdeBuild(ide),
      temporaryParent = optional("temporaryParent")?.let { Files.createDirectories(Path.of(it)) },
      reportDirectory = optional("reportDirectory")?.let(Path::of),
      failureReportFiles = listOf(
        "nested-build/knapshots-intellij/editor-inspection-sandbox/log/idea.log",
        "ide-sandbox/log/idea.log",
      ),
    )
  }
}

/** Link the generated project and select the launcher's JDK before the first Gradle sync. */
internal fun editorGradleSettings(): String = """
  <project version="4">
    <component name="GradleSettings">
      <option name="linkedExternalProjectsSettings">
        <GradleProjectSettings>
          <option name="externalProjectPath" value="${'$'}PROJECT_DIR${'$'}" />
          <option name="gradleJvm" value="#JAVA_HOME" />
        </GradleProjectSettings>
      </option>
    </component>
  </project>
""".trimIndent()

/** Read the actual selected IDE's product-prefixed build, not the project Kotlin version. */
internal fun installedIdeBuild(ide: Path): String? = listOf(
  ide.resolve("Contents/Resources/build.txt"), ide.resolve("build.txt"),
).firstOrNull(Files::isRegularFile)?.let { Files.readString(it).trim().takeIf(String::isNotEmpty) }
