# Testing compiler plugins

A Kotlin compiler plugin can participate in both a Gradle build and an IDE. Naming those pieces
clearly helps readers understand which part a test exercises:

| Part | What it does | Suggested name |
| --- | --- | --- |
| Gradle plugin | Configures a project's Kotlin compilations to load the compiler plugin. | `example.gradle-plugin` for the Gradle plugin ID |
| Compiler plugin | Generates declarations or otherwise extends the Kotlin compiler during a build. | `example.compiler-plugin` for the compiler plugin ID |
| Compiler plugin artifact | The JAR containing the compiler plugin that the build loads. | `example:example-compiler-plugin-jvm` |
| IDE plugin | Makes the compiler plugin's behavior available in the IDE. It might be provided through KEFS or by a standalone IDE plugin. | Describe the mechanism, for example “IDE plugin through KEFS” |

These are suggested names, not naming requirements. A Gradle plugin ID and a Kotlin compiler plugin
ID can have different names and are not Maven coordinates. A Maven coordinate identifies the JAR;
the compiler plugin ID identifies the plugin after Kotlin loads it. Versions are supplied separately
when resolving the JAR.

KPaths is one example: its experimental Gradle plugin ID is `dev.kpaths.gradle-plugin`, its compiler
plugin ID is `dev.kpaths.compiler-plugin`, and its compiler artifact is
`io.github.brennanium:kpaths-compiler-plugin-jvm`. KPaths supplies an IDE-compatible build through
KEFS. That build currently uses the same artifact name in a separate repository because KEFS'
default lookup matches the build artifact name. See the [KPaths repository](https://github.com/Brennanium/KPaths) for its implementation.

## What each assertion needs

| Assertion | What it checks | What the current integration needs |
| --- | --- | --- |
| `assertSnapshot` | A captured value compared with an expected value. | A snapshot strategy, a value, and an expectation. No compiler or IDE is required. |
| `assertCompiler` | Compilation outcome, diagnostics, and optionally compiler output such as FIR or IR. | Source, a compiler version, and a `CompilerHost`. The embedded host needs compiler plugin arguments and any compilation dependencies on its configured classpath. Capturing FIR or IR also needs plugin-specific capture arguments. |
| `assertEditor` | What the IDE reports for source, such as reference resolution and diagnostics. | A configured editor host, the project dependencies and plugin, a compatible IDE and Knapshots adapter, and any IDE integration the plugin needs. For KEFS, that includes KEFS and a compatible IDE plugin artifact. |

The distinction matters: a successful compiler assertion does not establish that the IDE can
resolve the generated declaration, and an editor assertion does not test the resulting program's
runtime behavior.

## Configuring compiler assertions today

`knapshots-compiler` provides `EmbeddedCompilerHost` to run a real JVM compiler. Configure the
compiler plugin JAR, any compilation classpath entries, and, when capturing output, the options that
ask that plugin to write its captures. Pass that host and the matching embedded Kotlin compiler
version to `assertCompiler`. The host accepts compiler arguments; the format of FIR and IR output
and the options to produce it depend on the plugin being tested.

Inline FIR/IR recording is available through `SnapshotRecording`; review the recorded source and
rerun the test. Knapshots still requires explicit compiler-host setup at the assertion call site;
shared defaults like KPaths' existing test harness are not yet integrated. The embedded host uses
its bundled compiler version rather than downloading a compiler for each requested version.

## Configuring editor assertions today

With the JUnit integration and a project-configured host, a test can call `assertEditor(source)`
without passing a host. Unexpected errors fail by default; reference resolution and expected
diagnostics can be asserted in its block. The Gradle integration can use the test task's runtime
classpath and infer selected version information. It still needs enough project configuration to
apply the target Gradle plugin, locate any required IDE plugin, and launch the compatible adapter.

The [compatibility guide](COMPATIBILITY.md#editor-assertions-in-junit) describes the experimental
Gradle settings and what is currently inferred. The adapter has not been published; a local archive
or the existing checkout-based launcher is still needed. Other IDE plugin mechanisms require their
own integration; enabling KEFS is not a requirement for compiler assertions.
