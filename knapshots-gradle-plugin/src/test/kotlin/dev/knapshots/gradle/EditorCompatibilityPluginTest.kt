package dev.knapshots.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorCompatibilityPluginTest {
  @Test fun `test configuration is compatible with Gradle configuration cache`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    Files.writeString(dir.resolve("compiler.jar"), "test compiler artifact")
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { java; id("io.github.brennanium.knapshots.compatibility") }
      knapshots {
        recording.set("missing")
        recordCompilerVersion.set(true)
        ksp { processorClasspath.from(files("compiler.jar")) }
        compilerPlugin {
          artifact.set(layout.projectDirectory.file("compiler.jar"))
          id.set("example.compiler-plugin")
          firOption.set("firSnapshot")
        }
      }
      tasks.test { failOnNoDiscoveredTests = false }
    """.trimIndent())
    val testSource = dir.resolve("src/test/java/example/Example.java")
    Files.createDirectories(testSource.parent)
    Files.writeString(testSource, "package example; public class Example {}")
    val runner = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("test", "--configuration-cache")
    runner.build()
    val second = runner.build()
    assertContains(second.output, "Configuration cache entry reused")
  }

  @Test fun `infers a sole declared plugin but avoids guessing among several`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    fun build(extra: String, assertion: String) {
      Files.writeString(dir.resolve("build.gradle.kts"), """
        plugins { `java-gradle-plugin`; id("io.github.brennanium.knapshots.compatibility") }
        gradlePlugin { plugins {
          create("first") { id = "example.first"; implementationClass = "example.Plugin" }
          $extra
        } }
        tasks.register("inspectPlugin") { doLast {
          val args = tasks.named<Test>("test").get().jvmArgumentProviders.flatMap { it.asArguments() }
          $assertion
        } }
      """.trimIndent())
      GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath().withArguments("inspectPlugin").build()
    }
    build("", "check(\"-Dknapshots.editor.pluginId=example.first\" in args)")
    build("create(\"second\") { id = \"example.second\"; implementationClass = \"example.Other\" }",
      "check(args.none { it.startsWith(\"-Dknapshots.editor.pluginId=\") })")
  }

  @Test fun `enables editor extension discovery for JUnit test tasks`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    Files.writeString(dir.resolve("runtime.jar"), "placeholder")
    Files.createDirectories(dir.resolve("gradle"))
    Files.writeString(dir.resolve("gradle/libs.versions.toml"), "[versions]\nkotlin = \"2.4.21\"\n")
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { java; id("io.github.brennanium.knapshots.compatibility") }
      version = "3.4.5"
      val fixture = layout.projectDirectory.file("runtime.jar")
      tasks.named<Test>("test") { classpath = files(fixture) }
      knapshots {
        gradlePlugin { id.set("example.plugin") }
        compilerPlugin {
          id.set("example.compiler-plugin")
          artifact.set(layout.projectDirectory.file("compiler.jar"))
          firOption.set("firSnapshot")
          irOption.set("irSnapshot")
        }
        recording.set("missing")
        recordCompilerVersion.set(true)
        idePlugin { kefs { idePlugin.set("example:ide-plugin") } }
        dependencies.add("example:library:1")
      }
      tasks.register("inspectTestConfiguration") {
        doLast {
          val test = tasks.named<Test>("test").get()
          check(test.systemProperties["junit.jupiter.extensions.autodetection.enabled"] == "true")
          val args = test.jvmArgumentProviders.flatMap { it.asArguments() }
          check("-Dknapshots.editor.pluginId=example.plugin" in args)
          check("-Dknapshots.compiler.pluginId=example.compiler-plugin" in args)
          check(args.any { it.startsWith("-Dknapshots.compiler.pluginJar=") && it.endsWith("compiler.jar") })
          check("-Dknapshots.compiler.firOption=firSnapshot" in args)
          check("-Dknapshots.compiler.irOption=irSnapshot" in args)
          check("-Dknapshots.compiler.record=missing" in args)
          check("-Dknapshots.ksp.record=missing" in args)
          check("-Dknapshots.ksp.recordVersion=true" in args)
          check("-Dknapshots.compiler.recordVersion=true" in args)
          check(args.any { it.startsWith("-Dknapshots.compiler.classpath=") && it.endsWith("runtime.jar") })
          check("-Dknapshots.editor.kefsCoordinate=example:ide-plugin" in args)
          check("-Dknapshots.editor.pluginVersion=3.4.5" in args)
          check("-Dknapshots.editor.kotlinVersion=2.4.21" in args)
          check(args.any { it.startsWith("-Dknapshots.editor.classpath=") && it.endsWith("runtime.jar") })
          check("-Dknapshots.editor.dependencies=example:library:1" in args)
        }
      }
    """.trimIndent())
    GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("inspectTestConfiguration").build()
  }

  @Test fun `infers compiler plugin artifact from its registrar service`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    val service = dir.resolve("src/main/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar")
    Files.createDirectories(service.parent)
    Files.writeString(service, "fixture.Registrar")
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { java; id("io.github.brennanium.knapshots.compatibility") }
      tasks.register("probeCompiler") { dependsOn("jar"); doLast {
        val args = tasks.named<Test>("test").get().jvmArgumentProviders.flatMap { it.asArguments() }
        check(args.any { it.startsWith("-Dknapshots.compiler.pluginJar=") && it.endsWith("consumer.jar") })
      } }
    """.trimIndent())
    GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath().withArguments("probeCompiler").build()
  }

  @Test fun `KSP uses catalog version and explicit configuration wins`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    Files.createDirectories(dir.resolve("gradle"))
    Files.writeString(dir.resolve("gradle/libs.versions.toml"), "[versions]\nkotlin = \"2.4.21\"\nksp = \"2.3.10\"\n")
    Files.writeString(dir.resolve("processor.jar"), "processor")
    val service = dir.resolve("src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider")
    Files.createDirectories(service.parent)
    Files.writeString(service, "fixture.Provider")
    fun run(override: String, expected: String) {
      Files.writeString(dir.resolve("build.gradle.kts"), """
        plugins { java; id("io.github.brennanium.knapshots.compatibility") }
        knapshots { ksp { processorClasspath.from(files("processor.jar")); $override } }
        tasks.register("probeKsp") { doLast {
          val args = tasks.named<Test>("test").get().jvmArgumentProviders.flatMap { it.asArguments() }
          check("-Dknapshots.ksp.version=$expected" in args)
          check("-Dknapshots.ksp.kotlinVersion=2.4.21" in args)
          check(args.any { it.startsWith("-Dknapshots.ksp.processorClasspath=") && it.endsWith("processor.jar") })
        } }
      """.trimIndent())
      GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath().withArguments("probeKsp").build()
    }
    run("", "2.3.10")
    run("version.set(\"2.3.12\")", "2.3.12")
  }

  @Test fun `editor recording configures an isolated worker classpath`(@TempDir dir: Path) {
    val sourceRoot = checkNotNull(System.getProperty("knapshots.sourceRoot")).replace("\\", "/")
    Files.writeString(dir.resolve("settings.gradle.kts"), """
      pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
      dependencyResolutionManagement { repositories { mavenCentral() } }
      rootProject.name = "consumer"
      includeBuild("$sourceRoot")
    """.trimIndent())
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { java; id("io.github.brennanium.knapshots.compatibility") }
      dependencies { testImplementation("io.github.brennanium:knapshots-editor:0.1.0-SNAPSHOT") }
      knapshots { recording.set("missing") }
      tasks.register("probe") { doLast {
        val args = tasks.named<Test>("test").get().jvmArgumentProviders.flatMap { it.asArguments() }
        check("-Dknapshots.editor.record=missing" in args)
        val worker = args.single { it.startsWith("-Dknapshots.editor.recorderClasspath=") }
        check(worker.contains("knapshots-compiler"))
        check(worker.contains("kotlin-compiler-embeddable"))
        println("ISOLATED_RECORDER_CONFIGURED")
      } }
    """.trimIndent())
    val result = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath().withArguments("probe").build()
    assertContains(result.output, "ISOLATED_RECORDER_CONFIGURED")
    val buildFile = dir.resolve("build.gradle.kts")
    val originalBuild = Files.readString(buildFile)
    Files.writeString(buildFile, originalBuild.replace("recording.set(\"missing\")", "recording.set(\"none\")")
      .replace("-Dknapshots.editor.record=missing", "-Dknapshots.editor.record=none"))
    val perAssertion = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath().withArguments("probe").build()
    assertContains(perAssertion.output, "ISOLATED_RECORDER_CONFIGURED")
    Files.writeString(buildFile, originalBuild)
    val javaSource = dir.resolve("src/test/java/example/Example.java")
    Files.createDirectories(javaSource.parent)
    Files.writeString(javaSource, "package example; public class Example {}")
    Files.writeString(dir.resolve("build.gradle.kts"), Files.readString(dir.resolve("build.gradle.kts")) + "\ntasks.test { failOnNoDiscoveredTests = false }\n")
    val cachedRunner = GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath().withArguments("test", "--configuration-cache")
    cachedRunner.build()
    assertContains(cachedRunner.build().output, "Configuration cache entry reused")
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
    Files.writeString(driver, "#!/bin/sh\nprintf '%s\\n' \"\$2\" >> ide.txt\nprintf '%s\\n' \"\$KNAPSHOTS_IDE_PATH\" > ide-env.txt\nprintf '%s\\n' \"\$KPATHS_TEST_CHECKOUT\" > checkout-env.txt\nprintf '%s\\n' \"\$KNAPSHOTS_IDE_RELEASE_BUILD|\$KNAPSHOTS_IDE_RELEASE_VERSION|\$KNAPSHOTS_IDE_RELEASE_NAME\" > release-env.txt\necho editor-driver-ran\n")
    check(driver.toFile().setExecutable(true))
    Files.writeString(dir.resolve("build.gradle.kts"), """
      plugins { id("io.github.brennanium.knapshots.compatibility") }
      knapshotsCompatibility {
        editorDriver.set(layout.projectDirectory.file("editor-driver.sh"))
        releaseFeed.set(layout.projectDirectory.file("releases.xml"))
        idePath.set(layout.projectDirectory.dir("studio"))
        driverEnvironment.put("KPATHS_TEST_CHECKOUT", "/custom checkout")
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
    assertEquals(ide.toRealPath(), Path.of(Files.readString(dir.resolve("ide-env.txt")).trim()).toRealPath())
    assertEquals("/custom checkout", Files.readString(dir.resolve("checkout-env.txt")).trim())
    assertEquals("AI-262.123|2026.2.2.8|Studio", Files.readString(dir.resolve("release-env.txt")).trim())
    assertContains(Files.readString(dir.resolve("build/reports/knapshots/editor-compatibility.json")), "\"result\": \"passed\"")
  }

  @Test fun `writes failure report when driver is missing`(@TempDir dir: Path) {
    Files.writeString(dir.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"")
    Files.writeString(dir.resolve("build.gradle.kts"), "plugins { id(\"io.github.brennanium.knapshots.compatibility\") }")
    GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
      .withArguments("editorCompatibility", "--ide-version", "2026.2.2.8").buildAndFail()
    val report = Files.readString(dir.resolve("build/reports/knapshots/editor-compatibility.json"))
    assertContains(report, "\"result\": \"failed\"")
    assertContains(report, "editorDriver")
  }
}
