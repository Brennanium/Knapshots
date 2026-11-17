# Architecture and extraction roadmap

Knapshots is a Kotlin testing toolkit with three related, but deliberately separate,
layers:

```text
┌────────────────────┐
│ knapshots-snapshots│  Capture/comparison/recording primitives
└─────────┬──────────┘
          ├─────────────────────┐
          ▼                     ▼
┌──────────────────┐   ┌──────────────────┐
│knapshots-compiler│   │ knapshots-editor │
│Compiler captures │   │ IDE observations │
└─────────┬────────┘   └─────────┬────────┘
          │                      │
          └──────────┬───────────┘
                     ▼
          Future Gradle integration
          and IDE/session runner
```

The dependency arrows only point down. A project that compares ordinary values must not
download an IDE or compiler-plugin infrastructure. A compiler-plugin author should be
able to use compiler assertions without depending on IntelliJ. An IDE adapter may depend
on editor observations, but the portable assertion API must not depend on an IntelliJ
class.

## Current modules

### `knapshots-snapshots`

The base module is intentionally tiny:

- `Snapshotting<Value, Format>` captures a `Value` into a comparable `Format`.
- `Diffing<Format>` compares captured values and returns an optional explanation.
- `pullback` adapts a strategy to a different input type.
- `compareSnapshot` captures exactly once and returns a structured mismatch.
- `textSnapshotting` is exact text comparison.

There is **not yet** a public `assertSnapshot`, filesystem snapshot convention, image
strategy, diff renderer, or inline recorder. Those should be designed here rather than
copied as compiler-specific behavior.

### `knapshots-compiler`

This module currently provides a generic *capture comparison* layer, not a Kotlin compiler
driver:

- `CompilerSnapshotKind` is an extensible named capture type. `FIR` and `IR` are built-in
  conveniences, rather than a closed enum, so integrations can define bytecode or
  plugin-specific output.
- `SnapshotExpectation` supports a default capture and compiler-version-specific overrides.
- Version selectors understand exact versions, `major.minor.*`, and bounded expressions such
  as `>=2.4.20 <2.5.0`. Exact selectors win. Ambiguous matching ranges fail.
- `assertCompilerSnapshots` asks an integration for captures and compares each requested
  capture.
- `assertCompilerSnapshotFiles` supports deliberately opt-in, file-backed recording. When it
  writes snapshots it fails the test, requiring review and a rerun.

The key missing public capability is a single source-driven `assertCompiler` API. It should
model successful and unsuccessful compilations, diagnostics, FIR/IR/bytecode captures when
available, and the fact that later phases may not exist after a failed compilation. Do not split
ordinary compile-error tests into a separate `assertCompilation` library.

The compiler module must stay backend-agnostic. A later K2/FIR, KCTFork, Gradle, or command-line
adapter should own compiler setup, plugin classpaths, capture generation, and compiler-version
differences.

### `knapshots-editor`

This module describes assertions over a *prepared* editor:

- `EditorObservations` supplies diagnostics and reference lookups.
- `ReferenceObservation` distinguishes `Resolved`, `Unresolved`, and `Unavailable`; an adapter
  failure may never masquerade as a legitimate unresolved reference.
- `assertEditor` fails unexpected error diagnostics by default. Warnings are asserted only when
  asked for. Expected diagnostics match one-to-one so broad expectations cannot hide extra
  errors.
- `EditorSourceHost` allows an adapter to open inline source, while retaining ownership of IDE
  and project lifecycle.

The module currently does not launch an IDE, import Gradle, wait for indexing, or record editor
snapshots. It only owns portable assertion semantics. A future source-driven
`assertEditor(source = …)` should be backed by a temporary project/session runner, not by test
fixtures hard-coded to one compiler plugin.

## Planned modules

These names are architectural placeholders, not publication commitments.

| Module | Responsibility | Must not depend on |
| --- | --- | --- |
| `knapshots-ide-runner` | Temporary project creation, IDE process/session lifecycle, readiness, transport of source and observations, cleanup and logs. | Project-specific compiler plugins and snapshots implementation details. |
| `knapshots-intellij` | IntelliJ/Android Studio adapter used inside the IDE process. | The host runner’s OS-process concerns. |
| `knapshots-gradle-plugin` | Convenient dependency wiring and tasks for local/CI usage. | A requirement that every user use Gradle. |
| Optional CLI | A thin interface over the JVM runner for CI/local ergonomics. | A separate Python runtime as a required dependency. |

The Gradle plugin should arrange compatible modules for a common workflow but must not hide the
independent APIs. It may launch the actual IDE and OS helpers such as Xvfb; using Gradle does not
make those external processes disappear.

## Test strategy

Keep test layers distinct:

1. **Unit tests** exercise snapshot comparison, selector rules, assertion matching, recording
   policy, and failure messages quickly.
2. **Compiler integration tests** compile minimal snippets using a chosen backend and assert
   source-driven output, diagnostics, or generated declarations. They must test expected failure
   and unexpected success.
3. **Light IDE fixture tests** validate an IntelliJ adapter with fast, focused source fixtures.
   These are not evidence that a packaged plugin or Gradle import works.
4. **Imported-project IDE tests** generate a clean consumer project, import it in a real IDE,
   wait for a deterministic ready state, then query references and diagnostics. Test both passing
   and deliberately failing cases.
5. **Compatibility matrix tests** schedule the above against explicitly requested Kotlin and IDE
   versions. Kotlin build compiler compatibility and the compiler bundled with an IDE are separate
   dimensions.

Do not report “no diagnostics” unless the adapter successfully captured diagnostics after analysis
was ready. Timeouts, unavailable analysis, and process failures must have distinct result states.

## Recording policy

Normal verification must never edit source or expectation files. Recording is an explicit mode.
All recorders should:

- validate every requested capture before changing any file;
- use atomic writes where the platform allows it;
- refuse ambiguous/unsupported inline source shapes;
- preserve version-specific expectations without replacing shared ones accidentally;
- fail after writing so the developer reviews the recorded output and reruns verification.

KPaths contains a Kotlin PSI inline-recorder prototype. It has useful safety behavior but is tied
to its own `assertCompiler` call shape, source layout, and FIR/IR arguments. Reuse its tests and
safety constraints, not its KPaths-specific API assumptions.

## Provenance and remaining KPaths work

The initial code was copied from KPaths instead of moved. KPaths remains self-contained and its
local test fixtures remain authoritative until Knapshots reaches a stable, independently verified
release. This repository must not change KPaths’ dependencies or delete those local pieces during
the extraction.

The useful KPaths references are:

- `snapshot-testing/` for the original snapshot foundation;
- `compiler-testing/` for compiler capture comparison and version selector tests;
- `editor-testing/` for portable editor assertion semantics;
- `kpaths-compiler-plugin-fixture/src/test/.../InlineCompilerSnapshots.kt` for the inline
  recording prototype;
- `kpaths-compiler-plugin-fixture/src/test/.../FutureCompilerPluginContractTest.kt` for a
  KPaths-specific Gradle-backed compiler harness;
- `kpaths-compiler-plugin-ide-fixture/` for a light IntelliJ fixture;
- `kpaths-kefs-ide-fixture/` and `scripts/` for a real Android Studio/KEFS project probe,
  compatibility runner, project generation, and platform handling.

The latter two IDE systems prove different things. The light fixture does not prove KEFS or a
Gradle import. The imported-project probe is currently KPaths-specific; it is a behavioral
reference, not yet Knapshots’ generic request/result protocol.

## Near-term implementation order

1. Finish the generic snapshot surface: assertion entry point, reusable failure rendering,
   explicit recording policy, and file/inline extension points.
2. Generalize `assertCompiler` around a compilation result model. Retain version selector behavior
   and add negative diagnostic tests before integrating a real compiler backend.
3. Extract the PSI inline recorder only after its call-shape assumptions are replaced by generic
   capture descriptors and its safety tests move with it.
4. Define a request/result transport for `assertEditor`; then build a temporary project host and
   one real IDE adapter with readiness, timeout, cleanup, and logs.
5. Add a Gradle plugin/runner around the working APIs. Port compatibility orchestration gradually;
   keep any existing script implementation as a reference until independent JVM parity exists.

Publishing coordinates, API stability, the exact JDK support policy, and a release process remain
open. Do not publish or introduce CI workflows without a separate decision.
