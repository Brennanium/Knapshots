# Knapshots

Snapshot testing tools for Kotlin values, compiler plugins, KSP processors, and editor behavior.

Knapshots is based on Point-Free's [SnapshotTesting](https://github.com/pointfreeco/swift-snapshot-testing) and [MacroTesting](https://github.com/pointfreeco/swift-macro-testing). It brings their snapshot-driven testing style to Kotlin compiler and IDE tooling.

## Usage

Write a test with the source you want to check:

```kotlin
import dev.knapshots.testing.ksp.assertKsp
import org.junit.jupiter.api.Test

class GreetingProcessorTest {
  @Test fun `generates a greeting`() {
    assertKsp("""
      @GenerateGreeting class Person
    """)
  }
}
```

On the first run, Knapshots records generated source and diagnostics inline and fails the test so you can review them. Run it again to compare future output with the recorded snapshots. If something changes, the failure shows the changed lines:

```diff
− expected / + actual
@@ -1,3 +1,3 @@
  object PersonGreeting {
−   fun greet() = "Hello, Person!"
+   fun greet() = "Welcome, Person!"
  }
```

Use `assertCompiler` to check a compiler plugin's output and diagnostics, `assertEditor` to check what an IDE reports, and `assertSnapshot` to compare a value using a snapshot strategy. See the [documentation](#documentation) for examples and setup.

## Installation

> Knapshots has not been published yet. The following shows the intended setup for the first `0.1.0` release; these dependencies will resolve after publication.

Add Maven Central to plugin and dependency resolution, then apply the Knapshots Gradle plugin and add the module you need:

```kotlin
plugins {
  kotlin("jvm") version "2.4.21"
  id("io.github.brennanium.knapshots.compatibility") version "0.1.0"
}

dependencies {
  testImplementation("io.github.brennanium:knapshots-ksp:0.1.0")
}
```

Knapshots can discover a processor declared in the current project using the standard KSP service registration. See [Getting started](docs/GETTING-STARTED.md) for repositories, test dependencies, compiler plugins, and editor setup.

## Documentation

- [Getting started](docs/GETTING-STARTED.md)
- [Snapshot testing](docs/SNAPSHOTS.md)
- [Testing compiler plugins](docs/COMPILER.md)
- [Testing KSP processors](docs/KSP-TESTING.md)
- [Testing editor behavior](docs/EDITOR.md)
- [Compatibility testing](docs/COMPATIBILITY.md)

## Development

Run `./gradlew check` to verify the library. Run `./gradlew verifyReleaseConsumer` to test the unpublished `0.1.0` artifacts in a separate Gradle consumer. To build the documentation site, install `docs/requirements.txt` and run `./gradlew docsBuild`; use `./gradlew docsServe` to view it locally.

Knapshots' compiler and IDE compatibility work also draws lessons from [Metro](https://github.com/ZacSweers/metro) and [kotlin-structural-typing](https://github.com/obabichev/kotlin-structural-typing).

## License

Knapshots is available under the [MIT License](LICENSE).
