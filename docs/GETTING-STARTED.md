# Getting started

Knapshots runs on JDK 17 or newer. It is under development and has not been published to a public registry yet. You can try the current checkout from a separate Gradle project using a local Maven repository.

## Build a local repository

Clone [Knapshots](https://github.com/Brennanium/Knapshots), then run this command in its checkout:

```shell
./gradlew publishAllPublicationsToConsumerVerificationRepository
```

The repository is created at `build/consumer-repository`. Add that directory to both plugin and dependency resolution in your test project's `settings.gradle.kts`:

```kotlin
pluginManagement {
  repositories {
    maven { url = uri("/path/to/Knapshots/build/consumer-repository") }
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    maven { url = uri("/path/to/Knapshots/build/consumer-repository") }
    mavenCentral()
  }
}
```

## Add test dependencies

Choose the module for the kind of assertion you need. For compiler assertions, add the plugin and the compiler module to your test project:

```kotlin
plugins {
  kotlin("jvm") version "2.4.21"
  id("dev.knapshots.compatibility") version "0.1.0-SNAPSHOT"
}

dependencies {
  testImplementation("dev.knapshots:knapshots-compiler:0.1.0-SNAPSHOT")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test { useJUnitPlatform() }
```

The compatibility plugin configures the test environment. A compiler plugin test also needs the artifact and options for the plugin under test; see [Testing compiler plugins](PLUGIN-TESTING.md#configuring-compiler-assertions-today). For KSP, add `knapshots-ksp` and follow [Testing KSP processors](KSP-TESTING.md). For plain text comparisons, `knapshots-snapshots` is sufficient and the Gradle plugin is optional.

`knapshots-compiler-gradle` and `knapshots-ksp` use Gradle TestKit. When the Knapshots Gradle plugin is applied, it supplies TestKit to the test runtime for those dependencies. Without the plugin, add `testRuntimeOnly(gradleTestKit())` yourself.

## Test what the IDE sees

Editor assertions additionally require an IDE-compatible adapter, and IDE integrations such as KEFS need their own configuration. The adapter is not published yet; the [compatibility guide](COMPATIBILITY.md#editor-assertions-in-junit) describes the current checkout-based setup and its requirements.

For a complete example of resolving the local artifacts without a composite build, see [`ci/published-consumer`](https://github.com/Brennanium/Knapshots/tree/main/ci/published-consumer).
