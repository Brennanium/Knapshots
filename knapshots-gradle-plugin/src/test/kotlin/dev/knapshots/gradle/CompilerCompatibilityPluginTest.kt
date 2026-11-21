package dev.knapshots.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import dev.knapshots.testing.compatibility.KotlinVersions
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
      plugins { id("io.github.brennanium.knapshots.compatibility") }
      knapshotsCompatibility { compilerDriver.set(layout.projectDirectory.file("driver.sh")) }
    """.trimIndent())

    val result = runner(dir).withArguments(
      "compilerCompatibility", "--kotlin-version", "2.4.20", "--kotlin-version", "2.4.21", "--stacktrace",
    ).build()

    assertContains(result.output, "driver-message-2.4.21")
    assertEquals(listOf("2.4.20", "2.4.21"), Files.readAllLines(dir.resolve("versions.txt")))
  }

  @Test fun `splits comma separated and repeated selectors including preview families`() {
    val selectors = splitKotlinVersionSelectors(listOf("2.4.20, 2.4.21", "2.5.0-dev*", "2.4.21,2.4.22"))
    assertEquals(listOf("2.4.20", "2.4.21", "2.5.0-dev*", "2.4.21", "2.4.22"), selectors)
    val versions = KotlinVersions { _, _ -> setOf("2.5.0-dev-10", "2.5.0-dev-20") }
    assertEquals("2.5.0-dev-20", versions.resolve(selectors)[2].version)
    for (input in listOf("", ",2.4.20", "2.4.20,", "2.4.20,,2.4.21", "2.4.20,  ")) {
      val error = assertFailsWith<IllegalArgumentException> { splitKotlinVersionSelectors(listOf(input)) }
      assertContains(error.message.orEmpty(), "Empty Kotlin version selector")
    }
  }

  @Test fun `task accepts mixed comma separated and repeated versions`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    val driver = dir.resolve("driver.sh")
    Files.writeString(driver, "#!/bin/sh\nprintf '%s\\n' \"\$2\" >> versions.txt\n")
    check(driver.toFile().setExecutable(true))
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { id("io.github.brennanium.knapshots.compatibility") }
      knapshotsCompatibility { compilerDriver.set(layout.projectDirectory.file("driver.sh")) }
    """.trimIndent())
    runner(dir).withArguments(
      "compilerCompatibility", "--kotlin-version", "2.4.20, 2.4.21", "--kotlin-version", "2.4.21,2.4.22",
    ).build()
    assertEquals(listOf("2.4.20", "2.4.21", "2.4.22"), Files.readAllLines(dir.resolve("versions.txt")))
    val report = Files.readString(dir.resolve("build/reports/knapshots/compiler-compatibility.json"))
    assertContains(report, "\"selector\": \"2.4.20\"")
    assertContains(report, "\"selector\": \"2.4.22\"")
  }

  @Test fun `passes platform selection and records individual check results`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    val driver = dir.resolve("driver.sh")
    Files.writeString(driver, "#!/bin/sh\nprintf '%s\\n' \"\$*\" > args.txt\nprintf '%s\\n' \"\$KPATHS_TEST_CHECKOUT\" > env.txt\necho 'KNAPSHOTS_CHECK jvm passed'\necho 'KNAPSHOTS_CHECK native not-run'\n")
    check(driver.toFile().setExecutable(true))
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { id("io.github.brennanium.knapshots.compatibility") }
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
    Files.writeString(dir.resolve("build.gradle.kts"), "plugins { id(\"io.github.brennanium.knapshots.compatibility\") }")
    val result = runner(dir).withArguments("compilerCompatibility", "--kotlin-version", "2.4.21").buildAndFail()
    assertContains(result.output, "Configure knapshotsCompatibility.compilerDriver")
  }

  private fun runner(dir: Path): GradleRunner = GradleRunner.create()
    .withProjectDir(dir.toFile()).withPluginClasspath()
}
