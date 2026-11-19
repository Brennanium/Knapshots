# Knapshots

Knapshots is a Kotlin testing toolkit for snapshot assertions, compiler-plugin verification, and real IDE assertions.

See [Testing compiler plugins](docs/PLUGIN-TESTING.md) for the roles of Gradle, compiler, and IDE plugins and what each assertion needs.

The toolkit is under active development. Its modules include:

- `knapshots-snapshots` supplies reusable snapshot strategies and comparisons.
- `knapshots-compiler` provides `assertCompiler`, compiler diagnostics, FIR and IR snapshots, and inline recording.
- `knapshots-compiler-gradle` compiles snippets in temporary Gradle projects with a selected Kotlin version.
- `knapshots-bytecode` inspects JVM instructions and captures class-file text snapshots.
- `knapshots-ksp` tests KSP processors and generated sources.
- `knapshots-editor` and `knapshots-editor-junit` provide source-driven editor assertions.
- `knapshots-ide-runner`, `knapshots-intellij`, `knapshots-compatibility`, and `knapshots-gradle-plugin` host IDE inspections and coordinate compatibility tests.
- `knapshots-intellij-fixture` is an optional standalone build for `assertEditor` in IntelliJ light fixtures; it requires a selected local IDE and IntelliJ test framework.

KPaths consumes Knapshots through a pinned source checkout in CI and a composite Gradle build locally. The APIs and packaging remain experimental; a published release and independently resolved IDE adapter are still to come.

The experimental `knapshots-intellij` adapter is intentionally opt-in because it compiles against the exact APIs of a locally selected IntelliJ-based IDE. It is excluded from normal verification; see the architecture roadmap before enabling it.

To compile it against a selected IDE, provide its installation path explicitly:

```shell
./gradlew \
  -Pkotlin.daemon.jvmargs="-Xmx2g" \
  -Pknapshots.enableIntellijAdapter \
  -Pknapshots.idePath="/path/to/Android Studio.app" \
  :knapshots-intellij:build
```

This command only compiles the adapter. The experimental runner can package and launch it for imported-project assertions; see the [compatibility guide](docs/COMPATIBILITY.md) for the experimental Gradle setup.

The repository includes one deliberately opt-in imported-project smoke test for the adapter. It uses a disposable IDE sandbox, pre-trusts only its generated project, and pre-seeds Android Studio's first-run/analytics preferences so CI never needs to answer a trust or consent dialog:

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

Knapshots is inspired by Point-Free's [SnapshotTesting](https://github.com/pointfreeco/swift-snapshot-testing), [MacroTesting](https://github.com/pointfreeco/swift-macro-testing), and [CompilationTesting](https://github.com/pointfreeco/swift-compilation-testing). Its compiler and IDE compatibility work also draws lessons from [Metro](https://github.com/ZacSweers/metro) and [kotlin-structural-typing](https://github.com/obabichev/kotlin-structural-typing).

Knapshots is released under the [MIT License](LICENSE).
