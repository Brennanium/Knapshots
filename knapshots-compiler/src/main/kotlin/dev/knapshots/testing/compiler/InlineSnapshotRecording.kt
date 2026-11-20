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

/** One inline assertion in a batch that must be committed together. */
public data class InlineSnapshotUpdate(
  val location: InlineSnapshotLocation,
  val assertionName: String,
  val values: Map<String, String>,
  val expectedSource: String?,
)

/** Record several inline assertions in one source file, without leaving partial updates. */
public fun recordInlineSnapshotBatch(updates: List<InlineSnapshotUpdate>, compilerVersion: String, recordVersion: Boolean = false) {
  require(updates.isNotEmpty()) { "At least one inline snapshot update is required." }
  require(updates.all { it.location.file.toAbsolutePath().normalize() == updates.first().location.file.toAbsolutePath().normalize() }) {
    "Batched inline snapshots must belong to the same source file."
  }
  require(updates.all { it.values.isNotEmpty() && it.assertionName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && it.values.keys.all { name -> name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) } }) {
    "Invalid inline snapshot update."
  }
  InlineCompilerSnapshots.writeBatch(updates, compilerVersion, recordVersion)
}
