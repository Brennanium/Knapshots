package dev.knapshots.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CompilerCompatibilityPluginTest {
  @Test fun `task passes selected versions to the configured driver`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    val driver = dir.resolve("driver.sh")
    Files.writeString(driver, "#!/bin/sh\nprintf '%s\\n' \"\$2\" >> versions.txt\necho driver-message-\"\$2\"\n")
    check(driver.toFile().setExecutable(true))
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { id("dev.knapshots.compatibility") }
      knapshotsCompatibility { compilerDriver.set(layout.projectDirectory.file("driver.sh")) }
    """.trimIndent())

    val result = runner(dir).withArguments(
      "compilerCompatibility", "--kotlin-version", "2.4.20", "--kotlin-version", "2.4.21", "--stacktrace",
    ).build()

    assertContains(result.output, "driver-message-2.4.21")
    assertEquals(listOf("2.4.20", "2.4.21"), Files.readAllLines(dir.resolve("versions.txt")))
  }

  @Test fun `passes platform selection and records individual check results`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    val driver = dir.resolve("driver.sh")
    Files.writeString(driver, "#!/bin/sh\nprintf '%s\\n' \"\$*\" > args.txt\nprintf '%s\\n' \"\$KPATHS_TEST_CHECKOUT\" > env.txt\necho 'KNAPSHOTS_CHECK jvm passed'\necho 'KNAPSHOTS_CHECK native not-run'\n")
    check(driver.toFile().setExecutable(true))
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { id("dev.knapshots.compatibility") }
      knapshotsCompatibility {
        compilerDriver.set(layout.projectDirectory.file("driver.sh"))
        driverEnvironment.put("KPATHS_TEST_CHECKOUT", "/custom checkout")
      }
    """.trimIndent())
    runner(dir).withArguments("compilerCompatibility", "--kotlin-version", "2.4.21", "--platform", "jvm,native").build()
    assertEquals("/custom checkout", Files.readString(dir.resolve("env.txt")).trim())
    assertContains(Files.readString(dir.resolve("args.txt")), "--kotlin-version 2.4.21 --platform jvm,native")
    val report = Files.readString(dir.resolve("build/reports/knapshots/compiler-compatibility.json"))
    assertContains(report, "\"jvm\": \"passed\"")
    assertContains(report, "\"native\": \"not_run\"")
    assertContains(report, "\"successful\": true")
  }

  @Test fun `a missing driver fails clearly`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    Files.writeString(dir.resolve("build.gradle.kts"), "plugins { id(\"dev.knapshots.compatibility\") }")
    val result = runner(dir).withArguments("compilerCompatibility", "--kotlin-version", "2.4.21").buildAndFail()
    assertContains(result.output, "Configure knapshotsCompatibility.compilerDriver")
  }

  private fun runner(dir: Path): GradleRunner = GradleRunner.create()
    .withProjectDir(dir.toFile()).withPluginClasspath()
}
