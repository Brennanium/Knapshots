package dev.knapshots.testing.compiler.gradle

import dev.knapshots.testing.compiler.*
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure

/** Compiles snippets in a disposable Kotlin/JVM Gradle project with the requested Kotlin version. */
public class GradleCompilerHost(
  private val classpath: List<Path> = emptyList(),
  private val compilerArguments: List<String> = emptyList(),
  private val captureArguments: (Map<CompilerSnapshotKind, Path>) -> List<String> = { emptyList() },
) : CompilerHost {
  override fun compile(request: CompilerRequest): CompilerResult {
    require(request.compilerVersion.matches(Regex("[A-Za-z0-9_.+-]+"))) { "Invalid Kotlin version." }
    require(request.sources.isNotEmpty()) { "At least one source is required." }
    require(request.sources.map { it.fileName }.distinct().size == request.sources.size) { "Source file names must be unique." }
    classpath.forEach { require(Files.exists(it)) { "Missing classpath entry: $it" } }
    val root = Files.createTempDirectory("knapshots-gradle-compiler-")
    try {
      fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
      val version = quote(request.compilerVersion)
      val repositories = if ("-dev-" in request.compilerVersion) listOf("dev", "experimental", "bootstrap").joinToString("\n") {
        "maven(\"https://packages.jetbrains.team/maven/p/kt/$it/\")"
      } else ""
      val resolution = if (repositories.isNotEmpty()) """
        resolutionStrategy.eachPlugin {
          if (requested.id.id == "org.jetbrains.kotlin.jvm")
            useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${'$'}{requested.version}")
        }
      """ else ""
      Files.writeString(root.resolve("settings.gradle.kts"), """
        pluginManagement { repositories { $repositories; gradlePluginPortal(); mavenCentral() }; $resolution }
        dependencyResolutionManagement { repositories { $repositories; mavenCentral() } }
        rootProject.name = "knapshots-compiler-fixture"
      """.trimIndent())
      val captures = request.captures.associateWith { kind ->
        require(kind.name.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid capture name: ${kind.name}" }
        root.resolve("capture-${kind.name}.txt")
      }
      val args = compilerArguments + captureArguments(captures)
      Files.writeString(root.resolve("build.gradle.kts"), """
        plugins { kotlin("jvm") version $version }
        val loader = extensions.getByName("kotlin").javaClass.classLoader
        val wrapper = Class.forName("org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt", true, loader)
        val actual = wrapper.getMethod("getKotlinPluginVersion", org.gradle.api.Project::class.java).invoke(null, project) as String
        check(actual == $version) { "Unexpected Kotlin version: ${'$'}actual" }
        dependencies { implementation(files(${classpath.joinToString(", ") { quote(it.toAbsolutePath().normalize().toString()) }})) }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
          compilerOptions.freeCompilerArgs.addAll(listOf(${args.joinToString(", ", transform = ::quote)}))
        }
      """.trimIndent())
      val sourceRoot = root.resolve("src/main/kotlin")
      request.sources.forEach { source ->
        val relative = Path.of(source.fileName)
        require(!relative.isAbsolute && relative.none { it.toString() == ".." } && source.fileName.endsWith(".kt")) { "Invalid Kotlin source file name: ${source.fileName}" }
        val file = sourceRoot.resolve(relative).normalize()
        Files.createDirectories(file.parent)
        Files.writeString(file, source.text)
      }
      val runner = GradleRunner.create().withProjectDir(root.toFile()).withArguments("compileKotlin", "--stacktrace", "--no-configuration-cache")
      val build = try { runner.build() } catch (failure: UnexpectedBuildFailure) { failure.buildResult }
      val task = build.task(":compileKotlin")
      check(task != null && task.outcome != TaskOutcome.SKIPPED && task.outcome != TaskOutcome.NO_SOURCE) {
        "The Gradle build failed before Kotlin compilation completed.\n${build.output}"
      }
      val success = task.outcome != TaskOutcome.FAILED
      val outputs = captures.mapNotNull { (kind, path) -> if (Files.isRegularFile(path)) kind to Files.readString(path) else null }.toMap()
      val classesRoot = root.resolve("build/classes/kotlin/main")
      val classes = if (Files.isDirectory(classesRoot)) Files.walk(classesRoot).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }.toList()
          .associate { classesRoot.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it) }
      } else emptyMap()
      val diagnostics = if (success) emptyList() else listOf(CompilerDiagnostic(CompilerDiagnosticSeverity.ERROR, build.output))
      return CompilerResult(success, diagnostics, outputs, classes)
    } finally {
      Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
  }
}
