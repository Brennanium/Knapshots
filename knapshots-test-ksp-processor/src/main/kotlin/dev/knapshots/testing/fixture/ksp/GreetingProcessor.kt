package dev.knapshots.testing.fixture.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

/** Test-only processor: classes annotated with fixture.AddGreeting get a greeting object. */
public class GreetingProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = GreetingProcessor(
    environment.codeGenerator, environment.logger, environment.options["fixture.warning"] == "true",
    environment.options["fixture.secondRound"] == "true",
    environment.options["fixture.resource"] == "true",
  )
}

private class GreetingProcessor(
  private val code: CodeGenerator,
  private val logger: KSPLogger,
  private val warning: Boolean,
  private val secondRound: Boolean,
  private val resource: Boolean,
) : SymbolProcessor {
  private val generated = mutableSetOf<String>()
  override fun process(resolver: Resolver): List<KSAnnotated> {
    val deferred = mutableListOf<KSAnnotated>()
    resolver.getSymbolsWithAnnotation("fixture.AddGreeting").forEach { symbol ->
      if (!symbol.validate(enableNewFeatures = true)) { deferred += symbol; return@forEach }
      val declaration = symbol as? KSClassDeclaration
      if (declaration == null) { logger.error("@AddGreeting requires a class", symbol); return@forEach }
      val name = declaration.simpleName.asString()
      val packageName = declaration.packageName.asString()
      if (!generated.add("$packageName.$name")) return@forEach
      if (warning) logger.warn("Generating greeting for $name", declaration)
      val file = requireNotNull(declaration.containingFile)
      code.createNewFile(Dependencies(false, file), packageName, "${name}Greeting").bufferedWriter().use {
        it.write("package $packageName\n\nobject ${name}Greeting {\n  fun greet(): String = \"Hello, $name!\"\n}\n")
      }
      if (resource) {
        code.createNewFile(Dependencies(false, file), packageName, "${name}Metadata", "txt").bufferedWriter().use {
          it.write("metadata for $name\n")
        }
      }
      if (secondRound && name == "Example") {
        code.createNewFile(Dependencies(false, file), packageName, "FollowUp").bufferedWriter().use {
          it.write("package $packageName\n\n@AddGreeting class FollowUp\n")
        }
      }
    }
    return deferred
  }
}
