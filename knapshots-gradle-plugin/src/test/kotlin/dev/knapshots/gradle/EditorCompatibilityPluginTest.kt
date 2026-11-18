package dev.knapshots.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorCompatibilityPluginTest {
  @Test fun `enables editor extension discovery for JUnit test tasks`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { java; id("dev.knapshots.compatibility") }
      tasks.register("inspectTestConfiguration") {
        doLast {
          val value = tasks.named<Test>("test").get().systemProperties["junit.jupiter.extensions.autodetection.enabled"]
          check(value == "true")
        }
      }
    """.trimIndent())
    GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("inspectTestConfiguration").build()
  }

  @Test fun `selects release validates local IDE and invokes driver`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    val ide = dir.resolve("studio")
    Files.createDirectories(ide)
    Files.writeString(ide.resolve("product-info.json"), """{"name":"Android Studio","buildNumber":"AI-262.123"}""")
    val feed = """<releases><item><name>Studio</name><version>2026.2.2.8</version><build>AI-262.123</build><channel>Stable</channel>
      <download><link>https://example.test/a-linux.tar.gz</link><checksum>${"a".repeat(64)}</checksum></download>
      <download><link>https://example.test/a-mac_arm.dmg</link><checksum>${"a".repeat(64)}</checksum></download></item></releases>"""
    Files.writeString(dir.resolve("releases.xml"), feed)
    val driver = dir.resolve("editor-driver.sh")
    Files.writeString(driver, "#!/bin/sh\nprintf '%s\\n' \"\$2\" >> ide.txt\necho editor-driver-ran\n")
    check(driver.toFile().setExecutable(true))
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { id("dev.knapshots.compatibility") }
      knapshotsCompatibility {
        editorDriver.set(layout.projectDirectory.file("editor-driver.sh"))
        releaseFeed.set(layout.projectDirectory.file("releases.xml"))
        idePath.set(layout.projectDirectory.dir("studio"))
      }
    """.trimIndent())
    val result = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("editorCompatibility", "--ide-version", "2026.2.2.8").build()
    assertContains(result.output, "262.123")
    assertContains(result.output, "editor-driver-ran")
    val second = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("editorCompatibility", "--ide-version", "2026.2.2.8").build()
    assertContains(second.output, "editor-driver-ran")
    val calls = Files.readAllLines(dir.resolve("ide.txt"))
    assertEquals(2, calls.size)
    assertEquals(ide.toRealPath(), Path.of(calls.first()).toRealPath())
    assertContains(Files.readString(dir.resolve("build/reports/knapshots/editor-compatibility.json")), "\"result\": \"passed\"")
  }

  @Test fun `writes failure report when driver is missing`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    Files.writeString(dir.resolve("build.gradle.kts"), "plugins { id(\"dev.knapshots.compatibility\") }")
    GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("editorCompatibility", "--ide-version", "2026.2.2.8").buildAndFail()
    val report = Files.readString(dir.resolve("build/reports/knapshots/editor-compatibility.json"))
    assertContains(report, "\"result\": \"failed\"")
    assertContains(report, "editorDriver")
  }
}
