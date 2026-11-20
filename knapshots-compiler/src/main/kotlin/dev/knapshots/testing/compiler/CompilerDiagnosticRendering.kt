package dev.knapshots.testing.compiler

import dev.knapshots.testing.snapshot.DiagnosticSource
import dev.knapshots.testing.snapshot.SourceDiagnostic
import dev.knapshots.testing.snapshot.renderSourceDiagnostics

/** Renders diagnostics against the submitted source; compiler columns are one-based UTF-16 offsets. */
public fun renderCompilerDiagnostics(sources: List<CompilerSource>, diagnostics: List<CompilerDiagnostic>): String =
  renderSourceDiagnostics(sources.map { DiagnosticSource(it.fileName, it.text) }, diagnostics.map { diagnostic ->
    val match = diagnostic.location?.replace('\\', '/')?.let { Regex("^(.*):(\\d+)$").matchEntire(it) }
    val fallback = diagnostic.location?.let { "$it${diagnostic.column?.let { column -> ":$column" } ?: ""}" }
    SourceDiagnostic(diagnostic.severity.name, diagnostic.message, match?.groupValues?.get(1),
      match?.groupValues?.get(2)?.toIntOrNull(), diagnostic.column, fallback)
  })
