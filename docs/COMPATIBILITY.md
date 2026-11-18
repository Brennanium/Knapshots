# Compatibility orchestration

The intended Gradle entry points select toolchains per invocation:

```shell
./gradlew editorCompatibility --ide-version 2026.2.2.8
./gradlew compilerCompatibility --kotlin-version 2.4.21
```

These tasks are experimental and currently require project-provided drivers. Compiler selectors
accept dev families such as `2.5.0-dev*`, reporting the concrete version selected. IDE selectors
accept a full version or build and `latest-stable`.

Compiler and editor compatibility are independent dimensions. Compiler compatibility invokes
the configured project driver for each selected Kotlin version and reports its result. The driver
owns the choice and reporting of JVM, Native, and consumer tests. Editor compatibility resolves an
Android Studio release, verifies its official download checksum, and invokes the configured editor
driver. Its report records the actual IDE build; when KEFS discovery is enabled it also records
the IDE compiler version. A local IDE override must match the selected build.

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
}
```

The compiler driver receives `--kotlin-version <resolved version>`, and the editor driver receives
`--ide-path <validated installation>`. With KEFS enabled, the editor driver also receives
`--ide-kotlin-version` and the selected `--kefs-version`.
A nonzero exit code fails the task. The IDE task resolves
the official feed, caches the SHA-256-verified archive, and provisions a temporary installation.
`idePath` and `releaseFeed` are optional overrides; the supplied IDE must match the selected build.

The task writes `build/reports/knapshots/editor-compatibility.json` with the resolved release,
result, and any selected KEFS/compiler versions. Its opt-in KEFS discovery selects a compatible
Marketplace release and reads its mapping for Android Studio's special compiler versions; the
driver remains responsible for installing/configuring KEFS and its compiler plugin.

Knapshots' imported-project smoke test has passed on Ubuntu/Xvfb and macOS CI using Android Studio
2026.2.2.8. A separate local integration uses Knapshots' editor host to test a KPaths consumer
with KEFS enabled, disabled, and with an intentional Kotlin error; those scenarios have also
passed on Ubuntu and macOS CI. KPaths still prepares its own artifacts for this test. Knapshots
provides an embedded JVM compiler host; its KPaths integration is still experimental. Selecting an IDE does not
itself make an adapter or compiler plugin compatible with its bundled compiler.

Keep version selection, distribution resolution, platform launch, and project-specific assertions
as distinct components. The same tasks should work locally and on CI, with reports identifying
resolved versions and distinguishing failures from checks that were not run. Preserve the KPaths
scripts until an independent consumer verifies equivalent behavior.

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

The task copies the XML to `build/reports/knapshots/scenarios/enabled/junit.xml` before checking
that at least one test ran and none failed or were skipped. Run the collector even when the test
command fails to preserve its report; clear the old test XML before each test invocation so a
failed launch cannot be mistaken for a fresh result.

An editor host can set `reportDirectory` to retain each inspection's request and response,
independently of temporary-project retention. On failure, the host also collects the IDE process
log and the relative paths supplied through `failureReportFiles`. A driver can place these files
in the scenario's `inspections` directory and upload `build/reports/knapshots` as a CI artifact.
The failure paths are relative to the temporary inspection directory.
