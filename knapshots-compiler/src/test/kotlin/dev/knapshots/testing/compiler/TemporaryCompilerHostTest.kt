package dev.knapshots.testing.compiler

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class TemporaryCompilerHostTest {
  private val request = CompilerRequest(listOf(CompilerSource("Main.kt", "class Example")), "2.4.21", emptySet())

  @Test fun `creates and cleans a fresh directory for every compilation`() {
    val directories = mutableListOf<Path>()
    val host = temporaryCompilerHost { directory ->
      directories.add(directory)
      Files.writeString(directory.resolve("output"), "result")
      CompilerHost { CompilerResult(succeeded = true) }
    }
    assertTrue(host.compile(request).succeeded)
    assertTrue(host.compile(request).succeeded)
    assertEquals(2, directories.size)
    assertNotEquals(directories[0], directories[1])
    assertTrue(directories.all(Files::notExists))
  }

  @Test fun `cleans up when compilation fails`() {
    lateinit var directory: Path
    val host = temporaryCompilerHost { path ->
      directory = path
      Files.writeString(path.resolve("partial"), "output")
      CompilerHost { error("compilation failed") }
    }
    assertFailsWith<IllegalStateException> { host.compile(request) }
    assertTrue(Files.notExists(directory))
  }
}
