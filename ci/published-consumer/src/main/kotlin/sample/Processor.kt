package sample

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

class ProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
    private var generated = false
    override fun process(resolver: Resolver): List<KSAnnotated> {
      if (!generated) {
        generated = true
        environment.codeGenerator.createNewFile(Dependencies.ALL_FILES, "fixture", "Generated")
          .bufferedWriter().use { it.write("package fixture\nobject Generated\n") }
      }
      return emptyList()
    }
  }
}
