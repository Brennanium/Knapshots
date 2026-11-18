package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.assertEditor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/** Exercises KPaths as an external compiler-plugin consumer of the generic editor host. */
@Tag("ide-integration")
@EnabledIfSystemProperty(named = "knapshots.kpathsCheckout", matches = ".+")
class KPathsEditorIntegrationTest {
  @Test fun `generated KPaths resolve in an imported editor project`() {
    val repository = Path.of(checkNotNull(System.getProperty("knapshots.projectDir")))
    val kpaths = Path.of(checkNotNull(System.getProperty("knapshots.kpathsCheckout"))).toRealPath()
    val ide = Path.of(checkNotNull(System.getProperty("knapshots.idePath"))).toRealPath()
    val kefs = checkNotNull(System.getProperty("knapshots.kefsVersion"))
    val mode = System.getProperty("knapshots.kpathsMode", "enabled")
    require(mode in setOf("enabled", "disabled", "diagnostic")) { "Unsupported KPaths editor test mode: $mode" }
    val version = Properties().apply { Files.newInputStream(kpaths.resolve("gradle.properties")).use(::load) }
      .getProperty("VERSION_NAME") ?: error("KPaths version is missing")
    val javaHome = listOf(ide.resolve("Contents/jbr/Contents/Home"), ide.resolve("jbr"))
      .firstOrNull { Files.isExecutable(it.resolve("bin/java")) } ?: error("Android Studio JBR missing: $ide")
    val sdk = sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"),
      System.getProperty("user.home") + "/Library/Android/sdk").filterNotNull().map(Path::of)
      .firstOrNull(Files::isDirectory) ?: error("Set ANDROID_HOME or ANDROID_SDK_ROOT")
    val artifacts = kpaths.resolve("build/consumer-verification/repository").toRealPath()
    val kefsRepo = kpaths.resolve("kpaths-kefs-ide-fixture/build/kefs-repository").toRealPath()
    fun kotlinLiteral(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
    fun xml(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    fun propertiesPath(path: Path) = path.toString().replace("\\", "\\\\").replace(":", "\\:").replace(" ", "\\ ")
    val settings = """
      <project version="4"><component name="KotlinPluginsSettings">
        <option name="repositories"><map><entry key="local" value="${xml(kefsRepo.toString())};PATH" /></map></option>
        <option name="plugins"><map><entry key="KPaths" value="EXACT;${mode != "disabled"};false;io.github.brennanium:kpaths-compiler-plugin-jvm" /></map></option>
        <option name="pluginsRepos"><map><entry key="KPaths" value="local" /></map></option>
      </component></project>
    """.trimIndent()
    val template = EditorProjectTemplate(
      settingsGradleKts = """
        pluginManagement { repositories { maven { url = uri(${kotlinLiteral(artifacts.toString())}) }; gradlePluginPortal(); mavenCentral() } }
        dependencyResolutionManagement { repositories { maven { url = uri(${kotlinLiteral(artifacts.toString())}) }; mavenCentral() } }
        rootProject.name = "knapshots-kpaths-consumer"
      """.trimIndent(),
      buildGradleKts = """
        plugins { kotlin("jvm") version "2.4.21"; id("dev.kpaths.compiler") version ${kotlinLiteral(version)} }
        dependencies {
          implementation(${kotlinLiteral("io.github.brennanium:kpaths-core:$version")})
          implementation(${kotlinLiteral("io.github.brennanium:kpaths-annotations:$version")})
        }
      """.trimIndent(),
      additionalFiles = mapOf(
        "local.properties" to "sdk.dir=${propertiesPath(sdk.toRealPath())}\n",
        ".idea/kotlin-plugins.xml" to settings,
      ),
      copiedFiles = listOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")
        .associateWith { repository.resolve(it) },
    )
    val launcher = CommandEditorProcessLauncher(
      command = { project -> listOf(
        repository.resolve("gradlew").toString(), "--no-daemon", "--no-configuration-cache", "-p", repository.toString(),
        "-Pknapshots.enableIntellijAdapter", "-Pknapshots.idePath=$ide", "-Pknapshots.kefsVersion=$kefs",
        "-Pknapshots.isolatedBuildRoot=${project.directory.parent.resolve("nested-build")}",
        "-Pknapshots.editor.project=${project.directory}", "-Pknapshots.editor.request=${project.requestPath}",
        "-Pknapshots.editor.response=${project.responsePath}", ":knapshots-intellij:runIde", "--args=${project.directory}",
      ) },
      timeout = Duration.ofMinutes(12), environment = mapOf("JAVA_HOME" to javaHome.toString()),
    )
    val host = RunnerEditorSourceHost(
      template, launcher, Files.createDirectories(repository.resolve("build/ide-integration")),
      reportDirectory = System.getProperty("knapshots.reportDirectory")?.let(Path::of),
      failureReportFiles = listOf("nested-build/knapshots-intellij/editor-inspection-sandbox/log/idea.log"),
    )
    assertEditor(
      source = """
        import dev.kpaths.annotations.Pathable
        @Pathable data class Person(var name: String)
        @Pathable sealed interface Event { data class Message(val text: String) : Event }
        val namePath = Person.Paths.name
        val messagePath = Event.Paths.Message.text
      """ + if (mode == "diagnostic") "\nval invalid: Int = \"wrong\"\n" else "",
      host = host,
      awaitReferences = if (mode == "disabled") emptySet() else setOf("Person.Paths.name", "Event.Paths.Message.text"),
    ) {
      when (mode) {
        "disabled" -> {
          diagnostics { error("Unresolved reference"); error("Unresolved reference") }
          reference("Person.Paths.name").isUnresolved()
          reference("Event.Paths.Message.text").isUnresolved()
        }
        else -> {
          if (mode == "diagnostic") diagnostics { error("Int") }
          reference("Person.Paths.name").resolves()
          reference("Event.Paths.Message.text").resolves()
        }
      }
    }
  }
}
