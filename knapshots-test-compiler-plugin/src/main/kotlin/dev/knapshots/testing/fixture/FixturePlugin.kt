package dev.knapshots.testing.fixture

import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.DeclarationGenerationContext
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.predicate.LookupPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createConeType
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

private const val ID = "dev.knapshots.fixture"
private val FIR = CompilerConfigurationKey.create<String>("fixture.fir")
private val IR = CompilerConfigurationKey.create<String>("fixture.ir")
private val DIAGNOSTIC = CompilerConfigurationKey.create<String>("fixture.diagnostic")

/** Internal test fixture, not a user-facing compiler plugin. */
public class FixtureOptions : CommandLineProcessor {
  override val pluginId: String = ID
  override val pluginOptions: Collection<CliOption> = listOf("fir", "ir", "diagnostic").map {
    CliOption(it, "path", "Capture fixture compiler output", required = false, allowMultipleOccurrences = false)
  }
  override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
    when (option.optionName) { "fir" -> configuration.put(FIR, value); "ir" -> configuration.put(IR, value); "diagnostic" -> configuration.put(DIAGNOSTIC, value); else -> error("Unknown fixture option") }
  }
}

@Suppress("OPT_IN_USAGE_ERROR")
public class FixtureRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String = ID
  override val supportsK2: Boolean = true
  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    FirExtensionRegistrarAdapter.registerExtension(FixtureFirRegistrar(configuration.get(FIR)))
    configuration.get(DIAGNOSTIC)?.let { message ->
      configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)?.report(CompilerMessageSeverity.WARNING, message)
    }
    IrGenerationExtension.registerExtension(FixtureIrExtension(configuration.get(IR)))
  }
}

private class FixtureFirRegistrar(private val destination: String?) : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +::FixtureDeclarations
    destination?.let { path -> +{ session: FirSession -> FixtureFirCheckers(session, path) } }
  }
}

private object FixtureDeclarationKey : GeneratedDeclarationKey()
private val generatedName = Name.identifier("addedMember")
private val addMember = LookupPredicate.create { annotated(FqName("fixture.AddMember")) }

/** Adds a predictable source-visible member to classes marked with @AddMember. */
private class FixtureDeclarations(session: FirSession) : FirDeclarationGenerationExtension(session) {
  override fun FirDeclarationPredicateRegistrar.registerPredicates() { register(addMember) }

  override fun getCallableNamesForClass(classSymbol: FirClassSymbol<*>, context: DeclarationGenerationContext.Member): Set<Name> =
    if (session.predicateBasedProvider.matches(addMember, classSymbol)) setOf(generatedName) else emptySet()

  override fun generateFunctions(callableId: CallableId, context: DeclarationGenerationContext.Member?): List<FirNamedFunctionSymbol> {
    val owner = context?.owner ?: return emptyList()
    if (!session.predicateBasedProvider.matches(addMember, owner) || callableId.callableName != generatedName) return emptyList()
    val type = ClassId.topLevel(FqName("kotlin.String")).createConeType(session)
    return listOf(createMemberFunction(owner, FixtureDeclarationKey, generatedName, type) { withGeneratedDefaultBody() }.symbol)
  }
}

private class FixtureFirCheckers(session: FirSession, private val destination: String) : FirAdditionalCheckersExtension(session) {
  private val names = sortedSetOf<String>()
  init { write() }
  override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
    override val regularClassCheckers: Set<FirDeclarationChecker<FirRegularClass>> = setOf(object : FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {
      context(context: CheckerContext, reporter: DiagnosticReporter)
      override fun check(declaration: FirRegularClass) {
        names += declaration.symbol.classId.asSingleFqName().asString()
        write()
      }
    })
  }
  private fun write() { Files.writeString(Path.of(destination), names.joinToString("\n", prefix = "FIR classes\n", postfix = "\n")) }
}

private class FixtureIrExtension(private val destination: String?) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    var replacements = 0
    var generated = 0
    moduleFragment.transformChildren(object : IrElementTransformerVoid() {
      override fun visitSimpleFunction(declaration: IrSimpleFunction): org.jetbrains.kotlin.ir.IrStatement {
        val origin = declaration.origin as? IrDeclarationOrigin.GeneratedByPlugin
        if (origin?.pluginKey == FixtureDeclarationKey) {
          generated++
          val builder = DeclarationIrBuilder(pluginContext, declaration.symbol)
          declaration.body = builder.irBlockBody { +builder.irReturn(builder.irString("generated by fixture")) }
        }
        return super.visitSimpleFunction(declaration)
      }
      override fun visitConst(expression: IrConst): IrConst {
        if (expression.value == "knapshots:input") { expression.value = "knapshots:output"; replacements++ }
        return expression
      }
    }, null)
    destination?.let { Files.writeString(Path.of(it), "IR replacements: $replacements\n" + if (generated > 0) "IR generated members: $generated\n" else "") }
  }
}
