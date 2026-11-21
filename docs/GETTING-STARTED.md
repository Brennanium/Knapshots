# Getting started

Add Knapshots to a test project to record and compare values, compiler output, KSP-generated source, or editor observations.

!!! note "Before the first release"
    Knapshots has not been published yet. The `0.1.0` coordinates below describe the intended first release and will resolve after it is published.

## Add Knapshots

Knapshots runs on JDK 17 or newer. Ensure Maven Central is available for both Gradle plugins and project dependencies in `settings.gradle.kts`:

```kotlin
pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories { mavenCentral() }
}
```

Apply the Knapshots Gradle plugin and add the module you want to use to your test dependencies:

```kotlin
plugins {
  kotlin("jvm") version "2.4.21"
  id("io.github.brennanium.knapshots.compatibility") version "0.1.0"
}

dependencies {
  testImplementation("io.github.brennanium:knapshots-compiler:0.1.0")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test { useJUnitPlatform() }
```

Use `knapshots-ksp` for KSP processors, `knapshots-editor-junit` and `knapshots-ide-runner` for editor assertions, or `knapshots-snapshots` for ordinary snapshots. The Gradle plugin is optional for plain snapshot comparisons.

## Test a compiler plugin

When the project under test registers a `CompilerPluginRegistrar` using the standard service file, Knapshots can discover its JAR. A focused assertion can check whether source compiles:

```kotlin
assertCompiler("""
  @GenerateGreeting class Person
""") {
  succeeds()
}
```

Use [compiler assertions](COMPILER.md) to record available plugin output and diagnostics. The guide also shows how to specify an artifact or plugin capture options when Knapshots cannot infer them.

## Test a KSP processor

If the project registers a `SymbolProcessorProvider` in the standard service file, Knapshots discovers its JAR and runtime dependencies. Add `knapshots-ksp`, then write:

```kotlin
assertKsp("""
  @GenerateGreeting class Person
""")
```

Run the test to record generated source and diagnostics inline, review the result, and rerun it. The [KSP guide](KSP-TESTING.md) covers processors in other projects and version overrides. The Gradle plugin supplies TestKit for Gradle-backed hosts; without the plugin, add `testRuntimeOnly(gradleTestKit())`.

## Test editor behavior

[Editor assertions](EDITOR.md) check diagnostics and reference resolution in an IDE. Full imported-project tests currently require an IDE-compatible adapter archive. Adapter distribution is still being developed; the [editor setup guide](COMPATIBILITY.md#editor-assertions-in-junit) describes the current requirements.
