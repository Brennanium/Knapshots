package dev.knapshots.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CollectEditorScenarioTaskTest {
  @Test fun `collects passing and skipped reports and rejects traversal`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { id("dev.knapshots.compatibility") }
      knapshotsCompatibility { scenarioJUnitXml.set(layout.projectDirectory.file("result.xml")) }
    """.trimIndent())
    val report = dir.resolve("result.xml")
    fun run(name: String) = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("collectEditorScenario", "--scenario", name)
    Files.writeString(report, "<testsuite tests=\"1\" skipped=\"0\" failures=\"0\" errors=\"0\"/>")
    run("enabled").build()
    assertTrue(Files.isRegularFile(dir.resolve("build/reports/knapshots/scenarios/enabled/junit.xml")))
    Files.writeString(report, "<testsuite tests=\"1\" skipped=\"1\" failures=\"0\" errors=\"0\"/>")
    assertContains(run("disabled").buildAndFail().output, "did not pass without skips")
    assertTrue(Files.isRegularFile(dir.resolve("build/reports/knapshots/scenarios/disabled/junit.xml")))
    Files.writeString(report, "<testsuite tests=\"1\" skipped=\"0\" failures=\"1\" errors=\"0\"/>")
    assertContains(run("failed").buildAndFail().output, "failures=1")
    assertTrue(Files.isRegularFile(dir.resolve("build/reports/knapshots/scenarios/failed/junit.xml")))
    assertContains(run("../escape").buildAndFail().output, "Invalid editor scenario name")
  }
}
