package dev.knapshots.testing.editor.intellij

class EditorFixtureTest : EditorFixtureTestCase() {
  fun testHostsSourceWithoutDiagnostics() {
    assertEditor(source = "plain text", fileName = "Example.txt") { }
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
}
