import dev.knapshots.testing.compiler.*
import dev.knapshots.testing.compiler.gradle.GradleCompilerHost
import dev.knapshots.testing.snapshot.*
import org.junit.jupiter.api.Test
import dev.knapshots.testing.ksp.assertKsp

class ConsumerTest {
  @Test fun snapshot() {
    kotlin.test.assertEquals("2.4.21", System.getProperty("knapshots.editor.kotlinVersion"))
    assertSnapshot("hello", "hello", textSnapshotting)
  }
  @Test fun embeddedCompiler() {
    assertCompiler("class Example", "2.4.21", EmbeddedCompilerHost()) { succeeds() }
  }
  @Test fun gradleCompiler() {
    assertCompiler("class Example", "2.4.21", GradleCompilerHost()) { succeeds() }
  }
  @Test fun configuredCompilerPlugin() {
    assertCompiler("""
      package fixture
      annotation class AddMember
      @AddMember class Example
    """) {
      fir("FIR classes\nfixture.AddMember\nfixture.Example")
      ir("IR replacements: 0\nIR generated members: 1")
    }
  }
  @Test fun configuredProcessor() {
    assertKsp("""
      package fixture
      annotation class AddGreeting
      @AddGreeting class Example
    """) { generated("// FILE: kotlin/fixture/Generated.kt\npackage fixture\nobject Generated") }
  }
}
