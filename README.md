# Knapshots

Knapshots is a Kotlin testing toolkit for snapshot assertions, compiler-plugin
verification, and real IDE assertions.

This repository is in its first extraction pass from KPaths. The initial modules are
intentionally small and independently testable:

- `knapshots-snapshots` provides composable capture and comparison strategies.
- `knapshots-compiler` provides version-aware named compiler-output snapshots. It does
  not yet compile source itself; integrations supply compiler captures.
- `knapshots-editor` provides assertions over diagnostics and reference-resolution
  observations from an IDE host. It does not yet start an IDE.

The planned `assertCompiler` and `assertEditor` source-driven integrations, Gradle
plugin, and compatibility runner are not public APIs yet. This is deliberately a
fresh repository: KPaths continues to use its local copies until this library has a
complete, verified release.

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
