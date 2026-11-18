package dev.knapshots.testing.compiler

import java.nio.file.Files
import java.nio.file.Path

/** Creates a fresh temporary directory for each compilation and removes it after the host returns. */
public fun temporaryCompilerHost(create: (Path) -> CompilerHost): CompilerHost = CompilerHost { request ->
  val directory = Files.createTempDirectory("knapshots-compiler-host-")
  var failure: Throwable? = null
  try {
    create(directory).compile(request)
  } catch (error: Throwable) {
    failure = error
    throw error
  } finally {
    try {
      Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    } catch (cleanupError: Throwable) {
      if (failure == null) throw cleanupError
      failure.addSuppressed(cleanupError)
    }
  }
}
