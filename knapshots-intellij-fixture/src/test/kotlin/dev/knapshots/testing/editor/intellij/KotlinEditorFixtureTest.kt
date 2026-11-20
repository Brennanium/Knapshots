package dev.knapshots.testing.editor.intellij

import com.intellij.testFramework.PsiTestUtil
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class KotlinEditorFixtureTest : EditorFixtureTestCase() {
  override fun setUp() {
    super.setUp()
    PsiTestUtil.addLibrary(myFixture.module, checkNotNull(System.getProperty("knapshots.fixtureKotlinStdlib")))
    KotlinCommonCompilerArgumentsHolder.getInstance(project).update { pluginClasspaths = emptyArray() }
  }

  fun testResolvesKotlinReferenceFromAdditionalSource() {
    assertEditor(
      fileName = "Use.kt",
      source = "fun read(person: Person) = person.name",
      additionalSources = mapOf("Person.kt" to "data class Person(val name: String)"),
    ) { reference("person.name").resolves() }
  }

  fun testReportsUnresolvedKotlinReference() {
    assertEditor(fileName = "Broken.kt", source = "fun read(person: Person) = person.missing",
      additionalSources = mapOf("Person.kt" to "data class Person(val name: String)")) {
      diagnostics { error("Unresolved reference") }
      reference("person.missing").isUnresolved()
    }
  }
  private fun installFixturePlugin(): AtomicInteger {
    val requests = AtomicInteger()
    val provider = object : KotlinBundledFirCompilerPluginProvider {
      override fun provideBundledPluginJar(project: Project, userSuppliedPluginJar: Path): Path? {
        if (userSuppliedPluginJar.toAbsolutePath().normalize() != Path.of(System.getProperty("knapshots.fixtureBuildPlugin")).toAbsolutePath().normalize()) return null
        requests.incrementAndGet()
        return Path.of(System.getProperty("knapshots.fixtureIdePlugin"))
      }
    }
    ExtensionPointName.create<KotlinBundledFirCompilerPluginProvider>("org.jetbrains.kotlin.bundledFirCompilerPluginProvider")
      .point.registerExtension(provider, testRootDisposable)
    KotlinCommonCompilerArgumentsHolder.getInstance(project).update {
      pluginClasspaths = arrayOf(checkNotNull(System.getProperty("knapshots.fixtureBuildPlugin")))
    }
    return requests
  }

  fun testGeneratedMemberResolvesWithFixtureCompilerPlugin() {
    val requests = installFixturePlugin()
    assertEditor(fileName = "Generated.kt", source = """
      package fixture
      annotation class AddMember
      @AddMember class Example
      val model = Example()
      val value = model.addedMember()
    """) { reference("model.addedMember").resolves() }
    assertTrue("The IDE did not request the fixture compiler plugin", requests.get() > 0)
  }
  fun testGeneratedMemberIsUnresolvedWithoutPlugin() {
    assertEditor(fileName = "WithoutPlugin.kt", source = """
      package fixture
      annotation class AddMember
      @AddMember class Example
      val model = Example()
      val value = model.addedMember()
    """) {
      diagnostics { error("Unresolved reference") }
      reference("model.addedMember").isUnresolved()
    }
  }

  fun testPluginErrorAppearsInEditorDiagnostics() {
    installFixturePlugin()
    assertEditor(fileName = "Rejected.kt", source = """
      package fixture
      annotation class Reject
      @Reject class Disallowed
    """) { diagnostics { error("Fixture rejects Disallowed") } }
  }

  fun testSourceDiagnosticSnapshotUsesPluginHighlight() {
    installFixturePlugin()
    assertEditor(fileName = "Rejected.kt", source = """
      package fixture
      annotation class Reject
      @Reject class Disallowed
    """) {
      sourceDiagnosticsSnapshot("""
        package fixture
        annotation class Reject
        @Reject class Disallowed
                ^
        error: [REJECTED] Fixture rejects Disallowed
      """)
    }
  }

  fun testPluginWarningAppearsInEditorDiagnostics() {
    installFixturePlugin()
    assertEditor(fileName = "Caution.kt", source = """
      package fixture
      annotation class Caution
      @Caution class Cautious
    """) { diagnostics { warning("Fixture warns about Cautious") } }
  }
  fun testGeneratedPropertyResolvesWithFixtureCompilerPlugin() {
    val requests = installFixturePlugin()
    assertEditor(fileName = "Property.kt", source = """
      package fixture
      annotation class AddProperty
      @AddProperty class Example
      val model = Example()
      val value = model.addedProperty
    """) { reference("model.addedProperty").resolves() }
    assertTrue("The IDE did not request the fixture compiler plugin", requests.get() > 0)
  }
}
