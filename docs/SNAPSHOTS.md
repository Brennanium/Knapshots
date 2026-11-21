# Snapshot testing

A snapshot captures a value in a form you can compare. For text, `assertSnapshot` checks the captured string against an expected string:

```kotlin
import dev.knapshots.testing.snapshot.assertSnapshot
import dev.knapshots.testing.snapshot.textSnapshotting

assertSnapshot(
  value = greeting,
  expected = "Hello, Ada!",
  using = textSnapshotting,
)
```

If the text changes, the failure shows the changed lines and their surrounding context:

```diff
− expected / + actual
@@ -1,1 +1,1 @@
− Hello, Ada!
+ Hello, Grace!
```

Trailing spaces, tabs, carriage returns, and a missing final newline are made visible in the diff. Very large changed sections use a bounded comparison and may be less compact.

## Snapshot other values

A `Snapshotting<Value, Format>` strategy turns a value into a comparable format. Adapt an existing strategy with `pullback`:

```kotlin
val lines = textSnapshotting.pullback<List<String>> { values ->
  values.joinToString("\n")
}

assertSnapshot(listOf("Ada", "Grace"), "Ada\nGrace", lines)
```

You can also supply a `Diffing<Format>` to define how another format is compared. Knapshots currently supplies a text strategy; automatic structured-value formatting is not included.

Compiler, KSP, and editor assertions build on these comparisons and can [record snapshots inline](PLUGIN-TESTING.md#output-and-diagnostics).
