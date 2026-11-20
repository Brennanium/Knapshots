# Compiler test fixtures

Knapshots keeps small test-only compiler plugins in its own repository, following the role that [MacroTesting's example macros](https://github.com/pointfreeco/swift-macro-testing/tree/main/Tests/MacroTestingTests/MacroExamples) play in testing MacroTesting. They are not public plugins or dependencies for library consumers.

The current fixture includes an `@AddMember` example: an annotated Kotlin class gains an `addedMember()` function. Tests also exercise a plugin warning and a simple IR string transformation. The plugin can write concise observations gathered in FIR and IR; those observations are intentionally small and are not full compiler dumps.

The fixture is exercised by both embedded and Gradle compiler hosts. Tests check a generated member's availability and runtime behavior, the failure without the plugin or annotation, type errors involving the generated member, warning messages and locations, compiler flags, multiple source files, FIR/IR snapshots, version-specific expectations, inline recording, and transformed bytecode. Separate KSP and editor tests cover those hosts. The optional KPaths integration remains useful as a cross-project test of an independent plugin; it is not required to run these tests.

## Coverage to expand

[CompilationTesting's beta preview](https://www.pointfree.co/blog/posts/234-beta-preview-compilationtesting) also motivates testing source-level compiler guarantees and recording diagnostics. The fixture and assertion APIs do not yet cover every behavior from MacroTesting or CompilationTesting. In particular, future work includes:

- More declaration shapes, including generated properties, nested and generic members, visibility, and cross-module use.
- Plugin-produced errors with precise source ranges and multiple diagnostics; the present fixture warning has no source location.
- Inline diagnostic snapshots, notes, and fix-it representations where Kotlin's compiler or IDE APIs expose them. Kotlin compiler diagnostics and IDE quick fixes are different APIs, so Swift fix-it behavior should not be assumed to map directly.
- Additional source formatting and escaped-string recording cases, language-mode options, and scenarios where a test should unexpectedly succeed or fail.
- Kotlin-specific boundaries such as plugin ordering, KSP-generated sources, bytecode bridges, and IDE resolution. These belong in the relevant test layers; a test-only compiler plugin alone cannot exercise all of them.

Keep fixture behavior small and deterministic. Add an example for a distinct testing capability rather than reproducing a production library's feature set.
