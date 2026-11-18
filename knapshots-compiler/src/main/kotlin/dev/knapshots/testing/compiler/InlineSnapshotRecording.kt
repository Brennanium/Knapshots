package dev.knapshots.testing.compiler

/** Records named snapshot arguments on an assertion, preserving the source safety checks shared by compiler integrations. */
public fun recordInlineSnapshots(
  location: InlineSnapshotLocation,
  assertionName: String,
  values: Map<String, String>,
  compilerVersion: String,
  recordVersion: Boolean = false,
  expectedSource: String? = null,
) {
  require(values.isNotEmpty()) { "At least one snapshot value is required." }
  require(assertionName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid assertion name." }
  require(values.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) { "Invalid snapshot argument name." }
  val captures = values.mapKeys { CompilerSnapshotKind(it.key) }
  InlineCompilerSnapshots.write(location.file, location.line, captures, compilerVersion, recordVersion,
    expectedSource, compiledSourceLine = true, assertionName = assertionName,
    argumentNames = captures.keys.associateWith { it.name })
}
