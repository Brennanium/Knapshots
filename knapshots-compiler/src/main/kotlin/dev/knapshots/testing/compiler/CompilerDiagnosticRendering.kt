package dev.knapshots.testing.compiler

/** Renders diagnostics against the submitted source; compiler columns are one-based UTF-16 offsets. */
public fun renderCompilerDiagnostics(sources: List<CompilerSource>, diagnostics: List<CompilerDiagnostic>): String {
  if (diagnostics.isEmpty()) return ""
  val sourceByName = sources.associateBy { it.fileName.replace('\\', '/') }
  val mapped = diagnostics.map { diagnostic ->
    val match = diagnostic.location?.replace('\\', '/')?.let { Regex("^(.*):(\\d+)$").matchEntire(it) }
    val file = match?.groupValues?.get(1)
    val line = match?.groupValues?.get(2)?.toIntOrNull()
    Triple(diagnostic, file?.let(sourceByName::get), line)
  }
  val remaining = mapped.toMutableList()
  val output = mutableListOf<String>()
  sources.forEach { source ->
    if (sources.size > 1) output += "// FILE: ${source.fileName}"
    source.text.lines().forEachIndexed { index, text ->
      output += text
      val here = remaining.filter { it.second == source && it.third == index + 1 }
      here.forEach { (diagnostic) ->
        diagnostic.column?.let { column ->
          // Preserve tabs to align the caret with the source in monospace output.
          val prefix = text.take((column - 1).coerceAtLeast(0)).map { if (it == '\t') '\t' else ' ' }.joinToString("")
          output += "$prefix^"
        }
        val lines = diagnostic.message.lines()
        output += "${diagnostic.severity.name.lowercase()}: ${lines.first()}"
        output += lines.drop(1)
      }
      remaining.removeAll(here.toSet())
    }
  }
  remaining.forEach { (diagnostic) ->
    val position = diagnostic.location?.let { " at $it${diagnostic.column?.let { column -> ":$column" } ?: ""}" } ?: ""
    output += "${diagnostic.severity.name.lowercase()}$position: ${diagnostic.message}"
  }
  return output.joinToString("\n")
}
