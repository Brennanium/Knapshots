package dev.knapshots.testing.ide

import dev.knapshots.testing.editor.assertEditor
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/** Exercises KPaths as an external compiler-plugin consumer of the generic editor host. */
@Tag("ide-integration")
@EnabledIfSystemProperty(named = "knapshots.kpathsCheckout", matches = ".+")
class KPathsEditorIntegrationTest {
  @Test fun `generated KPaths resolve in an imported editor project`() {
    val mode = System.getProperty("knapshots.kpathsMode", "enabled")
    assertEditor(
      source = """
        import dev.kpaths.annotations.Pathable
        @Pathable data class Person(var name: String)
        @Pathable sealed interface Event { data class Message(val text: String) : Event }
        val namePath = Person.Paths.name
        val messagePath = Event.Paths.Message.text
      """ + if (mode == "diagnostic") "\nval invalid: Int = \"wrong\"\n" else "",
      awaitReferences = if (mode == "disabled") emptySet() else setOf("Person.Paths.name", "Event.Paths.Message.text"),
    ) {
      when (mode) {
        "disabled" -> {
          diagnostics { error("Unresolved reference"); error("Unresolved reference") }
          reference("Person.Paths.name").isUnresolved()
          reference("Event.Paths.Message.text").isUnresolved()
        }
        else -> {
          if (mode == "diagnostic") diagnostics { error("Int") }
          reference("Person.Paths.name").resolves()
          reference("Event.Paths.Message.text").resolves()
        }
      }
    }
  }
}
