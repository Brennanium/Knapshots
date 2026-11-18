package dev.knapshots.testing.ide

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DirectIdeProcessLauncherTest {
  private fun archive(path: Path, entry: String) {
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
      zip.putNextEntry(ZipEntry(entry))
      zip.write("fixture".toByteArray())
      zip.closeEntry()
    }
  }

  @Test fun `launches with isolated plugin installation and project settings`(@TempDir directory: Path) {
    val ide = Files.createDirectories(directory.resolve("studio/bin"))
    val executable = ide.resolve("studio")
    Files.writeString(executable, "#!/bin/sh\ntest -f \"$" + "STUDIO_VM_OPTIONS\"\n")
    check(executable.toFile().setExecutable(true))
    val archive = directory.resolve("adapter.zip").also { archive(it, "adapter/lib/adapter.jar") }
    val project = Files.createDirectories(directory.resolve("inspection/project"))
    val result = DirectIdeProcessLauncher(ide.parent, listOf(archive)).launch(
      MaterializedEditorProject(project, project.resolve("request"), project.resolve("response")),
    )
    assertEquals(0, result.exitCode)
    val sandbox = directory.resolve("inspection/ide-sandbox")
    assertTrue(Files.isRegularFile(sandbox.resolve("plugins/adapter/lib/adapter.jar")))
    assertTrue(Files.readString(sandbox.resolve("config/options/trusted-paths.xml")).contains(project.toString()))
    assertTrue(Files.readString(sandbox.resolve("studio.vmoptions")).contains("-Dknapshots.editor.project=$project"))
  }

  @Test fun `trusts both the original project path and its resolved alias`(@TempDir directory: Path) {
    val real = Files.createDirectories(directory.resolve("real/project"))
    val link = Files.createSymbolicLink(directory.resolve("alias"), real.parent)
    val throughLink = link.resolve("project")
    assertTrue(throughLink in trustedProjectPaths(throughLink))
    assertTrue(real.toRealPath() in trustedProjectPaths(throughLink))
    assertTrue(trustedProjectPaths(throughLink).all { it.toRealPath() == real.toRealPath() })
  }

  @Test fun `rejects archive paths outside sandbox`(@TempDir directory: Path) {
    val archive = directory.resolve("bad.zip").also { archive(it, "../escaped") }
    val project = Files.createDirectories(directory.resolve("inspection/project"))
    assertFailsWith<IllegalArgumentException> {
      DirectIdeProcessLauncher(directory, listOf(archive)).launch(
        MaterializedEditorProject(project, project.resolve("request"), project.resolve("response")),
      )
    }
    assertTrue(Files.notExists(directory.resolve("inspection/escaped")))
  }
}
