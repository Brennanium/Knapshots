# Testing editor behavior

A compiler test can show that generated code compiles. An editor test checks what a developer sees while writing source: whether a reference resolves and which diagnostics appear.

```kotlin
assertEditor("""
  val name = Person.Paths.name
""") {
  reference("Person.Paths.name").resolves()
}
```

An input-only `assertEditor(source)` records the editor's diagnostics inline. You can also check a specific error with `diagnostics { error("…", line = 1) }`. Unexpected errors fail unless included in the expectations.

## Try an editor assertion

Add `knapshots-editor-junit` and `knapshots-ide-runner`, and apply the Knapshots Gradle plugin. Configure the Gradle plugin under test and its IDE integration once for the test project. The [editor setup guide](COMPATIBILITY.md#editor-assertions-in-junit) shows the current configuration and which settings are inferred.

The full imported-project host currently needs an IDE-compatible adapter archive or a Knapshots checkout from which to build the adapter. The adapter is not published yet. The optional [lightweight fixture](PLUGIN-TESTING.md#lightweight-intellij-fixtures) runs against an IntelliJ test fixture without importing a full project.

## Check a quick fix

In a lightweight fixture, record the result of each offered fix independently:

```kotlin
assertEditor(source) {
  quickFix("Create local variable") {
    fixedSource()
  }
  quickFix("Create field") {
    fixedSource()
  }
}
```

Each fix starts with the original source. Recording writes the fixed-source snapshots and, unless you specify diagnostics separately, the diagnostic snapshot together. A missing or ambiguous description fails; use `line = 2` to restrict the search to one diagnostic line. [IDE build expectations](PLUGIN-TESTING.md#expectations-for-ide-builds) let a test account for changes in fix descriptions or output.

Quick-fix support is experimental. It currently finds actions at diagnostic locations in the light fixture and snapshots the primary file only. The imported-project host and multi-file quick fixes are not supported yet. IntelliJ quick fixes are distinct from Kotlin compiler diagnostics.
