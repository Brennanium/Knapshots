# Testing KSP processors

`knapshots-ksp` runs a processor against Kotlin source and can compare its generated Kotlin and Java files as one inline snapshot. The snapshot includes each relative file name, so a rename or an unexpected additional source fails the assertion.

```kotlin
import dev.knapshots.testing.ksp.assertKsp

@Test fun `generates a catalog`() {
  assertKsp("""
    @GenerateCatalog data class Person(val name: String)
  """)
}
```

The first run records generated source and diagnostics inline, then fails so you can review and rerun. Missing snapshots record by default. Use `record = SnapshotRecording.NONE` to compare without writing, or select `failed` or `all` for re-recording. The Knapshots Gradle plugin's `recording` setting applies to compiler, KSP, and editor assertions together. `generated(SnapshotExpectation(...))` supports compiler-version overrides, as does the existing `generated = SnapshotExpectation(...)` parameter. Set `knapshots.ksp.recordVersion=true` to record a version-specific override for an existing default snapshot.

Configure the test JVM once with `knapshots.ksp.version`, `knapshots.ksp.processorClasspath`, and `knapshots.ksp.classpath`. The classpath values use the host platform's path separator. Include the processor and all of its runtime dependencies on the processor classpath; put types imported by test sources on the compilation classpath. The Kotlin compiler version defaults to the compiler bundled with Knapshots and can be overridden with `knapshots.ksp.kotlinVersion`. Alternatively, register one `KspHostProvider` service or pass a `GradleKspHost` explicitly.

For an outcome-only test, use `fails()` or `succeeds()`. To record only diagnostics, use the same diagnostics DSL as compiler and editor tests:

```kotlin
assertKsp("""@GenerateCatalog class Example""") {
  diagnostics()
}
```

Recording replaces `diagnostics()` with a snapshot block. Use `diagnostics { snapshot() }` to start with the block explicitly, or check a specific message with `diagnostics { error("Expected an eligible declaration", line = 1) }`. Recording generated source and diagnostics together is atomic: if either rewrite fails, neither is written. Place snapshot calls on separate lines so their source locations are unambiguous.

`additionalSources` accepts named `KspSource` files. Use `dependencies` to compile a separate JVM module before processing the main source; KSP sees that module's binary metadata. For a test-owned processor, `processorProjects` accepts sources and provider class names, builds the processor, and registers its service descriptor. Both kinds of projects use the configured classpaths.

Set `mainClass` to execute the compiled fixture after processing. The assertion checks compilation and execution independently; set `executionSucceeds = false` when testing an expected runtime failure.

The existing `expectedDiagnostics` and `diagnostics` parameters remain available for structured diagnostic checks and raw-output substring checks. KSP parses Kotlin and processor command-line diagnostics; unrecognized output remains available as raw text. Diagnostics with a line but no file in the trailing block refer to the first source file.

The built-in host uses temporary Gradle JVM projects, resolves the supplied Kotlin and KSP versions, and cleans them up afterwards. It does not yet cover arbitrary Gradle project configuration, multiplatform compilations, or snapshotting non-source outputs.

The KPaths checkout includes real-processor integration tests when `-Pknapshots.checkout=/path/to/Knapshots` is set. They exercise generated source, processor diagnostics, binary dependencies, runtime execution, and a separately built processor project.

Knapshots tests `assertKsp` against a [test-only processor](TEST-FIXTURES.md) in its own repository. It produces actual generated source and diagnostics without requiring a KPaths checkout.
