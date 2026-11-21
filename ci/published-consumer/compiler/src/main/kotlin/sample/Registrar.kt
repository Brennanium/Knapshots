package sample

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

class Registrar : CompilerPluginRegistrar() {
  override val pluginId: String = "sample.compiler"
  override val supportsK2: Boolean = true
  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    System.setProperty("knapshots.consumer.compilerLoaded", "yes")
  }
}
