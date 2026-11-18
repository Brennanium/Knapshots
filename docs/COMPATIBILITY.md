# Compatibility orchestration

The intended Gradle entry points select toolchains per invocation:

```shell
./gradlew editorCompatibility --ide-version 2026.2.2.8
./gradlew compilerCompatibility --kotlin-version 2.4.21
```

These are target APIs, not implemented tasks yet. Compiler selectors should also accept dev
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

Knapshots currently has a local Android Studio imported-project smoke test. It does not yet have
a compiler backend, compatibility tasks, IDE distribution resolver, KEFS provisioning, or Linux
CI verification. Selecting an IDE does not itself make an adapter or compiler plugin compatible
with its bundled compiler; a run must fail clearly if it lacks compatible artifacts or a driver.

Keep version selection, distribution resolution, platform launch, and project-specific assertions
as distinct components. The same tasks should work locally and on CI, with reports identifying
resolved versions and distinguishing failures from checks that were not run. Preserve the KPaths
scripts until an independent consumer verifies equivalent behavior.
