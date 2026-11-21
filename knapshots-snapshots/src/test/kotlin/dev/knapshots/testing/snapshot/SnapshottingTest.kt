package dev.knapshots.testing.snapshot

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SnapshottingTest {
  @Test fun `pullback adapts a text strategy without losing its comparison`() {
    data class Result(val output: String)
    val strategy = textSnapshotting.pullback<Result> { it.output }
    assertNull(compareSnapshot(Result("ok"), "ok", strategy))
    val difference = assertNotNull(compareSnapshot(Result("actual"), "expected", strategy))
    assertEquals("expected", difference.expected)
    assertEquals("actual", difference.actual)
  }

  @Test fun `capture and diffing are supplied by the strategy`() {
    var captures = 0
    val strategy = Snapshotting<Int, Int>(Diffing { expected, actual ->
      if (expected == actual) null else "different numbers"
    }) { value -> captures++; value * 2 }
    assertEquals("different numbers", compareSnapshot(2, 3, strategy)?.message)
    assertEquals(1, captures)
  }

  @Test fun `assertion uses the strategy and renders a useful failure`() {
    val failure = assertFailsWith<AssertionError> {
      assertSnapshot("actual", "expected", textSnapshotting, name = "profile")
    }

    assertContains(failure.message.orEmpty(), "Snapshot 'profile' differs")
    assertContains(failure.message.orEmpty(), "− expected")
    assertContains(failure.message.orEmpty(), "+ actual")
  }
  @Test fun `snapshot indentation preserves embedded carriage returns and sentinel characters`() {
    assertEquals("first\r\nsecond\rthird\u0000", trimSnapshotIndent("  first\r\n  second\rthird\u0000"))
    assertEquals("alpha\nbeta", trimSnapshotIndent("  alpha\n  beta"))
  }

}
