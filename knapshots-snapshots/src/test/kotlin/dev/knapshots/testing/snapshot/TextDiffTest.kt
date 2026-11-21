package dev.knapshots.testing.snapshot

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TextDiffTest {
  @Test fun `equal strings have no difference`() {
    assertNull(textDiff("same\n", "same\n"))
    assertNull(textDiff("", ""))
  }

  @Test fun `replacement is shown with context`() {
    assertEquals("− expected / + actual\n@@ -1,3 +1,3 @@\n  first\n− before\n+ after\n  last", textDiff("first\nbefore\nlast", "first\nafter\nlast"))
  }

  @Test fun `distant changes form separate hunks with correct line numbers`() {
    val old = (1..20).joinToString("\n") { "line $it" }
    val new = old.replace("line 2\n", "changed 2\n").replace("line 19\n", "changed 19\n")
    val diff = checkNotNull(textDiff(old, new))
    assertContains(diff, "@@ -1,5 +1,5 @@")
    assertContains(diff, "@@ -16,5 +16,5 @@")
    assertContains(diff, "− line 2\n+ changed 2")
    assertContains(diff, "− line 19\n+ changed 19")
    assertFalse("line 10" in diff)
  }

  @Test fun `insertions and deletions show the changed lines`() {
    assertContains(checkNotNull(textDiff("a\nc", "a\nb\nc")), "+ b")
    assertContains(checkNotNull(textDiff("a\nb\nc", "a\nc")), "− b")
  }

  @Test fun `trailing whitespace and final newline differences are visible`() {
    assertContains(checkNotNull(textDiff("hello ", "hello\t")), "− hello·\n+ hello⇥")
    assertContains(checkNotNull(textDiff("hello", "hello\n")), "No newline at end of expected")
    assertContains(checkNotNull(textDiff("hello\n", "hello")), "No newline at end of actual")
    assertContains(checkNotNull(textDiff("hello\r\n", "hello\n")), "hello␍")
  }

  @Test fun `repeated lines retain useful context`() {
    val diff = checkNotNull(textDiff("a\nb\na\nc", "a\nb\nx\na\nc"))
    assertContains(diff, "+ x")
    assertContains(diff, "  c")
  }

  @Test fun `large changed region is bounded`() {
    val old = (1..1500).joinToString("\n") { "old $it" }
    val new = (1..1500).joinToString("\n") { "new $it" }
    val diff = checkNotNull(textDiff(old, new))
    assertContains(diff, "− old 1")
    assertContains(diff, "+ new 1500")
  }

  @Test fun `large snapshots use bounded fallback and retain outer context`() {
    val old = (1..1500).joinToString("\n") { "line $it" }
    val new = old.replace("line 750\n", "replacement\n")
    val diff = checkNotNull(textDiff(old, new))
    assertContains(diff, "− line 750\n+ replacement")
    assertFalse("line 100\n" in diff)
  }
}
