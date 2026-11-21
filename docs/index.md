# Knapshots

Test the code your Kotlin tools produce, the diagnostics they report, and what developers see in their editor.

```kotlin
assertCompiler("""
  @GenerateGreeting class Person
""")
```

Run the test to record the available compiler output and diagnostics inline. Review the result, then run it again to check future changes. The same input-first workflow is available for [KSP processors](KSP-TESTING.md) and [editor diagnostics](EDITOR.md).

Knapshots also compares ordinary values using [snapshot strategies](SNAPSHOTS.md), and can inspect generated JVM bytecode. When a snapshot changes, the failure shows the relevant lines with context.

## Choose what to test

- **[Snapshots](SNAPSHOTS.md)** compare a value with an expected representation.
- **[Compiler assertions](COMPILER.md)** check compilation, diagnostics, and compiler plugin output.
- **[KSP assertions](KSP-TESTING.md)** check generated source and diagnostics from a processor.
- **[Editor assertions](EDITOR.md)** check diagnostics and reference resolution in an IDE.
- **[Compatibility testing](COMPATIBILITY.md)** runs a project's checks against selected Kotlin and IDE versions.

Start with [Getting started](GETTING-STARTED.md) to add Knapshots to a test project. Knapshots is based on Point-Free's [SnapshotTesting](https://github.com/pointfreeco/swift-snapshot-testing) and [MacroTesting](https://github.com/pointfreeco/swift-macro-testing).
