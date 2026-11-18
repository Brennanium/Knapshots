package dev.knapshots.testing.ksp

import dev.knapshots.testing.compiler.InlineSnapshotLocation
import dev.knapshots.testing.compiler.SnapshotExpectation
import dev.knapshots.testing.compiler.SnapshotRecording
import dev.knapshots.testing.compiler.discoverInlineSnapshotLocation
import dev.knapshots.testing.compiler.recordInlineSnapshots
import dev.knapshots.testing.compiler.shouldRecordSnapshot
import dev.knapshots.testing.snapshot.compareSnapshot
import dev.knapshots.testing.snapshot.textSnapshotting
import java.nio.file.Files

public data class KspSource(val fileName: String, val text: String)
public data class KspDependency(val name: String, val sources: List<KspSource>)
public data class KspProcessorProject(val name: String, val sources: List<KspSource>, val providers: List<String>)
public enum class KspDiagnosticSeverity { ERROR, WARNING, INFO }
public data class KspDiagnostic(val severity: KspDiagnosticSeverity, val message: String, val file: String? = null, val line: Int? = null, val column: Int? = null)
public data class KspDiagnosticExpectation(val severity: KspDiagnosticSeverity, val messageContains: String, val file: String? = null, val line: Int? = null, val column: Int? = null)
public data class KspRequest(val sources: List<KspSource>, val compilerVersion: String, val dependencies: List<KspDependency> = emptyList(), val mainClass: String? = null, val processorProjects: List<KspProcessorProject> = emptyList())
public data class KspResult(val succeeded: Boolean, val generatedFiles: Map<String, String>, val output: String = "", val diagnostics: List<KspDiagnostic> = emptyList(), val executionSucceeded: Boolean? = null)
public fun interface KspHost { public fun process(request: KspRequest): KspResult }

/** Asserts processor output and compilation outcome. File names are part of the snapshot. */
public fun assertKsp(
  source: String,
  compilerVersion: String = System.getProperty("knapshots.ksp.kotlinVersion") ?: org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION,
  host: KspHost = configuredKspHost(),
  generated: SnapshotExpectation? = null,
  additionalSources: List<KspSource> = emptyList(),
  fileName: String = "Main.kt",
  succeeds: Boolean = true,
  diagnostics: List<String> = emptyList(),
  expectedDiagnostics: List<KspDiagnosticExpectation>? = null,
  dependencies: List<KspDependency> = emptyList(),
  processorProjects: List<KspProcessorProject> = emptyList(),
  mainClass: String? = null,
  executionSucceeds: Boolean = true,
  record: SnapshotRecording = configuredRecording(),
  recordVersion: Boolean = System.getProperty("knapshots.ksp.recordVersion", "false").toBooleanStrict(),
  location: InlineSnapshotLocation? = null,
) {
  require(compilerVersion.isNotBlank()) { "A compiler version is required." }
  val sources = listOf(KspSource(fileName, source)) + additionalSources
  require(sources.map { it.fileName }.distinct().size == sources.size) { "Source file names must be unique." }
  val target = if (generated != null && record != SnapshotRecording.NONE) location ?: discoverInlineSnapshotLocation() else null
  val original = target?.let { Files.readString(it.file) }
  val result = host.process(KspRequest(sources.map { it.copy(text = it.text.trimIndent()) }, compilerVersion,
    dependencies.map { dependency -> dependency.copy(sources = dependency.sources.map { it.copy(text = it.text.trimIndent()) }) }, mainClass, processorProjects.map { processor -> processor.copy(sources = processor.sources.map { it.copy(text = it.text.trimIndent()) }) }))
  if (result.succeeded != succeeds) throw AssertionError("Expected KSP compilation to ${if (succeeds) "succeed" else "fail"}, but it ${if (result.succeeded) "succeeded" else "failed"}.\n${result.output}")
  diagnostics.forEach { expected ->
    if (!result.output.contains(expected)) throw AssertionError("Missing diagnostic containing '$expected'.\n${result.output}")
  }
  if (mainClass != null && result.succeeded && result.executionSucceeded != executionSucceeds) {
    throw AssertionError("Expected execution to ${if (executionSucceeds) "succeed" else "fail"}, but it ${when (result.executionSucceeded) { true -> "succeeded"; false -> "failed"; null -> "did not run" }}.\n${result.output}")
  }
  expectedDiagnostics?.let { expected ->
    val owner = IntArray(result.diagnostics.size) { -1 }
    fun assign(expectedIndex: Int, visited: BooleanArray): Boolean {
      val item = expected[expectedIndex]
      require(item.messageContains.isNotBlank()) { "Expected diagnostic text must not be blank." }
      for (index in result.diagnostics.indices) {
        val actual = result.diagnostics[index]
        if (visited[index] || actual.severity != item.severity || !actual.message.contains(item.messageContains) ||
          (item.file != null && actual.file != item.file) || (item.line != null && actual.line != item.line) ||
          (item.column != null && actual.column != item.column)) continue
        visited[index] = true
        if (owner[index] == -1 || assign(owner[index], visited)) { owner[index] = expectedIndex; return true }
      }
      return false
    }
    expected.forEachIndexed { index, item ->
      if (!assign(index, BooleanArray(result.diagnostics.size))) throw AssertionError("Missing diagnostic $item. Actual: ${result.diagnostics}\n${result.output}")
    }
    val unexpected = result.diagnostics.filterIndexed { index, diagnostic -> owner[index] == -1 && diagnostic.severity == KspDiagnosticSeverity.ERROR }
    if (unexpected.isNotEmpty()) throw AssertionError("Unexpected error diagnostics: $unexpected\n${result.output}")
  }
  if (generated == null) return
  val actual = result.generatedFiles.toSortedMap().entries.joinToString("\n\n") { (name, text) ->
    "// FILE: $name\n${text.trimEnd('\n', '\r')}"
  }
  if (shouldRecordSnapshot(generated, actual, compilerVersion, record, recordVersion)) {
    val destination = checkNotNull(target)
    try {
      recordInlineSnapshots(destination, "assertKsp", mapOf("generated" to actual), compilerVersion, recordVersion, original)
    } catch (error: Exception) {
      throw AssertionError("Could not record inline snapshot at ${destination.file}:${destination.line}: ${error.message}", error)
    }
    throw AssertionError("Recorded inline snapshot at ${destination.file}:${destination.line}; review and rerun the test.")
  }
  check(!generated.missing) { "Missing generated source snapshot; enable recording to insert it." }
  val difference = compareSnapshot(actual, generated.forVersion(compilerVersion).trimIndent().trimEnd('\n', '\r'), textSnapshotting)
  if (difference != null) throw AssertionError("Generated KSP source for Kotlin $compilerVersion differs.\nExpected:\n${difference.expected}\nActual:\n${difference.actual}")
}

/** Provides a processor host for all assertions in a test runtime. */
public fun interface KspHostProvider { public fun createHost(): KspHost }

private fun configuredKspHost(): KspHost {
  val providers = java.util.ServiceLoader.load(KspHostProvider::class.java).toList()
  check(providers.size <= 1) { "Expected at most one KspHostProvider service, found ${providers.size}." }
  providers.singleOrNull()?.let { return it.createHost() }
  val version = System.getProperty("knapshots.ksp.version") ?: error("Configure knapshots.ksp.version or register a KspHostProvider.")
  fun paths(name: String) = System.getProperty(name).orEmpty().split(java.io.File.pathSeparator).filter(String::isNotBlank).map(java.nio.file.Path::of)
  return GradleKspHost(version, paths("knapshots.ksp.processorClasspath"), paths("knapshots.ksp.classpath"))
}

private fun configuredRecording(): SnapshotRecording {
  val value = System.getProperty("knapshots.ksp.record", "none")
  return try { SnapshotRecording.valueOf(value.uppercase(java.util.Locale.ROOT)) }
  catch (_: IllegalArgumentException) { error("Invalid knapshots.ksp.record '$value'; use none, missing, failed, or all.") }
}
