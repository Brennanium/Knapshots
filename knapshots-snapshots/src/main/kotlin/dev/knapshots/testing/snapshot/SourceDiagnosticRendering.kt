package dev.knapshots.testing.snapshot

/** A source used when presenting a diagnostic in context. */
public data class DiagnosticSource(val fileName: String, val text: String)

/** A source position is optional because some hosts cannot associate a diagnostic with a file. */
public data class SourceDiagnostic(
  val severity: String,
  val message: String,
  val fileName: String? = null,
  val line: Int? = null,
  val column: Int? = null,
  val fallbackLocation: String? = null,
  /** One-based, exclusive end position, when available. */
  val endLine: Int? = null,
  val endColumn: Int? = null,
)

/** Render source diagnostics with one-based UTF-16 column positions. */
public fun renderSourceDiagnostics(sources: List<DiagnosticSource>, diagnostics: List<SourceDiagnostic>): String {
  if (diagnostics.isEmpty()) return ""
  val sourceByName = sources.associateBy { it.fileName.replace('\\', '/') }
  val mapped = diagnostics.map { Triple(it, it.fileName?.replace('\\', '/')?.let(sourceByName::get), it.line) }
  val remaining = mapped.toMutableList()
  val output = mutableListOf<String>()
  sources.forEach { source ->
    if (sources.size > 1) output += "// FILE: ${source.fileName}"
    source.text.lines().forEachIndexed { index, text ->
      output += text
      val here = remaining.filter { it.second == source && it.third == index + 1 }
      here.forEach { (diagnostic) ->
        diagnostic.column?.let { column ->
          val prefix = text.take((column - 1).coerceAtLeast(0)).map { if (it == '\t') '\t' else ' ' }.joinToString("")
          val end = diagnostic.endColumn
          val width = if (diagnostic.endLine == diagnostic.line && end != null &&
            column in 1..text.length + 1 && end > column && end <= text.length + 1) end - column else 1
          output += "$prefix^${"~".repeat(width - 1)}"
        }
        val lines = diagnostic.message.lines()
        output += "${diagnostic.severity.lowercase()}: ${lines.first()}"
        output += lines.drop(1)
      }
      remaining.removeAll(here.toSet())
    }
  }
  remaining.forEach { (diagnostic) ->
    val position = diagnostic.fallbackLocation?.let { " at $it" } ?: ""
    output += "${diagnostic.severity.lowercase()}$position: ${diagnostic.message}"
  }
  return output.joinToString("\n")
}
