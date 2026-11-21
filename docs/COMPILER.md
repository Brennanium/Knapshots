# Testing compiler plugins

A compiler test lets you describe a small piece of source and check what your plugin does with it. For example, a plugin that generates a member can be tested without creating a separate application for every case:

```kotlin
@Test fun `generates a greeting`() {
  assertCompiler("""
    @GenerateGreeting class Person
  """)
}
```

On the first run, Knapshots records the configured compiler output and diagnostics inline and fails the test for review. Run it again to check the result. Later changes produce a focused diff. See [Output and diagnostics](PLUGIN-TESTING.md#output-and-diagnostics) for recording modes and narrower checks.

## Check a diagnostic

When the outcome matters more than a complete output snapshot, describe the expected behavior:

```kotlin
assertCompiler("""
  @GenerateGreeting fun unsupported() = Unit
""") {
  fails()
  diagnostics { error("Expected a class", line = 1) }
}
```

The diagnostic check verifies the message and location, and unexpected errors fail the test. Use `diagnostics()` to record all diagnostics inline instead.

## Configure the plugin under test

Add `knapshots-compiler` and apply the Knapshots Gradle plugin as shown in [Getting started](GETTING-STARTED.md). When this project declares a standard `CompilerPluginRegistrar` service in `src/main/resources/META-INF/services`, Knapshots uses the project's JAR automatically. Otherwise, configure the artifact to load:

```kotlin
knapshots {
  compilerPlugin {
    artifact.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    id.set("example.compiler-plugin")
    firOption.set("firSnapshot")
    irOption.set("irSnapshot")
  }
}
```

The example uses the JAR built by this project. For a plugin built elsewhere, point `artifact` at that artifact and ensure its producing task runs before the tests. FIR and IR options are needed only when the plugin supplies those captures. Knapshots can infer the compiler plugin ID from an already-applied compatible Kotlin Gradle plugin. The test runtime classpath is used for types imported by the snippet.

[Compiler and editor assertion details](PLUGIN-TESTING.md) covers version-specific snapshots, bytecode, compiler hosts, and the roles of Gradle and IDE plugins.
