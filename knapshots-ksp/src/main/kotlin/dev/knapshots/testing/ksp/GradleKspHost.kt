package dev.knapshots.testing.ksp

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure

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
    require(processorClasspath.isNotEmpty() || request.processorProjects.isNotEmpty()) { "At least one processor artifact or project is required." }
    (processorClasspath + compilationClasspath).forEach { require(Files.exists(it)) { "Missing classpath entry: $it" } }
    val projectNames = request.dependencies.map { it.name } + request.processorProjects.map { it.name }
    require(projectNames.distinct().size == projectNames.size) { "Project names must be unique." }
    projectNames.forEach { require(it.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) && it !in setOf("build", "src", "gradle")) { "Invalid project name: $it" } }
    request.processorProjects.forEach { require(it.providers.isNotEmpty()) { "Processor project ${it.name} must declare a provider." } }
    request.processorProjects.flatMap { it.providers }.forEach {
      require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_$.]*"))) { "Invalid processor provider: $it" }
    }
    request.mainClass?.let { require(it.matches(Regex("[A-Za-z_][A-Za-z0-9_$.]*"))) { "Invalid main class: $it" } }
    val root = Files.createTempDirectory("knapshots-ksp-")
    try {
      fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
      fun paths(values: List<Path>) = values.joinToString(", ") { quote(it.toAbsolutePath().normalize().toString()) }
      fun writeSources(sourceRoot: Path, sources: List<KspSource>) {
        require(sources.map { it.fileName }.distinct().size == sources.size) { "Source file names must be unique." }
        sources.forEach { source ->
          val relative = Path.of(source.fileName)
          require(!relative.isAbsolute && relative.none { it.toString() == ".." } && source.fileName.endsWith(".kt")) { "Invalid source file name: ${source.fileName}" }
          val file = sourceRoot.resolve(relative).normalize()
          Files.createDirectories(file.parent)
          Files.writeString(file, source.text)
        }
      }
      val includes = projectNames.joinToString("\n") { "include(${quote(":" + it)})" }
      Files.writeString(root.resolve("settings.gradle.kts"), """
        pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
        dependencyResolutionManagement { repositories { mavenCentral() } }
        rootProject.name = "knapshots-ksp-fixture"
        $includes
      """.trimIndent())
      request.dependencies.forEach { dependency ->
        val directory = Files.createDirectories(root.resolve(dependency.name))
        Files.writeString(directory.resolve("build.gradle.kts"), """
          plugins { kotlin("jvm") version ${quote(request.compilerVersion)} }
          dependencies { implementation(files(${paths(compilationClasspath)})) }
        """.trimIndent())
        writeSources(directory.resolve("src/main/kotlin"), dependency.sources)
      }
      request.processorProjects.forEach { processor ->
        val directory = Files.createDirectories(root.resolve(processor.name))
        Files.writeString(directory.resolve("build.gradle.kts"), """
          plugins { kotlin("jvm") version ${quote(request.compilerVersion)} }
          dependencies { implementation(files(${paths(processorClasspath + compilationClasspath)})) }
        """.trimIndent())
        writeSources(directory.resolve("src/main/kotlin"), processor.sources)
        val services = directory.resolve("src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider")
        Files.createDirectories(services.parent)
        Files.writeString(services, processor.providers.joinToString("\n", postfix = "\n"))
      }
      val processorLines = request.processorProjects.joinToString("\n") { "ksp(project(${quote(":" + it.name)}))" }
      val dependencyLines = request.dependencies.joinToString("\n") { "implementation(project(${quote(":" + it.name)}))" }
      val runTask = request.mainClass?.let { """
        tasks.register<JavaExec>("knapshotsRun") {
          classpath = sourceSets["main"].runtimeClasspath
          mainClass.set(${quote(it)})
        }
      """ }.orEmpty()
      Files.writeString(root.resolve("build.gradle.kts"), """
        plugins {
          kotlin("jvm") version ${quote(request.compilerVersion)}
          id("com.google.devtools.ksp") version ${quote(kspVersion)}
        }
        dependencies {
          implementation(files(${paths(compilationClasspath)}))
          ksp(files(${paths(processorClasspath)}))
          $processorLines
          $dependencyLines
        }
        ksp {
          ${processorOptions.entries.joinToString("\n") { (key, value) -> "arg(${quote(key)}, ${quote(value)})" }}
        }
        $runTask
      """.trimIndent())
      writeSources(root.resolve("src/main/kotlin"), request.sources)
      val arguments = listOf(if (request.mainClass == null) "compileKotlin" else "knapshotsRun", "--stacktrace", "--no-configuration-cache")
      val runner = GradleRunner.create().withProjectDir(root.toFile()).withArguments(arguments)
      val build = try { runner.build() } catch (failure: UnexpectedBuildFailure) { failure.buildResult }
      val kspTask = build.task(":kspKotlin")
      check(kspTask != null && kspTask.outcome != TaskOutcome.SKIPPED && kspTask.outcome != TaskOutcome.NO_SOURCE) { "The KSP task did not run.\n${build.output}" }
      val compileTask = build.task(":compileKotlin")
      val compilationFailed = kspTask.outcome == TaskOutcome.FAILED || compileTask?.outcome == TaskOutcome.FAILED
      val compilationSucceeded = !compilationFailed && compileTask != null && compileTask.outcome !in setOf(TaskOutcome.SKIPPED, TaskOutcome.NO_SOURCE)
      check(compilationFailed || compilationSucceeded) { "The Gradle build failed before compilation completed.\n${build.output}" }
      val run = build.task(":knapshotsRun")
      val execution = if (request.mainClass == null || !compilationSucceeded) null else when (run?.outcome) {
        TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE, TaskOutcome.FROM_CACHE -> true
        TaskOutcome.FAILED -> false
        else -> error("The requested main class did not run.\n${build.output}")
      }
      val generated = root.resolve("build/generated/ksp/main")
      val files = if (Files.isDirectory(generated)) Files.walk(generated).use { paths ->
        paths.filter { Files.isRegularFile(it) && (it.fileName.toString().endsWith(".kt") || it.fileName.toString().endsWith(".java")) }.toList().associate { generated.relativize(it).toString().replace('\\', '/') to Files.readString(it) }
      } else emptyMap()
      return KspResult(compilationSucceeded, files, build.output, parseKspDiagnostics(build.output, root), execution)
    } finally {
      Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
  }
}

/** Parses Kotlin and KSP CLI diagnostics; unrecognized log lines remain available in output. */
internal fun parseKspDiagnostics(output: String, root: Path): List<KspDiagnostic> {
  val marker = Regex("^(e|w|i):\\s*(.*)$")
  val location = Regex("^(.+?):(\\d+)(?::(\\d+))?(?::|\\s)\\s*(.*)$")
  return output.lineSequence().mapNotNull { line ->
    val match = marker.matchEntire(line.trim()) ?: return@mapNotNull null
    val severity = when (match.groupValues[1]) { "e" -> KspDiagnosticSeverity.ERROR; "w" -> KspDiagnosticSeverity.WARNING; else -> KspDiagnosticSeverity.INFO }
    val body = match.groupValues[2].removePrefix("[ksp] ")
    val located = location.matchEntire(body)
    val file = located?.groupValues?.get(1)?.let { value ->
      val path = runCatching { if (value.startsWith("file:")) Path.of(URI(value)) else Path.of(value) }.getOrNull()
      val normalizedRoot = runCatching { root.toRealPath() }.getOrElse { root.toAbsolutePath().normalize() }
      val normalizedPath = path?.let { candidate -> runCatching { candidate.toRealPath() }.getOrElse { candidate.toAbsolutePath().normalize() } }
      if (normalizedPath != null && normalizedPath.startsWith(normalizedRoot)) normalizedRoot.relativize(normalizedPath).toString().replace('\\', '/') else value
    }
    KspDiagnostic(severity, located?.groupValues?.get(4) ?: body, file,
      located?.groupValues?.get(2)?.toIntOrNull(), located?.groupValues?.get(3)?.toIntOrNull())
  }.toList()
}
