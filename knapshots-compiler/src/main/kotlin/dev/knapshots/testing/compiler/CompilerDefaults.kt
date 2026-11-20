package dev.knapshots.testing.compiler

import java.util.ServiceLoader
import java.nio.file.Path
import org.jetbrains.kotlin.config.KotlinCompilerVersion

/** Supplies the compiler under test once for a test project's assertions. */
public fun interface CompilerHostProvider {
  public fun createCompiler(): CompilerHost
}

private fun configuredCompiler(): CompilerHost {
  val providers = ServiceLoader.load(CompilerHostProvider::class.java).toList()
  check(providers.size <= 1) {
    "Expected at most one CompilerHostProvider service, found ${providers.size}."
  }
  providers.singleOrNull()?.let { return it.createCompiler() }
  val pluginJar = System.getProperty("knapshots.compiler.pluginJar")
    ?: error("Configure the compiler plugin under test or register a CompilerHostProvider service.")
  val classpath = System.getProperty("knapshots.compiler.classpath").orEmpty()
    .split(java.io.File.pathSeparator).filter(String::isNotBlank).map(Path::of)
  val pluginId = System.getProperty("knapshots.compiler.pluginId")
  val options = mapOf(
    CompilerSnapshotKind.FIR to System.getProperty("knapshots.compiler.firOption"),
    CompilerSnapshotKind.IR to System.getProperty("knapshots.compiler.irOption"),
  )
  return EmbeddedCompilerHost(
    classpath = classpath,
    compilerArguments = listOf("-Xplugin=$pluginJar"),
    captureArguments = { captures -> captures.flatMap { (kind, path) ->
      val id = requireNotNull(pluginId) { "Configure the compiler plugin ID to capture ${kind.name}." }
      val option = requireNotNull(options[kind]) { "Configure the compiler plugin's ${kind.name} capture option." }
      listOf("-P", "plugin:$id:$option=$path")
    } },
  )
}

/** Compile with the project's configured host and the Kotlin compiler on the test runtime classpath. */
public fun assertCompiler(
  source: String,
  fir: SnapshotExpectation? = null,
  ir: SnapshotExpectation? = null,
  record: SnapshotRecording = configuredRecording(),
  recordVersion: Boolean = configuredRecordVersion(),
  location: InlineSnapshotLocation? = null,
  bytecode: SnapshotExpectation? = null,
  diagnostics: SnapshotExpectation? = null,
  additionalSources: List<CompilerSource> = emptyList(),
) {
  assertCompiler(source, KotlinCompilerVersion.VERSION, configuredCompiler(), fir, ir, record, recordVersion, location, bytecode, diagnostics, additionalSources)
}

/** Check compilation and diagnostics with the project's configured compiler. */
public fun assertCompiler(
  source: String,
  record: SnapshotRecording = configuredRecording(),
  recordVersion: Boolean = configuredRecordVersion(),
  block: CompilerExpectations.() -> Unit,
) {
  assertCompiler(source, KotlinCompilerVersion.VERSION, configuredCompiler(), record = record, recordVersion = recordVersion, block = block)
}

internal fun configuredRecording(): SnapshotRecording {
  val value = System.getProperty("knapshots.compiler.record", "none")
  return try { SnapshotRecording.valueOf(value.uppercase(java.util.Locale.ROOT)) }
  catch (_: IllegalArgumentException) { error("Invalid knapshots.compiler.record '$value'; use none, missing, failed, or all.") }
}

internal fun configuredRecordVersion(): Boolean =
  System.getProperty("knapshots.compiler.recordVersion", "false").toBooleanStrict()
