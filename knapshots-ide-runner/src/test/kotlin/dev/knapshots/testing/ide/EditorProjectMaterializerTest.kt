package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.EditorRequest
import dev.knapshots.testing.editor.EditorSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorProjectMaterializerTest {
  private val template = EditorProjectTemplate(
    settingsGradleKts = "rootProject.name = \"editor-fixture\"",
    buildGradleKts = "plugins { kotlin(\"jvm\") version \"2.4.21\" }",
    additionalFiles = mapOf("gradle/libs.versions.toml" to "[versions]"),
  )

  @Test fun `creates an isolated Gradle project and writes its protocol request`(@TempDir directory: Path) {
    val request = EditorRequest(
      sources = listOf(EditorSource("example/Example.kt", "class Example")),
      referenceExpressions = setOf("Example"),
      captureDiagnostics = true,
    )

    val project = EditorProjectMaterializer().materialize(directory.resolve("consumer"), template, request)

    assertEquals("rootProject.name = \"editor-fixture\"", Files.readString(project.directory.resolve("settings.gradle.kts")))
    assertEquals("class Example", Files.readString(project.directory.resolve("src/main/kotlin/example/Example.kt")))
    assertEquals(request, EditorInspectionProtocol.readRequest(project.requestPath))
    assertTrue(project.responsePath.startsWith(project.directory))
  }

  @Test fun `rejects existing and escaping project paths`(@TempDir directory: Path) {
    val request = EditorRequest(listOf(EditorSource("../outside.kt", "class Outside")), emptySet(), true)
    val materializer = EditorProjectMaterializer()
    assertFailsWith<IllegalArgumentException> {
      materializer.materialize(directory.resolve("consumer"), template, request)
    }
    assertTrue(!Files.exists(directory.resolve("consumer")))

    val existing = directory.resolve("existing")
    Files.createDirectories(existing)
    assertFailsWith<IllegalArgumentException> {
      materializer.materialize(existing, template, EditorRequest(listOf(EditorSource("Example.kt", "class Example")), emptySet(), true))
    }
  }
}
