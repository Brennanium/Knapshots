# Compatibility orchestration

The intended Gradle entry points select toolchains per invocation:

```shell
./gradlew editorCompatibility --ide-version 2026.2.2.8
./gradlew compilerCompatibility --kotlin-version 2.4.21
```

These tasks are experimental and currently require project-provided drivers. Compiler selectors accept dev
families such as `2.5.0-dev*`, reporting the concrete version selected. IDE selectors should
accept a full version or build and `latest-stable`.

Compiler and editor compatibility are independent dimensions. Compiler compatibility runs a
project's compiler and consumer regression suites for the selected Kotlin version, recording
which platforms passed, failed, or were not run. Editor compatibility resolves an Android
Studio release, verifies its official download checksum, and runs editor assertions in an
isolated IDE. Its report records the actual IDE build and bundled IDE compiler. A local IDE
override should be verified against the requested build.

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
`--kotlin-ide-version` information as `--ide-kotlin-version` and the selected `--kefs-version`.
A nonzero exit code fails the task. The IDE task resolves
the official feed, caches the SHA-256-verified archive, and provisions a temporary installation.
`idePath` and `releaseFeed` are optional overrides; the supplied IDE must match the selected build.

The task writes `build/reports/knapshots/editor-compatibility.json` with the resolved release,
result, and any selected KEFS/compiler versions. Its opt-in KEFS discovery selects a compatible
Marketplace release and reads its mapping for Android Studio's special compiler versions; the
driver remains responsible for installing/configuring KEFS and its compiler plugin.

Knapshots has a local Android Studio imported-project smoke test. A macOS integration run also
invoked KPaths' existing KEFS driver through `editorCompatibility` using Android Studio
2026.2.2.8, KEFS 0.5.0-262, and IDE Kotlin 2.4.20-ij262-52. The enabled, disabled, and expected
diagnostic imported-project scenarios passed. That run used an installed IDE override; it did not
verify distribution download, Linux/Xvfb, or a KPaths migration away from its existing scripts.
Knapshots does not yet have a compiler backend, automatic KEFS installation, or verified Linux
CI coverage. Selecting an IDE does not itself make an adapter or compiler plugin compatible
with its bundled compiler; a run must fail clearly if it lacks compatible artifacts or a driver.

Keep version selection, distribution resolution, platform launch, and project-specific assertions
as distinct components. The same tasks should work locally and on CI, with reports identifying
resolved versions and distinguishing failures from checks that were not run. Preserve the KPaths
scripts until an independent consumer verifies equivalent behavior.
