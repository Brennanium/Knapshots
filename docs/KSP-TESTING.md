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

`additionalSources` accepts named `KspSource` files. Diagnostics currently match substrings of Gradle output; they are not structured severity or source-location assertions. Compilation success is checked by default, and an expected failure must actually reach the KSP task. The built-in host uses a temporary Gradle JVM project, resolves the supplied Kotlin and KSP versions, and cleans it up afterwards. It does not yet cover custom Gradle project configuration, multiplatform compilations, processor resources, or snapshotting non-source outputs.

The KPaths checkout includes a real-processor integration test when `-Pknapshots.checkout=/path/to/Knapshots` is set. It verifies generated source and processor diagnostics through an actual downstream Gradle project.
