package dev.knapshots.gradle

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault

/** Copies the report before validating it, so failed and skipped scenarios remain inspectable. */
@DisableCachingByDefault(because = "Collects the result of a fresh external test invocation")
public abstract class CollectEditorScenarioTask : DefaultTask() {
  init { outputs.upToDateWhen { false } }

  @get:Input public abstract val scenario: Property<String>
  @get:InputFile public abstract val junitXml: RegularFileProperty
  @get:OutputDirectory public abstract val outputDirectory: DirectoryProperty

  @Option(option = "scenario", description = "Name of the editor test scenario")
  public fun scenario(value: String) { scenario.set(value) }

  @TaskAction public fun collect() {
    val name = scenario.orNull ?: throw GradleException("Specify --scenario.")
    require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && name != "." && name != "..") {
      "Invalid editor scenario name: $name"
    }
    val source = junitXml.get().asFile.toPath()
    val destination = outputDirectory.get().asFile.toPath().resolve(name).resolve("junit.xml")
    Files.createDirectories(destination.parent)
    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
    val factory = DocumentBuilderFactory.newInstance().apply {
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val root = Files.newInputStream(destination).use { factory.newDocumentBuilder().parse(it).documentElement }
    require(root.tagName == "testsuite") { "Expected a JUnit testsuite report for scenario $name." }
    fun count(attribute: String): Int = root.getAttribute(attribute).toIntOrNull()
      ?: throw GradleException("Invalid or missing JUnit '$attribute' count for scenario $name.")
    val tests = count("tests")
    val skipped = count("skipped")
    val failures = count("failures")
    val errors = count("errors")
    if (tests <= 0 || skipped != 0 || failures != 0 || errors != 0) {
      throw GradleException("Editor scenario '$name' did not pass without skips (tests=$tests, skipped=$skipped, failures=$failures, errors=$errors). Report: $destination")
    }
    logger.lifecycle("Editor scenario '$name' passed ($tests tests). Report: $destination")
  }
}
