# Knapshots

Knapshots is a Kotlin testing toolkit for snapshot assertions, compiler-plugin
verification, and real IDE assertions.

This repository is in its first extraction pass from KPaths. The initial modules are
intentionally small and independently testable:

- `knapshots-snapshots` provides composable capture and comparison strategies, including an
  in-memory `assertSnapshot` assertion.
- `knapshots-compiler` provides source-driven `assertCompiler` assertions through a pluggable
  compiler host: success/failure, diagnostics, and version-aware named compiler-output snapshots.
  An experimental embedded JVM compiler host supports plugin-provided FIR/IR captures and inline recording.
- `knapshots-editor` provides assertions over diagnostics and reference-resolution
  observations from an IDE host. It builds a complete source/observation request before opening
  that host, but does not yet start an IDE itself.
- `knapshots-ide-runner` materializes fresh Gradle projects and carries versioned requests/results
  between a runner and an IDE-side adapter. It can launch a caller-supplied command with timeout,
  logs, and isolated-project lifecycle, but does not bundle an IDE-specific launcher yet.

The planned `assertCompiler` and `assertEditor` source-driven integrations, Gradle
plugin, and compatibility runner are not public APIs yet. This is deliberately a
fresh repository: KPaths continues to use its local copies until this library has a
complete, verified release.

The experimental `knapshots-intellij` adapter is intentionally opt-in because it compiles against
the exact APIs of a locally selected IntelliJ-based IDE. It is excluded from normal verification;
see the architecture roadmap before enabling it.

To compile it against a selected IDE, provide its installation path explicitly:

```shell
./gradlew \
  -Pkotlin.daemon.jvmargs="-Xmx2g" \
  -Pknapshots.enableIntellijAdapter \
  -Pknapshots.idePath="/path/to/Android Studio.app" \
  :knapshots-intellij:build
```

This only compiles the adapter; it does not yet configure a user project, install the adapter,
or launch an imported-project assertion. Its `runIde` task accepts the generated project and
protocol paths, but a polished Gradle-facing setup for library consumers is still pending.

The repository includes one deliberately opt-in imported-project smoke test for the adapter. It
uses a disposable IDE sandbox, pre-trusts only its generated project, and pre-seeds Android
Studio's first-run/analytics preferences so CI never needs to answer a trust or consent dialog:

```shell
./gradlew \
  -Pkotlin.daemon.jvmargs="-Xmx2g" \
  -Pknapshots.enableIntellijAdapter \
  -Pknapshots.idePath="/path/to/Android Studio.app" \
  -Pknapshots.includeIdeIntegration \
  :knapshots-ide-runner:test \
  --tests '*IntellijAdapterIntegrationTest'
```

## Build

Use a supported JDK (currently JDK 17 or newer) and run:

```shell
./gradlew check
```

## Inspiration

Knapshots is inspired by Point-Free's
[SnapshotTesting](https://github.com/pointfreeco/swift-snapshot-testing),
[MacroTesting](https://github.com/pointfreeco/swift-macro-testing), and
[CompilationTesting](https://github.com/pointfreeco/swift-compilation-testing).
Its compiler and IDE compatibility work also draws lessons from
[Metro](https://github.com/ZacSweers/metro) and
[kotlin-structural-typing](https://github.com/obabichev/kotlin-structural-typing).

Knapshots is released under the [MIT License](LICENSE).
