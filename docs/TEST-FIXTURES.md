# Compiler test fixtures

Knapshots keeps small test-only compiler plugins and KSP processors in its own repository, following the role that [MacroTesting's example macros](https://github.com/pointfreeco/swift-macro-testing/tree/main/Tests/MacroTestingTests/MacroExamples) play in testing MacroTesting. They are not public plugins or dependencies for library consumers.

The compiler fixture includes `@AddMember` and `@AddProperty`: annotated Kotlin classes gain a function or property. It also reports source-located `@Reject` errors and `@Caution` warnings and performs a simple IR string transformation. The plugin can write concise observations gathered in FIR and IR; those observations are intentionally small and are not full compiler dumps.

The fixture is exercised by both embedded and Gradle compiler hosts. Tests check generated functions and properties, nested and generic classes, plugin-free downstream use, failures without the plugin or annotation, type errors, multiple source-located plugin diagnostics, compiler flags, multiple source files, FIR/IR snapshots, version-specific expectations, inline recording, and transformed bytecode. A separate test-only KSP processor supplies `@AddGreeting`: it generates a greeting object for each annotated class, reports errors for invalid targets, and optionally emits a warning. KSP integration tests run the processor in an isolated Gradle project, compare and record generated source, inspect diagnostics, execute generated code, and cover multiple files, compiled dependencies, processor rounds, and multiple processors. Lightweight editor tests use the same test compiler plugin to check generated Kotlin references and plugin diagnostics; they also test ordinary Kotlin and Java editor observations. The optional KPaths integration remains useful as a cross-project test of an independent plugin; it is not required to run these tests.

The recording-mode tests follow the cases in [SnapshotTesting's recording tests](https://github.com/pointfreeco/swift-snapshot-testing/blob/main/Tests/SnapshotTestingTests/RecordTests.swift): missing snapshots, existing matches and mismatches, failed-only updates, forced updates, and no-record behavior. Compiler, KSP, and editor assertions exercise these modes with inline snapshots. The fixture also tests generated output alongside diagnostics, inspired by [MacroTesting's diagnostic and expansion examples](https://github.com/pointfreeco/swift-macro-testing/blob/main/Tests/MacroTestingTests/AddBlockerTests.swift). Kotlin source ranges and IDE quick fixes remain distinct areas of work.

## Coverage to expand

[CompilationTesting's beta preview](https://www.pointfree.co/blog/posts/234-beta-preview-compilationtesting) also motivates testing source-level compiler guarantees and recording diagnostics. The fixture and assertion APIs do not yet cover every behavior from MacroTesting or CompilationTesting. In particular, future work includes:

- More declaration shapes and visibility cases beyond the generated functions and properties, nested and generic classes, and downstream JVM consumer already covered.
- Full diagnostic source ranges. The embedded compiler host captures line and column and renders a caret; range and end-position support needs further investigation.
- Generated KSP resources. A disabled acceptance test demonstrates that the processor can produce a resource, but the Gradle host currently collects only Kotlin and Java sources.
- Editor diagnostics support source-annotated inline recording through an isolated worker, but version-specific editor expectations are not implemented. The worker keeps the embedded compiler off the IDE classpath to avoid conflicts with the IDE Kotlin plugin.
- Diagnostic notes and fix-it or IDE quick-fix assertions where Kotlin's APIs expose them. Kotlin compiler diagnostics and IDE quick fixes are different APIs, so Swift fix-it behavior should not be assumed to map directly.
- Additional source formatting and escaped-string recording cases, language-mode options, and scenarios where a test should unexpectedly succeed or fail.
- Further Kotlin-specific boundaries such as compiler-plugin ordering, bytecode bridges, and full imported-project IDE resolution. KSP multi-round and multiple-processor cases and lightweight IDE resolution are covered; they do not replace full IDE integration.

Keep fixture behavior small and deterministic. Add an example for a distinct testing capability rather than reproducing a production library's feature set.
