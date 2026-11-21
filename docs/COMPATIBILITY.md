# Compatibility orchestration

The intended Gradle entry points select toolchains per invocation:

```shell
./gradlew editorCompatibility --ide-version 2026.2.2.8
./gradlew compilerCompatibility --kotlin-version 2.4.20,2.4.21,'2.5.0-dev*' --platform jvm,native
```

These tasks are experimental and currently require project-provided drivers. Compiler selectors accept comma-separated lists and repeated `--kotlin-version` options, including dev families such as `2.5.0-dev*`. Quote wildcard selectors to prevent shell expansion. The report records the concrete version selected; duplicate resolved versions run once. IDE selectors accept a full version or build and `latest-stable`.

Compiler and editor compatibility are independent dimensions. Compiler compatibility invokes the configured project driver for each selected Kotlin version and reports its result. The driver owns the choice and reporting of JVM, Native, and consumer tests. Editor compatibility resolves an Android Studio release, verifies its official download checksum, and invokes the configured editor driver. Its report records the actual IDE build; when KEFS discovery is enabled it also records the IDE compiler version. A local IDE override must match the selected build.

The existing KPaths implementation is the migration reference:

- `scripts/verify-compiler-compatibility.py` resolves Kotlin selectors, runs project regression
  drivers, and reports JVM and optional Native results separately.
- `scripts/verify-android-studio-version.py` resolves IDE releases, verifies downloads, identifies
  the bundled Kotlin compiler, and invokes a project driver.
- Its KEFS driver discovers a compatible Marketplace release and exercises enabled, disabled,
  and diagnostic scenarios. KEFS is optional: a custom IDE plugin can provide another driver.
- `.github/workflows/ide-compatibility.yml` runs version entries on Linux and macOS, provisions
  Xvfb on Linux, and collects machine-readable results and logs.

Apply `dev.knapshots.compatibility` and configure the executable drivers:

```kotlin
knapshotsCompatibility {
  compilerDriver.set(layout.projectDirectory.file("scripts/compiler-driver"))
  editorDriver.set(layout.projectDirectory.file("scripts/editor-driver"))
  useKefs.set(true) // optional; for a KEFS-based compiler plugin
  useXvfb.set(true) // optional; for Linux runners with xvfb-run installed
  driverEnvironment.put("EXAMPLE_CHECKOUT", "/path/to/checkout") // optional
}
```

The compiler driver receives `--kotlin-version <resolved version>` and, when selected, `--platform <comma-separated platforms>`. It can report individual checks by printing `KNAPSHOTS_CHECK <name> passed`, `failed`, or `not-run`; Knapshots writes their results to `build/reports/knapshots/compiler-compatibility.json`. The editor driver receives `--ide-path <validated installation>`. With KEFS enabled, the editor driver also receives `--ide-kotlin-version` and the selected `--kefs-version`. A nonzero exit code fails the task. The IDE task resolves the official feed, caches the SHA-256-verified archive, and provisions a temporary installation. `idePath` and `releaseFeed` are optional overrides; the supplied IDE must match the selected build.

The task writes `build/reports/knapshots/editor-compatibility.json` with the resolved release, result, and any selected KEFS/compiler versions. Its opt-in KEFS discovery selects a compatible Marketplace release and reads its mapping for Android Studio's special compiler versions; the driver remains responsible for installing/configuring KEFS and its compiler plugin.

Knapshots' imported-project smoke test has passed on Ubuntu/Xvfb and macOS CI using Android Studio 2026.2.2.8. A separate local integration uses Knapshots' editor host to test a KPaths consumer with KEFS enabled, disabled, and with an intentional Kotlin error; those scenarios have also passed on Ubuntu and macOS CI. KPaths still prepares its own artifacts for this test. Knapshots provides an embedded JVM compiler host; its KPaths integration is still experimental. Selecting an IDE does not itself make an adapter or compiler plugin compatible with its bundled compiler.

Keep version selection, distribution resolution, platform launch, and project-specific assertions as distinct components. The same tasks should work locally and on CI, with reports identifying resolved versions and distinguishing failures from checks that were not run. Preserve the KPaths scripts until an independent consumer verifies equivalent behavior.

## Collecting scenario reports

For drivers that run a Gradle test for each editor scenario, configure the test's JUnit XML:

```kotlin
knapshotsCompatibility {
  scenarioJUnitXml.set(layout.projectDirectory.file("build/test-results/test/TEST-example.EditorTest.xml"))
}
```

```shell
./gradlew collectEditorScenario --scenario enabled
```

The task copies the XML to `build/reports/knapshots/scenarios/enabled/junit.xml` before checking that at least one test ran and none failed or were skipped. Run the collector even when the test command fails to preserve its report; clear the old test XML before each test invocation so a failed launch cannot be mistaken for a fresh result.

An editor host can set `reportDirectory` to retain each inspection's request and response, independently of temporary-project retention. On failure, the host also collects the IDE process log and the relative paths supplied through `failureReportFiles`. A driver can place these files in the scenario's `inspections` directory and upload `build/reports/knapshots` as a CI artifact. The failure paths are relative to the temporary inspection directory.

## Editor assertions in JUnit

For the roles of Gradle, compiler, and IDE plugins, see [Testing compiler plugins](PLUGIN-TESTING.md).

Add `knapshots-editor-junit` and `knapshots-ide-runner` to the test dependencies. The compatibility plugin enables JUnit extension discovery; tests can then call `assertEditor(source) { ... }` without an annotation or host argument. Configure the temporary project's plugin and dependencies once in Gradle:

```kotlin
knapshots {
  gradlePlugin { id.set("example.gradle-plugin") }
  repository.set(layout.projectDirectory.dir("build/local-maven"))
  idePath.set(file("/path/to/Android Studio.app"))
  runnerRoot.set(file("/path/to/Knapshots"))
  idePlugin {
    kefs {
      version.set("0.5.0-262")
      idePlugin.set("example:example-compiler-plugin-jvm")
      repository.set(layout.projectDirectory.dir("build/ide-maven"))
    }
  }
}
```

This example uses a Knapshots checkout to build the adapter and a local repository containing the IDE-compatible artifact. `runnerRoot` is inferred from `KNAPSHOTS_CHECKOUT` when that environment variable is set, and `idePath` can be supplied with `KNAPSHOTS_IDE_PATH`. Choose the KEFS version compatible with the selected IDE. The plugin ID tells the generated Gradle project which plugin to apply. The optional KEFS `idePlugin` identifies the IDE artifact that KEFS loads; it is written as Maven group and artifact (`group:artifact`), without a version. In this example the build compiler artifact is also named `example:example-compiler-plugin-jvm`, so KEFS can match it.

Knapshots uses the test task's runtime classpath as the generated project's dependencies. It takes the Kotlin version from the applied Kotlin JVM plugin when available, falling back to the `libs` version catalog's `kotlin` entry, and takes the plugin version from the Gradle project version; each can be overridden explicitly. Projects with a separate consumer classpath or a plugin version unrelated to their project version should configure those values explicitly. When the project declares exactly one Gradle plugin using `java-gradle-plugin`, Knapshots can also infer its ID, so the `gradlePlugin` block can be omitted. If there are multiple declarations, select the plugin explicitly. For the current KEFS lookup, Knapshots can infer the IDE artifact from an already applied Kotlin compiler Gradle plugin. A separately named IDE artifact requires an additional mapping and is not yet supported by this default lookup.

### How KEFS selects the IDE plugin

KEFS is the first IDE plugin integration exercised by Knapshots. The temporary project's Gradle plugin loads the build compiler plugin. When Android Studio analyzes that project, KEFS sees the build compiler plugin JAR and looks for an IDE-compatible replacement. Knapshots configures the KEFS repository and artifact name in the generated project's settings and installs KEFS in the isolated IDE sandbox.

With KEFS' default lookup, the build and IDE JARs must use the same Maven group and artifact name so KEFS can associate them. They are different builds: the build JAR targets the project's Kotlin compiler, while the IDE JAR targets the IDE's bundled Kotlin compiler. The IDE repository's versions combine the IDE Kotlin compiler version and library version. For example, with library version `0.1.0` and IDE compiler version `2.4.20-ij262-52`, the IDE artifact version is `2.4.20-ij262-52-0.1.0`. A different artifact name needs a verified mapping; specifying a different name alone does not make KEFS match it.

The compatibility orchestrator supplies the selected IDE and, when KEFS is enabled, its downloaded archive to the editor driver. Configure `adapterArchive` with the Knapshots IntelliJ adapter ZIP; the direct launcher installs it and KEFS in an isolated IDE sandbox. The test project's Gradle wrapper is used by default. The direct launcher has passed a KPaths Android Studio test with the adapter and wrapper outside the Knapshots checkout.

The adapter archive is not published yet, so selecting and resolving a compatible archive still requires setup. The previous Gradle-based launcher remains available through `knapshots.ideRunnerRoot`. The KPaths compatibility driver builds the adapter for its selected IDE and reuses it for all scenarios; it passes project configuration through Gradle test properties.

The provider is initialized only when a test calls `assertEditor`. Without the compatibility plugin, enable JUnit's `junit.jupiter.extensions.autodetection.enabled` setting yourself.
