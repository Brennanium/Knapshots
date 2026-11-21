# Knapshots

Knapshots is a Kotlin testing toolkit for snapshot assertions, compiler plugins, KSP processors, and editor behavior.

```kotlin
assertCompiler("""
  @GenerateGreeting class Person
""")
```

Run the test to record the available output and diagnostics inline, review the result, and run it again to check future changes. The same workflow works for KSP and editor diagnostics. Knapshots also offers text snapshots, JVM bytecode inspection, and compatibility testing across Kotlin and IDE versions.

## Documentation

- [Getting started](docs/GETTING-STARTED.md)
- [Snapshot testing](docs/SNAPSHOTS.md)
- [Testing compiler plugins](docs/COMPILER.md)
- [Testing KSP processors](docs/KSP-TESTING.md)
- [Testing editor behavior](docs/EDITOR.md)
- [Compatibility testing](docs/COMPATIBILITY.md)

Knapshots is under active development and has not been published to a public registry. The getting-started guide explains how to use a locally published build. Full editor tests currently require an IDE-compatible adapter archive or a checkout; the guide explains the current options.

## Development

Run `./gradlew check` to verify the library. Run `./gradlew verifyPublishedConsumer` to publish to a local repository and test a separate Gradle consumer. To build the documentation site, install `docs/requirements.txt` and run `./gradlew docsBuild`. Use `./gradlew docsServe` to view the site locally.

## Inspiration

Knapshots is inspired by Point-Free's [SnapshotTesting](https://github.com/pointfreeco/swift-snapshot-testing), [MacroTesting](https://github.com/pointfreeco/swift-macro-testing), and [CompilationTesting](https://github.com/pointfreeco/swift-compilation-testing). Its compiler and IDE compatibility work also draws lessons from [Metro](https://github.com/ZacSweers/metro) and [kotlin-structural-typing](https://github.com/obabichev/kotlin-structural-typing).

Knapshots is released under the [MIT License](LICENSE).
