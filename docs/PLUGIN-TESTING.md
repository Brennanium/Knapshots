# Testing compiler plugins

A Kotlin compiler plugin can participate in both a Gradle build and an IDE. Naming those pieces clearly helps readers understand which part a test exercises:

| Part | What it does | Suggested name |
| --- | --- | --- |
| Gradle plugin | Configures a project's Kotlin compilations to load the compiler plugin. | `example.gradle-plugin` for the Gradle plugin ID |
| Compiler plugin | Generates declarations or otherwise extends the Kotlin compiler during a build. | `example.compiler-plugin` for the compiler plugin ID |
| Compiler plugin artifact | The JAR containing the compiler plugin that the build loads. | `example:example-compiler-plugin-jvm` |
| IDE plugin | Makes the compiler plugin's behavior available in the IDE. It might be provided through KEFS or by a standalone IDE plugin. | Describe the mechanism, for example “IDE plugin through KEFS” |

These are suggested names, not naming requirements. A Gradle plugin ID and a Kotlin compiler plugin ID can have different names and are not Maven coordinates. A Maven coordinate identifies the JAR; the compiler plugin ID identifies the plugin after Kotlin loads it. Versions are supplied separately when resolving the JAR.

KPaths is one example: its experimental Gradle plugin ID is `dev.kpaths.gradle-plugin`, its compiler plugin ID is `dev.kpaths.compiler-plugin`, and its compiler artifact is `io.github.brennanium:kpaths-compiler-plugin-jvm`. KPaths supplies an IDE-compatible build through KEFS. The two JARs are built for different Kotlin compilers, but KPaths gives them the same Maven group and artifact name because KEFS' default lookup matches the build JAR's artifact name. They live in separate repositories; the IDE artifact's version includes both the IDE Kotlin compiler version and the KPaths version. See the [KPaths repository](https://github.com/Brennanium/KPaths) for its implementation.

## What each assertion needs

| Assertion | What it checks | What the current integration needs |
| --- | --- | --- |
| `assertSnapshot` | A captured value compared with an expected value. | A snapshot strategy, a value, and an expectation. No compiler or IDE is required. |
| `assertCompiler` | Compilation outcome, diagnostics, and optionally compiler output such as FIR or IR. | Source and project-wide compiler configuration. The embedded host uses the compiler on the test runtime classpath; FIR/IR capture also needs the plugin’s capture options. |
| `assertEditor` | What the IDE reports for source, such as reference resolution and diagnostics. | A configured editor host, the project dependencies and plugin, a compatible IDE and Knapshots adapter, and any IDE integration the plugin needs. For KEFS, that includes KEFS and a compatible IDE plugin artifact. |

The distinction matters: a successful compiler assertion does not establish that the IDE can resolve the generated declaration, and an editor assertion does not test the resulting program's runtime behavior.

## Configuring compiler assertions today

Configure the compiler plugin once in Gradle. The artifact is the plugin JAR under test; the optional FIR and IR settings are the plugin-specific options that write those captures:

```kotlin
knapshots {
  compilerPlugin {
    artifact.set(layout.projectDirectory.file("build/libs/example-compiler-plugin.jar"))
    id.set("example.compiler-plugin")
    firOption.set("firSnapshot")
    irOption.set("irSnapshot")
  }
}
```

Knapshots uses the test task's runtime classpath and the loaded Kotlin compiler version. If the target Kotlin compiler Gradle plugin is already applied and exposes its compiler ID, the ID can be inferred. You can then check compilation without a host or version argument:

```kotlin
assertCompiler("class Example") {
  succeeds()
}
```

For FIR or IR, pass the desired expectations as `fir` and `ir`. Use `record = SnapshotRecording.MISSING` to record missing inline snapshots, then review the changes and rerun the test. To set a default for a test project, use `knapshots { recording.set("missing") }` or `-Pknapshots.recording=missing`; an explicit assertion argument overrides it. `recordCompilerVersion.set(true)` or `-Pknapshots.recordCompilerVersion=true` records version-specific overrides. The default is `none` when no recording mode is configured. An explicit `CompilerHost` remains available for integrations that need a different compiler invocation. The embedded host runs the compiler on the test runtime classpath; it does not download another compiler when a version is requested.

A host can use `temporaryCompilerHost` to receive a fresh temporary working directory for each compilation; Knapshots removes it when the host finishes. A caller-supplied directory is a possible future override for inspecting output or preparing project files. Its ownership, existing-file behavior, and cleanup rules are not defined yet.

This setup is experimental and requires the Knapshots Gradle plugin and compiler module. KPaths' opt-in cross-project fixture exercises successful compilation, diagnostics, FIR/IR snapshots, version-specific expectations, and inline recording against its real compiler plugin.

## Configuring editor assertions today

With the JUnit integration and a project-configured host, a test can call `assertEditor(source)` without passing a host. Unexpected errors fail by default; reference resolution and expected diagnostics can be asserted in its block. The Gradle integration can use the test task's runtime classpath and infer selected version information. It still needs enough project configuration to apply the target Gradle plugin, locate any required IDE plugin, and launch the compatible adapter.

The [compatibility guide](COMPATIBILITY.md#editor-assertions-in-junit) describes the experimental Gradle settings and what is currently inferred. The adapter has not been published; a local archive or the existing checkout-based launcher is still needed. Other IDE plugin mechanisms require their own integration; enabling KEFS is not a requirement for compiler assertions.

For runtime or bytecode checks, `EmbeddedCompilerHost.compile` returns class files alongside diagnostics. Call `result.classLoader()` to load a compiled class or inspect its `.class` resource after the temporary compiler directory has been removed.

## Inspecting JVM bytecode

Add `knapshots-bytecode` to inspect the classes produced by a compiler host. `ClassBytecode.read(bytes)` exposes methods, method calls, field accesses, type instructions, and referenced owners for focused structural checks. `renderJvmBytecode(bytes)` and `jvmBytecodeSnapshotting` provide a textual class-file snapshot without line numbers or stack frames.

```kotlin
val bytes = result.classFiles.getValue("example/Person.class")
val person = ClassBytecode.read(bytes)
val getter = person.method("getName", "()Ljava/lang/String;")
assertSnapshot(bytes, expected = expectedBytecode, using = jvmBytecodeSnapshotting)
```

For version-aware compiler assertions, wrap a host with `withJvmBytecodeSnapshot("example.Person")`, then request `bytecode(expected)` in the `assertCompiler` block alongside `fir(...)` and `ir(...)`. For inline recording, pass the wrapped host to `assertCompiler` and provide `bytecode = snapshot()`; recording follows the same modes and version selection as FIR and IR. The bytecode host must identify the class to capture:

```kotlin
val compiler = EmbeddedCompilerHost().withJvmBytecodeSnapshot("example.Person")
assertCompiler(
  source = "package example\nclass Person(val name: String)",
  compilerVersion = kotlinVersion,
  compiler = compiler,
  bytecode = snapshot(),
  record = SnapshotRecording.ALL,
)
```

If a project supplies a `CompilerHostProvider`, it can wrap that host once for its tests. A host that does not provide bytecode captures cannot satisfy the bytecode expectation. Bytecode is compiler-version-specific, so choose expectations appropriate for the compiler under test.

## Choosing a compiler host

`EmbeddedCompilerHost` calls the compiler in the test process. It is a good default for frequent compiler-plugin assertions and uses the Kotlin compiler on the test runtime classpath. `GradleCompilerHost`, from `knapshots-compiler-gradle`, creates a disposable Kotlin/JVM project and resolves the Kotlin Gradle plugin for the requested version. It is useful for checking compiler options and dependencies through a real Gradle build, at the cost of project configuration and build time.

Both implement `CompilerHost`, so either can drive `assertCompiler`. Configure a Gradle host with the test classpath, compiler arguments, and, for FIR/IR captures, a function that maps the requested captures to plugin arguments. It returns compiled classes and any requested capture files. A compilation failure currently reports the Gradle build output as one error diagnostic; it does not yet parse individual Kotlin diagnostic locations. Tests that require custom multi-module projects or need to inspect a project after the build should continue to create those projects explicitly.

## Lightweight IntelliJ fixtures

`knapshots-intellij-fixture` adapts IntelliJ's `BasePlatformTestCase` and `CodeInsightTestFixture` to `assertEditor`. Extend `EditorFixtureTestCase` to use its source-hosting assertion, or use `IntellijEditorObservations` with an existing fixture. Provide the IntelliJ platform test framework and the IDE-compatible compiler/plugin setup in your own test project.

This optional adapter is a separate build because it compiles against a selected IDE. From a Knapshots checkout, `./gradlew -p knapshots-intellij-fixture test -Pknapshots.idePath=/path/to/ide` verifies it without launching a full IDE. A composite consumer can include the Knapshots build and `knapshots-intellij-fixture`, then depend on `dev.knapshots:knapshots-intellij-fixture`. These lightweight tests do not prove Gradle import, KEFS loading, or full IDE behavior.
