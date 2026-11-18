package dev.knapshots.testing.compatibility

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

class CompilerCompatibilityTest {
  @Test fun `runs distinct versions after a failure and preserves skipped checks`() {
    val calls = mutableListOf<String>()
    val runner = CompilerCompatibility(driver = CompilerCompatibilityDriver { version ->
      calls += version
      if (version == "2.4.20") throw IllegalStateException("driver failed")
      CompatibilityResult(mapOf("jvm" to CompatibilityStatus.PASSED, "native" to CompatibilityStatus.NOT_RUN))
    })
    val report = runner.run(listOf("2.4.20", "2.4.21", "2.4.21"))
    assertEquals(listOf("2.4.20", "2.4.21"), calls)
    assertFalse(report.successful)
    assertEquals(CompatibilityStatus.FAILED, report.entries.first().result.checks["driver"])
    assertEquals(CompatibilityStatus.NOT_RUN, report.entries.last().result.checks["native"])
  }

  @Test fun `an entirely skipped version is not successful`() {
    val runner = CompilerCompatibility(driver = CompilerCompatibilityDriver {
      CompatibilityResult(mapOf("jvm" to CompatibilityStatus.NOT_RUN))
    })
    assertFalse(runner.run(listOf("2.4.21")).successful)
  }
}
