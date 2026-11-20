package dev.knapshots.testing.editor.intellij

class EditorFixtureTest : EditorFixtureTestCase() {
  fun testHostsSourceWithoutDiagnostics() {
    assertEditor(source = "plain text", fileName = "Example.txt") { diagnostics { snapshot("") } }
  }

  fun testUnavailableReferenceIsAnAssertionFailure() {
    val failure = runCatching {
      assertEditor(source = "plain text", fileName = "Example.txt") {
        reference("missing").resolves()
      }
    }.exceptionOrNull()
    assertTrue(failure is AssertionError)
    assertTrue(failure?.message?.contains("Cannot inspect reference") == true)
  }
  fun testResolvesReferencesAcrossSourceFiles() {
    assertEditor(
      fileName = "Use.java",
      source = "class Use { int read(Model model) { return model.value; } }",
      additionalSources = mapOf("Model.java" to "class Model { int value; }"),
    ) {
      reference("model.value").resolves()
    }
  }

  fun testReportsUnexpectedRealDiagnostics() {
    val failure = runCatching {
      assertEditor(fileName = "Broken.java", source = "class Broken { int value = missing; }") { diagnostics { } }
    }.exceptionOrNull()
    assertTrue(failure is AssertionError)
    assertTrue(failure?.message?.contains("Unexpected ERROR") == true)
  }

  fun testMatchesExpectedDiagnosticAndUnresolvedReference() {
    assertEditor(fileName = "Broken.java", source = "class Broken { int read() { return this.missing; } }") {
      diagnostics { error("Cannot resolve symbol") }
      reference("this.missing").isUnresolved()
    }
  }
  fun testVersionedExpectationsUseActualIdeBuild() {
    val build = com.intellij.openapi.application.ApplicationInfo.getInstance().build.asString()
    assertEditor(source = "plain text", fileName = "Example.txt") {
      diagnostics { default("should not be selected"); versioned(build, "") }
    }
    val releaseVersion = System.getenv("KNAPSHOTS_IDE_RELEASE_VERSION")
    val releaseName = System.getenv("KNAPSHOTS_IDE_RELEASE_NAME")
    if (releaseVersion != null && releaseName != null) {
      val name = releaseName.substringBefore('|').trim().removePrefix("Android Studio ")
      val selectors = listOf(releaseVersion, releaseVersion.substringBeforeLast('.') + ".*", name, name.substringBefore(' '))
      selectors.distinct().forEach { selector ->
        assertEditor(source = "plain text", fileName = "Example.txt") {
          diagnostics { default("should not be selected"); versioned(selector, "") }
        }
      }
    }
  }

}
