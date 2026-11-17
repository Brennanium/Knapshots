package dev.knapshots.testing.compiler

import dev.knapshots.testing.snapshot.compareSnapshot
import dev.knapshots.testing.snapshot.textSnapshotting

/** A named Kotlin source file supplied to a compiler integration. */
public data class CompilerSource(
  val fileName: String,
  val text: String,
)

/** A compiler invocation assembled from an assertion's expectations. */
public data class CompilerRequest(
  val sources: List<CompilerSource>,
  val compilerVersion: String,
  val captures: Set<CompilerSnapshotKind>,
)

/** A compiler diagnostic captured by an integration. */
public data class CompilerDiagnostic(
  val severity: CompilerDiagnosticSeverity,
  val message: String,
  val location: String? = null,
)

public enum class CompilerDiagnosticSeverity { ERROR, WARNING, INFO }

/** The result of one compiler invocation, including only the captures that were available. */
public data class CompilerResult(
  val succeeded: Boolean,
  val diagnostics: List<CompilerDiagnostic> = emptyList(),
  val captures: Map<CompilerSnapshotKind, String> = emptyMap(),
)

/** Implemented by a compiler backend; Knapshots does not prescribe a compiler API. */
public fun interface CompilerHost {
  public fun compile(request: CompilerRequest): CompilerResult
}

/**
 * Compiles one source file and checks its result.
 *
 * The expectation block must state whether compilation succeeds or fails. That makes a test for
 * an expected compiler error fail when the source unexpectedly compiles successfully.
 */
public fun assertCompiler(
  source: String,
  compilerVersion: String,
  compiler: CompilerHost,
  fileName: String = "Main.kt",
  block: CompilerExpectations.() -> Unit,
) {
  assertCompiler(
    sources = listOf(CompilerSource(fileName, source)),
    compilerVersion = compilerVersion,
    compiler = compiler,
    block = block,
  )
}

/** Compiles multiple source files and checks one unified compiler result. */
public fun assertCompiler(
  sources: List<CompilerSource>,
  compilerVersion: String,
  compiler: CompilerHost,
  block: CompilerExpectations.() -> Unit,
) {
  require(sources.isNotEmpty()) { "At least one compiler source is required." }
  require(compilerVersion.isNotBlank()) { "A compiler version is required." }
  sources.forEach { source ->
    require(source.fileName.isNotBlank()) { "A compiler source file name must not be blank." }
  }

  val expectations = CompilerExpectations().apply(block)
  val result = compiler.compile(
    CompilerRequest(
      sources = sources.map { source -> source.copy(text = source.text.trimIndent()) },
      compilerVersion = compilerVersion,
      captures = expectations.snapshotKinds(),
    ),
  )
  expectations.verify(result, compilerVersion)
}

/** Configures the result expected from [assertCompiler]. */
public class CompilerExpectations internal constructor() {
  private var expectedSuccess: Boolean? = null
  private val expectedDiagnostics = mutableListOf<ExpectedCompilerDiagnostic>()
  private val expectedSnapshots = linkedMapOf<CompilerSnapshotKind, SnapshotExpectation>()

  /** Expects the compiler to complete successfully. */
  public fun succeeds() {
    expectSuccess(true)
  }

  /** Expects compilation to fail. */
  public fun fails() {
    expectSuccess(false)
  }

  /** Checks individual diagnostics. Unmatched error diagnostics fail by default. */
  public fun diagnostics(block: CompilerDiagnosticExpectations.() -> Unit) {
    CompilerDiagnosticExpectations(expectedDiagnostics).apply(block)
  }

  /** Expects one named compiler capture, with optional compiler-version-specific output. */
  public fun snapshot(kind: CompilerSnapshotKind, expected: SnapshotExpectation) {
    check(expectedSnapshots.put(kind, expected) == null) {
      "A ${kind.name} compiler snapshot was already requested."
    }
  }

  /** Convenience for an exact, version-independent capture. */
  public fun snapshot(kind: CompilerSnapshotKind, expected: String) {
    snapshot(kind, SnapshotExpectation(expected))
  }

  /** Convenience for an FIR capture. */
  public fun fir(expected: SnapshotExpectation) {
    snapshot(CompilerSnapshotKind.FIR, expected)
  }

  /** Convenience for an exact, version-independent FIR capture. */
  public fun fir(expected: String) {
    fir(SnapshotExpectation(expected))
  }

  /** Convenience for an IR capture. */
  public fun ir(expected: SnapshotExpectation) {
    snapshot(CompilerSnapshotKind.IR, expected)
  }

  /** Convenience for an exact, version-independent IR capture. */
  public fun ir(expected: String) {
    ir(SnapshotExpectation(expected))
  }

  internal fun snapshotKinds(): Set<CompilerSnapshotKind> = expectedSnapshots.keys

  internal fun verify(result: CompilerResult, compilerVersion: String) {
    val failures = mutableListOf<String>()
    when (expectedSuccess) {
      true -> if (!result.succeeded) failures += "Expected compilation to succeed, but it failed."
      false -> if (result.succeeded) failures += "Expected compilation to fail, but it succeeded."
      null -> failures += "Specify whether compilation succeeds or fails using succeeds() or fails()."
    }
    verifyDiagnostics(result.diagnostics, failures)
    expectedSnapshots.forEach { (kind, expected) ->
      val actual = result.captures[kind]
      if (actual == null) {
        failures += "The compiler did not provide the requested ${kind.name} snapshot."
      } else {
        val difference = compareSnapshot(
          actual.trimEnd('\n', '\r'),
          expected.forVersion(compilerVersion).trimIndent().trimEnd('\n', '\r'),
          textSnapshotting,
        )
        if (difference != null) {
          failures += "${kind.name} snapshot for Kotlin $compilerVersion differs:\n" +
            "Expected:\n${difference.expected}\nActual:\n${difference.actual}"
        }
      }
    }
    if (failures.isNotEmpty()) {
      throw AssertionError(failures.joinToString("\n", prefix = "Compiler assertions failed:\n"))
    }
  }

  private fun expectSuccess(value: Boolean) {
    check(expectedSuccess == null) { "Compilation outcome can only be specified once." }
    expectedSuccess = value
  }

  private fun verifyDiagnostics(actual: List<CompilerDiagnostic>, failures: MutableList<String>) {
    val owner = IntArray(actual.size) { -1 }
    fun assign(expectedIndex: Int, visited: BooleanArray): Boolean {
      val expected = expectedDiagnostics[expectedIndex]
      for (index in actual.indices) {
        val diagnostic = actual[index]
        if (visited[index] || diagnostic.severity != expected.severity ||
          !diagnostic.message.contains(expected.messageContains) ||
          (expected.location != null && expected.location != diagnostic.location)) continue
        visited[index] = true
        if (owner[index] == -1 || assign(owner[index], visited)) {
          owner[index] = expectedIndex
          return true
        }
      }
      return false
    }

    expectedDiagnostics.forEachIndexed { index, expected ->
      if (!assign(index, BooleanArray(actual.size))) {
        failures += "Missing ${expected.severity} diagnostic containing '${expected.messageContains}'" +
          (expected.location?.let { " at $it" } ?: "")
      }
    }
    actual.filterIndexed { index, diagnostic ->
      owner[index] == -1 && diagnostic.severity == CompilerDiagnosticSeverity.ERROR
    }.forEach { diagnostic ->
      failures += "Unexpected ERROR${diagnostic.location?.let { " at $it" } ?: ""}: ${diagnostic.message}"
    }
  }
}

internal data class ExpectedCompilerDiagnostic(
  val severity: CompilerDiagnosticSeverity,
  val messageContains: String,
  val location: String?,
)

/** Builder for expected compiler diagnostics. */
public class CompilerDiagnosticExpectations internal constructor(
  private val expected: MutableList<ExpectedCompilerDiagnostic>,
) {
  public fun error(messageContains: String, location: String? = null) {
    expect(CompilerDiagnosticSeverity.ERROR, messageContains, location)
  }

  public fun warning(messageContains: String, location: String? = null) {
    expect(CompilerDiagnosticSeverity.WARNING, messageContains, location)
  }

  private fun expect(severity: CompilerDiagnosticSeverity, messageContains: String, location: String?) {
    require(messageContains.isNotBlank()) { "Expected diagnostic message must not be blank." }
    expected += ExpectedCompilerDiagnostic(severity, messageContains, location)
  }
}
