# Testing KSP processors

`knapshots-ksp` runs a processor against Kotlin source and can compare its generated Kotlin and Java files as one inline snapshot. The snapshot includes each relative file name, so a rename or an unexpected additional source fails the assertion.

```kotlin
import dev.knapshots.testing.compiler.SnapshotExpectation
import dev.knapshots.testing.ksp.assertKsp

@Test fun `generates a catalog`() {
  assertKsp(
    source = """
      @GenerateCatalog data class Person(val name: String)
    """,
    generated = SnapshotExpectation("""
      // FILE: kotlin/example/PersonCatalog.kt
      package example
      // Generated source goes here.
    """),
  )
}
```

For a first snapshot, use `generated = snapshot()` (import `dev.knapshots.testing.compiler.snapshot`) and run with `knapshots.ksp.record=missing` as a test JVM system property. Recording writes the generated source into the test and fails so you can review it and rerun. `none`, `missing`, `failed`, and `all` follow the compiler assertion's recording modes. Set `knapshots.ksp.recordVersion=true` to record an exact Kotlin-version override for an existing default snapshot; `SnapshotExpectation` also accepts version selectors in its `versions` map. Source discovery and safe rewrites use the compiler assertion's shared implementation.

Configure the test JVM once with `knapshots.ksp.version`, `knapshots.ksp.processorClasspath`, and `knapshots.ksp.classpath`. The classpath values use the host platform's path separator. Include the processor and all of its runtime dependencies on the processor classpath; put types imported by test sources on the compilation classpath. The Kotlin compiler version defaults to the compiler bundled with Knapshots and can be overridden with `knapshots.ksp.kotlinVersion`. Alternatively, register one `KspHostProvider` service or pass a `GradleKspHost` explicitly.

```kotlin
assertKsp(
  source = """@GenerateCatalog class Example""",
  succeeds = false,
  diagnostics = listOf("expected diagnostic text"),
)
```

`additionalSources` accepts named `KspSource` files. Use `dependencies` to compile a separate JVM module before processing the main source; KSP sees that module's binary metadata. For a test-owned processor, `processorProjects` accepts sources and provider class names, builds the processor, and registers its service descriptor. Both kinds of projects use the configured classpaths.

Set `mainClass` to execute the compiled fixture after processing. The assertion checks compilation and execution independently; set `executionSucceeds = false` when testing an expected runtime failure.

For structured diagnostic checks, supply `expectedDiagnostics` with `KspDiagnosticExpectation` values:

```kotlin
assertKsp(
  source = "@GenerateCatalog class Example",
  succeeds = false,
  expectedDiagnostics = listOf(
    KspDiagnosticExpectation(
      KspDiagnosticSeverity.ERROR,
      "Expected an eligible declaration",
      file = "src/main/kotlin/Main.kt",
      line = 1,
    ),
  ),
)
```

Each expectation can specify severity, message text, relative source file, line, and column. Unexpected parsed errors fail the assertion. The Gradle host parses Kotlin and KSP command-line diagnostics; unrecognized output remains available as raw text and the `diagnostics` string list remains available for substring checks.

The built-in host uses temporary Gradle JVM projects, resolves the supplied Kotlin and KSP versions, and cleans them up afterwards. It does not yet cover arbitrary Gradle project configuration, multiplatform compilations, or snapshotting non-source outputs.

The KPaths checkout includes real-processor integration tests when `-Pknapshots.checkout=/path/to/Knapshots` is set. They exercise generated source, processor diagnostics, binary dependencies, runtime execution, and a separately built processor project.
