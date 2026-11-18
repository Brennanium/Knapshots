package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.EditorRequest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Gradle files and optional project files required to open a generated editor-test project. */
public data class EditorProjectTemplate(
  val settingsGradleKts: String,
  val buildGradleKts: String,
  val gradleProperties: String? = null,
  val additionalFiles: Map<String, String> = emptyMap(),
  /** Existing regular files copied into every generated project, such as a Gradle wrapper. */
  val copiedFiles: Map<String, Path> = emptyMap(),
)

/** Paths created for one editor inspection. The runner owns deletion after IDE shutdown. */
public data class MaterializedEditorProject(
  val directory: Path,
  val requestPath: Path,
  val responsePath: Path,
)

/**
 * Creates a fresh Gradle project for an [EditorRequest]. It intentionally does not invoke Gradle
 * or an IDE; those lifecycle concerns belong to the runner.
 */
public class EditorProjectMaterializer {
  public fun materialize(
    directory: Path,
    template: EditorProjectTemplate,
    request: EditorRequest,
  ): MaterializedEditorProject {
    require(!Files.exists(directory)) { "Editor project directory already exists: $directory" }
    val textFiles = buildList {
      add("settings.gradle.kts" to template.settingsGradleKts)
      add("build.gradle.kts" to template.buildGradleKts)
      template.gradleProperties?.let { add("gradle.properties" to it) }
      addAll(template.additionalFiles.entries.map { (path, contents) -> path to contents })
      addAll(request.sources.map { source -> "src/main/kotlin/${source.fileName}" to source.text })
    }
    val copiedFiles = template.copiedFiles.entries.map { (relativePath, source) ->
      require(Files.isRegularFile(source)) { "Copied editor-project file must be a regular file: $source" }
      relativePath to source
    }
    val destinations = (textFiles.map { it.first } + copiedFiles.map { it.first })
      .map { relativePath -> resolve(directory, relativePath) }
    require(destinations.distinct().size == destinations.size) { "Generated editor-project files must not overlap." }
    val reserved = setOf(
      directory.resolve(".knapshots/editor-request.properties"),
      directory.resolve(".knapshots/editor-response.properties"),
    )
    require(destinations.none(reserved::contains)) { "Generated editor-project files cannot replace Knapshots protocol files." }

    Files.createDirectories(directory)
    textFiles.zip(destinations.take(textFiles.size)).forEach { (file, destination) -> write(destination, file.second) }
    copiedFiles.zip(destinations.drop(textFiles.size)).forEach { (file, destination) ->
      Files.createDirectories(checkNotNull(destination.parent))
      Files.copy(file.second, destination, StandardCopyOption.COPY_ATTRIBUTES)
    }

    val requestPath = directory.resolve(".knapshots/editor-request.properties")
    val responsePath = directory.resolve(".knapshots/editor-response.properties")
    EditorInspectionProtocol.writeRequest(request, requestPath)
    return MaterializedEditorProject(directory, requestPath, responsePath)
  }

  private fun write(destination: Path, contents: String) {
    Files.createDirectories(checkNotNull(destination.parent))
    Files.writeString(destination, contents, StandardCharsets.UTF_8)
  }

  private fun resolve(directory: Path, relativePath: String): Path {
    require(relativePath.isNotBlank()) { "Generated editor-project paths must not be blank." }
    val candidate = Path.of(relativePath)
    require(!candidate.isAbsolute && !relativePath.contains('\\') && candidate.none { it.toString() == ".." }) {
      "Generated editor-project paths must be relative and use forward slashes: $relativePath"
    }
    val destination = directory.resolve(candidate).normalize()
    require(destination.startsWith(directory)) { "Generated editor-project path escapes its directory: $relativePath" }
    return destination
  }
}
