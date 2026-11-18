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
public data class KspRequest(val sources: List<KspSource>, val compilerVersion: String)
public data class KspResult(val succeeded: Boolean, val generatedFiles: Map<String, String>, val output: String = "")
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
  record: SnapshotRecording = configuredRecording(),
  recordVersion: Boolean = System.getProperty("knapshots.ksp.recordVersion", "false").toBooleanStrict(),
  location: InlineSnapshotLocation? = null,
) {
  require(compilerVersion.isNotBlank()) { "A compiler version is required." }
  val sources = listOf(KspSource(fileName, source)) + additionalSources
  require(sources.map { it.fileName }.distinct().size == sources.size) { "Source file names must be unique." }
  val target = if (generated != null && record != SnapshotRecording.NONE) location ?: discoverInlineSnapshotLocation() else null
  val original = target?.let { Files.readString(it.file) }
  val result = host.process(KspRequest(sources.map { it.copy(text = it.text.trimIndent()) }, compilerVersion))
  if (result.succeeded != succeeds) throw AssertionError("Expected KSP compilation to ${if (succeeds) "succeed" else "fail"}, but it ${if (result.succeeded) "succeeded" else "failed"}.\n${result.output}")
  diagnostics.forEach { expected ->
    if (!result.output.contains(expected)) throw AssertionError("Missing diagnostic containing '$expected'.\n${result.output}")
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
