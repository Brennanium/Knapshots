package dev.knapshots.testing.compatibility

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class KotlinVersionsTest {
  @Test fun `selects newest common dev build across channels and artifacts`() {
    val resolver = KotlinVersions { channel, artifact ->
      when {
        channel == "dev" && artifact == "kotlin-gradle-plugin" -> setOf("2.5.0-dev-9", "2.5.0-dev-100", "2.5.0-dev-101")
        channel == "experimental" && artifact == "kotlin-compiler-embeddable" -> setOf("2.5.0-dev-9", "2.5.0-dev-100")
        else -> emptySet()
      }
    }
    assertEquals(
      listOf(KotlinVersionSelection("2.4.21", "2.4.21"), KotlinVersionSelection("2.5.0-dev*", "2.5.0-dev-100")),
      resolver.resolve(listOf("2.4.21", "2.5.0-dev*")),
    )
  }

  @Test fun `rejects invalid and unavailable selectors`() {
    val resolver = KotlinVersions { _, _ -> emptySet() }
    assertFailsWith<IllegalArgumentException> { resolver.resolve(listOf("2.5.*")) }
    assertFailsWith<IllegalArgumentException> { resolver.resolve(listOf("2.5.0-dev*")) }
  }

  @Test fun `exact versions need no network lookup`() {
    val resolver = KotlinVersions { _, _ -> error("unexpected metadata lookup") }
    assertEquals("2.5.0-dev-12", resolver.resolve(listOf("2.5.0-dev-12")).single().version)
  }
}
