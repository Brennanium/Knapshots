package dev.knapshots.testing.ksp

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner

/** Runs KSP in an isolated Gradle project using the caller's processor and application classpaths. */
public class GradleKspHost(
  private val kspVersion: String,
  private val processorClasspath: List<Path>,
  private val compilationClasspath: List<Path> = emptyList(),
  private val processorOptions: Map<String, String> = emptyMap(),
) : KspHost {
  override fun process(request: KspRequest): KspResult {
    require(request.compilerVersion.matches(Regex("[A-Za-z0-9_.+-]+"))) { "Invalid Kotlin version." }
    require(kspVersion.matches(Regex("[A-Za-z0-9_.+-]+"))) { "Invalid KSP version." }
    require(processorClasspath.isNotEmpty()) { "At least one processor artifact is required." }
    (processorClasspath + compilationClasspath).forEach { require(Files.exists(it)) { "Missing classpath entry: $it" } }
    val root = Files.createTempDirectory("knapshots-ksp-")
    try {
      Files.writeString(root.resolve("settings.gradle.kts"), """
        pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
        dependencyResolutionManagement { repositories { mavenCentral() } }
        rootProject.name = "knapshots-ksp-fixture"
      """.trimIndent())
      fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
      fun paths(values: List<Path>) = values.joinToString(", ") { quote(it.toAbsolutePath().normalize().toString()) }
      Files.writeString(root.resolve("build.gradle.kts"), """
        plugins {
          kotlin("jvm") version ${quote(request.compilerVersion)}
          id("com.google.devtools.ksp") version ${quote(kspVersion)}
        }
        dependencies {
          implementation(files(${paths(compilationClasspath)}))
          ksp(files(${paths(processorClasspath)}))
        }
        ksp {
          ${processorOptions.entries.joinToString("\n") { (key, value) -> "arg(${quote(key)}, ${quote(value)})" }}
        }
      """.trimIndent())
      val sourceRoot = root.resolve("src/main/kotlin")
      request.sources.forEach { source ->
        val relative = Path.of(source.fileName)
        require(!relative.isAbsolute && relative.none { it.toString() == ".." } && source.fileName.endsWith(".kt")) { "Invalid source file name: ${source.fileName}" }
        val file = sourceRoot.resolve(relative).normalize()
        Files.createDirectories(file.parent)
        Files.writeString(file, source.text)
      }
      val result = GradleRunner.create().withProjectDir(root.toFile())
        .withArguments("compileKotlin", "--stacktrace", "--no-configuration-cache")
        .buildAndFailOrSucceed()
      val generated = root.resolve("build/generated/ksp/main")
      val files = if (Files.isDirectory(generated)) Files.walk(generated).use { paths ->
        paths.filter { Files.isRegularFile(it) && (it.fileName.toString().endsWith(".kt") || it.fileName.toString().endsWith(".java")) }.toList().associate { generated.relativize(it).toString().replace('\\', '/') to Files.readString(it) }
      } else emptyMap()
      val task = result.first.task(":kspKotlin")
      check(task != null && task.outcome != org.gradle.testkit.runner.TaskOutcome.SKIPPED && task.outcome != org.gradle.testkit.runner.TaskOutcome.NO_SOURCE) { "The KSP task did not run.\n${result.first.output}" }
      return KspResult(result.second, files, result.first.output)
    } finally {
      Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
  }
}

private fun GradleRunner.buildAndFailOrSucceed(): Pair<org.gradle.testkit.runner.BuildResult, Boolean> = try {
  build() to true
} catch (failure: org.gradle.testkit.runner.UnexpectedBuildFailure) {
  failure.buildResult to false
}
