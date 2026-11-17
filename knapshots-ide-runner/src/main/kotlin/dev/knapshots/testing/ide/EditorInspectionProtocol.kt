package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.DiagnosticSeverity
import dev.knapshots.testing.editor.EditorDiagnostic
import dev.knapshots.testing.editor.EditorRequest
import dev.knapshots.testing.editor.EditorSource
import dev.knapshots.testing.editor.ReferenceObservation
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

/** The versioned request/result transport used between an IDE runner and its IDE-side adapter. */
public object EditorInspectionProtocol {
  public const val VERSION: Int = 1

  /** Writes a complete editor inspection request without relying on a project-specific API. */
  public fun writeRequest(request: EditorRequest, path: Path) {
    val properties = Properties().apply {
      setProperty("version", VERSION.toString())
      setProperty("kind", "request")
      setProperty("captureDiagnostics", request.captureDiagnostics.toString())
      setProperty("sources", request.sources.size.toString())
      request.sources.forEachIndexed { index, source ->
        setEncoded("source.$index.fileName", source.fileName)
        setEncoded("source.$index.text", source.text)
      }
      setProperty("references", request.referenceExpressions.size.toString())
      request.referenceExpressions.sorted().forEachIndexed { index, expression ->
        setEncoded("reference.$index.expression", expression)
      }
    }
    write(properties, path)
  }

  /** Reads an IDE inspection request and rejects malformed or incompatible inputs. */
  public fun readRequest(path: Path): EditorRequest {
    val properties = read(path, "request")
    val sources = List(properties.count("sources")) { index ->
      EditorSource(
        fileName = properties.encoded("source.$index.fileName"),
        text = properties.encoded("source.$index.text"),
      )
    }
    val references = List(properties.count("references")) { index ->
      properties.encoded("reference.$index.expression")
    }.toSet()
    require(references.size == properties.count("references")) { "Editor inspection request has duplicate references." }
    return EditorRequest(
      sources = sources,
      referenceExpressions = references,
      captureDiagnostics = properties.boolean("captureDiagnostics"),
    )
  }

  /** Writes an inspection response from an IDE-side adapter. */
  public fun writeResponse(response: EditorInspectionResponse, path: Path) {
    val properties = Properties().apply {
      setProperty("version", VERSION.toString())
      setProperty("kind", "response")
      when (response) {
        is EditorInspectionResponse.Failed -> {
          setProperty("status", "failed")
          setEncoded("reason", response.reason)
        }
        is EditorInspectionResponse.Completed -> {
          setProperty("status", "completed")
          setProperty("diagnosticsAvailable", (response.diagnostics != null).toString())
          val diagnostics = response.diagnostics.orEmpty()
          setProperty("diagnostics", diagnostics.size.toString())
          diagnostics.forEachIndexed { index, diagnostic ->
            setProperty("diagnostic.$index.severity", diagnostic.severity.name)
            setEncoded("diagnostic.$index.message", diagnostic.message)
            diagnostic.location?.let { setEncoded("diagnostic.$index.location", it) }
          }
          setProperty("references", response.references.size.toString())
          response.references.entries.sortedBy { it.key }.forEachIndexed { index, (expression, observation) ->
            setEncoded("reference.$index.expression", expression)
            when (observation) {
              is ReferenceObservation.Resolved -> {
                setProperty("reference.$index.status", "resolved")
                observation.target?.let { setEncoded("reference.$index.detail", it) }
              }
              ReferenceObservation.Unresolved -> setProperty("reference.$index.status", "unresolved")
              is ReferenceObservation.Unavailable -> {
                setProperty("reference.$index.status", "unavailable")
                setEncoded("reference.$index.detail", observation.reason)
              }
            }
          }
        }
      }
    }
    write(properties, path)
  }

  /** Reads an IDE response without converting unavailable work into a successful observation. */
  public fun readResponse(path: Path): EditorInspectionResponse {
    val properties = read(path, "response")
    return when (properties.required("status")) {
      "failed" -> EditorInspectionResponse.Failed(properties.encoded("reason"))
      "completed" -> {
        val diagnostics = if (properties.boolean("diagnosticsAvailable")) {
          List(properties.count("diagnostics")) { index ->
            val severity = try {
              DiagnosticSeverity.valueOf(properties.required("diagnostic.$index.severity"))
            } catch (error: IllegalArgumentException) {
              throw IllegalArgumentException("Unknown diagnostic severity at index $index.", error)
            }
            EditorDiagnostic(
              severity = severity,
              message = properties.encoded("diagnostic.$index.message"),
              location = properties.getProperty("diagnostic.$index.location")?.let(::decode),
            )
          }
        } else {
          require(properties.count("diagnostics") == 0) {
            "An IDE response cannot include diagnostics when diagnostics are unavailable."
          }
          null
        }
        val referenceEntries = List(properties.count("references")) { index ->
          val expression = properties.encoded("reference.$index.expression")
          val observation = when (properties.required("reference.$index.status")) {
            "resolved" -> ReferenceObservation.Resolved(
              properties.getProperty("reference.$index.detail")?.let(::decode),
            )
            "unresolved" -> ReferenceObservation.Unresolved
            "unavailable" -> ReferenceObservation.Unavailable(properties.encoded("reference.$index.detail"))
            else -> throw IllegalArgumentException("Unknown reference status at index $index.")
          }
          expression to observation
        }
        require(referenceEntries.map { it.first }.distinct().size == referenceEntries.size) {
          "An IDE response cannot include a reference more than once."
        }
        val references = referenceEntries.toMap()
        EditorInspectionResponse.Completed(diagnostics, references)
      }
      else -> throw IllegalArgumentException("Unknown editor inspection response status.")
    }
  }

  private fun read(path: Path, expectedKind: String): Properties {
    require(Files.isRegularFile(path)) { "Missing editor inspection $expectedKind: $path" }
    val properties = Properties()
    Files.newBufferedReader(path, StandardCharsets.UTF_8).use(properties::load)
    require(properties.required("version") == VERSION.toString()) {
      "Unsupported editor inspection protocol version '${properties.getProperty("version")}'."
    }
    require(properties.required("kind") == expectedKind) { "Expected an editor inspection $expectedKind." }
    return properties
  }

  private fun write(properties: Properties, path: Path) {
    Files.createDirectories(checkNotNull(path.parent) { "Editor inspection path needs a parent directory." })
    val text = properties.stringPropertyNames().sorted().joinToString(separator = "\n", postfix = "\n") { key ->
      "$key=${properties.getProperty(key)}"
    }
    val temporary = Files.createTempFile(path.parent, ".${path.fileName}", ".tmp")
    try {
      Files.writeString(temporary, text, StandardCharsets.UTF_8)
      try {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun Properties.required(key: String): String =
    getProperty(key) ?: throw IllegalArgumentException("Missing editor inspection property '$key'.")

  private fun Properties.count(key: String): Int = required(key).toIntOrNull()?.also { count ->
    require(count >= 0) { "Editor inspection property '$key' must not be negative." }
  } ?: throw IllegalArgumentException("Editor inspection property '$key' must be an integer.")

  private fun Properties.boolean(key: String): Boolean = when (required(key)) {
    "true" -> true
    "false" -> false
    else -> throw IllegalArgumentException("Editor inspection property '$key' must be true or false.")
  }

  private fun Properties.setEncoded(key: String, value: String) {
    setProperty(key, encode(value))
  }

  private fun Properties.encoded(key: String): String = decode(required(key))

  private fun encode(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

  private fun decode(value: String): String = try {
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
  } catch (error: IllegalArgumentException) {
    throw IllegalArgumentException("Editor inspection property is not valid Base64.", error)
  }
}

/** The outcome returned by an IDE-side adapter. */
public sealed interface EditorInspectionResponse {
  public data class Completed(
    val diagnostics: List<EditorDiagnostic>?,
    val references: Map<String, ReferenceObservation>,
  ) : EditorInspectionResponse

  public data class Failed(val reason: String) : EditorInspectionResponse
}
