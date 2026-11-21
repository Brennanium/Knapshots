import dev.knapshots.testing.compiler.assertCompiler
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class InferenceTest {
  @Test fun `loads the current project plugin automatically`() {
    System.clearProperty("knapshots.consumer.compilerLoaded")
    assertCompiler("class Example") { succeeds() }
    assertEquals("yes", System.getProperty("knapshots.consumer.compilerLoaded"))
  }
}
