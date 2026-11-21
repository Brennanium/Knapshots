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

Knapshots uses the test task's runtime classpath and the loaded Kotlin compiler version. If the target Kotlin compiler Gradle plugin is already applied and exposes its compiler ID, the ID can be inferred. You can then start with just the source:

```kotlin
assertCompiler("class Example")
```

On the first run Knapshots records the available FIR and IR captures and diagnostics inline, then fails the test so you can review and rerun it. A host reports which phase captures it supports; if none are configured, the assertion records diagnostics. For a custom host, `withDefaultSnapshotKinds(setOf(CompilerSnapshotKind.FIR, CompilerSnapshotKind.IR))` declares the available default captures. Bytecode is only captured when explicitly requested.

For FIR, IR, or bytecode, call `fir()`, `ir()`, or `bytecode()` inside the assertion block to record their output. The existing `fir`, `ir`, and `bytecode` parameters remain available. Use `record = SnapshotRecording.MISSING` to record missing inline snapshots, then review the changes and rerun the test. To set a default for a test project, use `knapshots { recording.set("missing") }` or `-Pknapshots.recording=missing`; an explicit assertion argument overrides it. `recordCompilerVersion.set(true)` or `-Pknapshots.recordCompilerVersion=true` records version-specific overrides. The default is `missing` when no recording mode is configured; set `none` to compare without creating or changing snapshots. An explicit `CompilerHost` remains available for integrations that need a different compiler invocation. The embedded host runs the compiler on the test runtime classpath; it does not download another compiler when a version is requested.

### Output and diagnostics

Input-only assertions record their default output and diagnostics the first time they run:

```kotlin
assertCompiler(compilerSource)
assertKsp(processorSource)
assertEditor(editorSource)
```

KSP records generated source and diagnostics; editor assertions record IDE diagnostics. Compiler assertions record the host's available default FIR and IR captures and compiler diagnostics. A clean diagnostics snapshot is empty. If an error prevents a compiler phase from being captured, the available diagnostics are still recorded. The resulting expectations are inserted into the test so subsequent runs compare them.

Use the trailing block when a test needs a narrower check. For example, `fails()` checks only that compilation fails; it does not prescribe an error message:

```kotlin
assertCompiler(invalidSource) {
  fails()
}

assertKsp(invalidSource) {
  diagnostics { error("Expected an eligible declaration", line = 3) }
}
```

`succeeds()` asserts success. `diagnostics()` records only diagnostics, and `diagnostics { snapshot(...) }` compares or re-records them explicitly. These forms do not require an outcome assertion. A line without a file refers to the first source; use `file = "Other.kt"` for another source. A `column` can be checked when the host provides one. Embedded compiler snapshots underline a same-line diagnostic range when Kotlin supplies its exclusive end position, and otherwise show a caret at the start. The Gradle compiler host currently reports failed build output as one diagnostic rather than individual source positions.

For inline compiler, KSP, and editor assertions, recording modes are `missing` (the default), `failed`, `all`, and `none`. Configure them through `knapshots { recording.set(...) }`, `-Pknapshots.recording=...`, or the assertion's `record` argument. Recording writes the test and fails it for review. In `none` mode, an input-only assertion with no saved snapshot fails instead of passing without an expectation. Compiler and KSP snapshots also accept a `SnapshotExpectation` for version-specific output. The parameter-based compiler and KSP snapshot APIs remain available.

A host can use `temporaryCompilerHost` to receive a fresh temporary working directory for each compilation; Knapshots removes it when the host finishes. A caller-supplied directory is a possible future override for inspecting output or preparing project files. Its ownership, existing-file behavior, and cleanup rules are not defined yet.

This setup is experimental and requires the Knapshots Gradle plugin and compiler module. Knapshots' own tests use a small [internal compiler plugin](TEST-FIXTURES.md) to exercise diagnostics, FIR/IR phase observations, IR transformation, inline recording, bytecode, and both compiler hosts. KPaths also provides an opt-in cross-project integration test against a separate real compiler plugin.

## Expectations for different Kotlin versions

An output shared across versions can be written directly, such as `fir("...")`. When the output differs, put its expectations in a versioned block:

```kotlin
assertCompiler(source) {
  fir {
    default("shared FIR")
    versioned("2.5.*", "FIR for Kotlin 2.5")
  }
  diagnostics {
    default("shared diagnostics")
    versioned("2.5.*", "diagnostics for Kotlin 2.5")
  }
}
```

`default(...)` is required within a versioned snapshot. Exact selectors, minor-family selectors such as `2.5.*`, and comparison ranges such as `>=2.4.20 <2.5.0` are supported. A minor-family selector includes preview versions in that family. For an unqualified release boundary, `<2.5.0` excludes all `2.5.0` previews, and `>=2.5.0` includes them; an explicit preview boundary such as `>=2.5.0-dev-42` uses preview ordering. Exact matches take precedence, followed by a uniquely narrower matching selector. A narrower selector must describe a strict subset of the broader one. Equivalent or incomparable overlapping selectors fail rather than guessing. Invalid selectors are rejected even when they do not match.

Leave a value out to record it on the compiler version that selects it:

```kotlin
assertCompiler(source) {
  fir {
    default()
    versioned("2.5.*")
    versioned("2.6.*")
  }
}
```

On Kotlin 2.5, only the `2.5.*` placeholder is selected. Missing placeholders for other versions are ignored; with recording disabled, a missing selected placeholder fails. Recording preserves other versions and fails the test so the change can be reviewed. `ir` and explicitly requested `bytecode` support the same blocks. The existing `SnapshotExpectation` API remains available.

To change the outcome or several expectations together, use assertion-level version blocks:

```kotlin
assertCompiler(source) {
  ir("IR shared by both versions")
  default {
    fails()
    diagnostics { error("Unsupported declaration") }
  }
  versioned("2.5.*") {
    succeeds()
    fir("FIR for Kotlin 2.5")
    diagnostics { default("") }
  }
}
```

Each top-level expectation is a default: `ir("...")` is equivalent to `default { ir("...") }`. A selected version block overrides only the kinds it declares; other kinds retain their defaults. In this example, the versioned diagnostics replace the default error expectation, and the default IR remains active. Defining the same kind twice at the default level, or declaring a selector twice at the assertion level, is an error. Selection happens before compilation so unselected captures are not requested. The snapshot selectors select expectations for the compiler already running the test; they do not choose or install a compiler.

## Configuring editor assertions today

With the JUnit integration and a project-configured host, a test can call `assertEditor(source)` without passing a host. Input-only assertions snapshot the IDE diagnostics; when using focused diagnostic checks, unexpected errors fail by default. Reference resolution and expected diagnostics can be asserted in the block. The Gradle integration can use the test task's runtime classpath and infer selected version information. It still needs enough project configuration to apply the target Gradle plugin, locate any required IDE plugin, and launch the compatible adapter.

Editor diagnostic snapshots use the input-only, `diagnostics()`, and `diagnostics { snapshot(...) }` forms shown above. The IDE supplies the locations and message text.

### Expectations for IDE builds

Use the same `default` and `versioned` shape for editor diagnostics:

```kotlin
assertEditor(source) {
  diagnostics {
    default()
    versioned("AI-262.*")
  }
}
```

An empty placeholder is recorded only when selected; inactive placeholders are ignored. Selectors accept exact product-prefixed IDE builds such as `AI-262.10968.63.2622.16730706`, and build families such as `AI-262.*` and `IU-263.*`. Android Studio also supports exact release numbers such as `2026.2.2.8`, release families such as `2026.2.2.*`, and release names such as `Rabbit` or `Rabbit 2`. `Rabbit` includes numbered Rabbit releases; `Rabbit 2` targets that release line. `AI` identifies Android Studio, while IntelliJ editions use their own product codes. Exact builds and exact release numbers take precedence over families. Nested families choose the narrower match, so `Rabbit 2` wins over `Rabbit` and `2026.2.2.*` wins over `2026.2.*`. Incomparable matching selectors and multiple matching exact selectors fail rather than guessing. Comparison ranges are not yet supported.

The compatibility task passes release metadata for its verified IDE build to the test driver. For a locally selected Android Studio outside that task, a release-number or name selector looks up its exact build in JetBrains' public release feed and caches the result in the test process. Build-only selectors do not need a feed lookup. If release metadata is unavailable or doesn't match the selected build, the assertion fails rather than silently choosing the default. Custom hosts can provide verified metadata using `ideRelease()`.

Top-level `default { ... }` groups default diagnostics and reference expectations. A matching `versioned("AI-262.*") { ... }` replaces diagnostics as a group and replaces references by expression, while retaining unspecified defaults. Duplicate default diagnostics or the same default reference fail. Only references needed by the selected build are sent to the IDE. A custom host must implement `ideBuild()` to provide the actual product-prefixed IDE build; versioned assertions fail clearly if it is unavailable. The standard Gradle host reads `build.txt` from the selected IDE installation, and the lightweight IntelliJ fixture reads the running IDE's build. This is the IDE build, not the Kotlin compiler version configured in the temporary project.

### Why recording uses a worker

Recording rewrites Kotlin test source using a Kotlin parser. The lightweight IDE fixture already runs with IDE and Kotlin-plugin classes on its classpath; loading the recorder's compiler classes into that same process can cause incompatible class versions to collide. Knapshots instead starts a small persistent Java subprocess with an isolated recorder classpath. The editor test sends it the source location and snapshot text; the worker rewrites the test and returns the result. It does not launch another IDE or run the editor assertion again. The worker starts only when recording is needed; ordinary comparisons do not use it.

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

### Quick fixes in a light fixture

A single diagnostic can offer several quick fixes. Assert each fix by its displayed description and compare the source it produces:

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

With recording enabled, Knapshots records each `fixedSource()` and the source diagnostics. Each fix starts from the original source, and all snapshots are written together. Specify `line = 2` on `quickFix` when the same description appears at more than one diagnostic; missing or ambiguous matches fail. You can also assert the diagnostics explicitly using `diagnostics { ... }`.

This is an initial light-fixture implementation: it discovers intentions at diagnostic locations and snapshots the primary source only. Multi-file fixes and the imported-project editor host are not supported yet. Quick-fix labels and formatting can change between IDE versions; top-level `versioned` blocks can select different expectations.

### Reading snapshot failures

Text snapshot failures show the changed lines with nearby context. The hunk header gives line numbers for the expected and actual snapshots:

```diff
− expected / + actual
@@ -1,3 +1,3 @@
  class Person {
−   val name: String
+   var name: String
  }
```

Trailing spaces appear as `·`, trailing tabs as `⇥`, and carriage returns as `␍`. A missing final newline is called out separately. This format is shared by compiler, KSP, editor, and text snapshot assertions. For exceptionally large changed sections, Knapshots bounds the comparison work and may show the whole changed section instead of a minimal diff.
