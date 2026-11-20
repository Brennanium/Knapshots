package dev.knapshots.testing.compiler

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Rewrites only identified assertion arguments, and refuses ambiguous or unsupported shapes. */
@Suppress("OPT_IN_USAGE_ERROR")
@OptIn(CompilerConfiguration.Internals::class, ExperimentalCompilerApi::class)
internal object InlineCompilerSnapshots {
  private val lock = Any()
  private data class Rewrite(val compiledLine: Int, val addedLines: Int)
  private data class SourceHistory(
    var current: String,
    val versions: MutableSet<String>,
    val rewrites: MutableList<Rewrite>,
  )
  private val histories = mutableMapOf<Path, SourceHistory>()

  fun write(
    file: Path,
    line: Int,
    values: Map<CompilerSnapshotKind, String>,
    version: String,
    versioned: Boolean,
    expectedSource: String? = null,
    compiledSourceLine: Boolean = false,
    assertionName: String = "assertCompiler",
    argumentNames: Map<CompilerSnapshotKind, String> = mapOf(CompilerSnapshotKind.FIR to "fir", CompilerSnapshotKind.IR to "ir", CompilerSnapshotKind.BYTECODE to "bytecode"),
  ) = synchronized(lock) {
    require(values.isNotEmpty())
    val original = Files.readString(file)
    val key = file.toAbsolutePath().normalize()
    val history = if (compiledSourceLine) histories[key] else null
    check(history == null || history.current == original) { "Source changed outside snapshot recording: $file." }
    check(expectedSource == null || original == expectedSource || history?.versions?.contains(expectedSource) == true) {
      "Source changed while compilation was running: $file."
    }
    check(history?.rewrites?.none { it.compiledLine == line } != false) {
      "This assertion has already been recorded in this test run: $file:$line."
    }
    val currentLine = line + history?.rewrites.orEmpty().filter { it.compiledLine < line }.sumOf { it.addedLines }
    // The embedded parser expects an IDE home even outside an IDE process.
    if (System.getProperty("idea.home.path") == null) {
      System.setProperty("idea.home.path", Path.of(System.getProperty("user.dir")).toAbsolutePath().toString())
    }
    val disposable = Disposer.newDisposable()
    try {
      val configuration = CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() }
      val environment = KotlinCoreEnvironment.createForTests(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
      val parsed = KtPsiFactory(environment.project).createFile(original)
      fun lineOf(offset: Int) = original.take(offset).count { it == '\n' } + 1
      val matchingCalls = PsiTreeUtil.collectElementsOfType(parsed, KtCallExpression::class.java).filter {
        it.calleeExpression?.text == assertionName &&
          currentLine in lineOf(it.textRange.startOffset)..lineOf(it.textRange.endOffset)
      }
      // A generic phase assertion can contain snapshot() as its argument. Select the outer
      // call while still rejecting unrelated calls that happen to share a line.
      val candidates = matchingCalls.filter { candidate -> matchingCalls.none { other ->
        other !== candidate && PsiTreeUtil.isAncestor(other, candidate, true)
      } }
      require(candidates.size == 1) { "Expected exactly one $assertionName call at $file:$line, found ${candidates.size}." }
      val call = candidates.single()
      val args = call.valueArgumentList ?: error("assertCompiler has no argument list at $file:$line.")
      val edits = mutableListOf<Triple<Int, Int, String>>()
      val additions = mutableListOf<Pair<String, String>>()
      val start = original.lastIndexOf('\n', call.textRange.startOffset - 1) + 1
      val indentation = original.substring(start, call.textRange.startOffset).takeWhile { it == ' ' || it == '\t' }
      fun literal(value: String): String {
        val clean = value.trimEnd('\n', '\r')
        // Kotlin raw strings still interpolate dollars. Regular escaped strings safely handle the
        // rare case where the compiler output itself contains a triple-quote delimiter.
        if ("\"\"\"" in clean) return "\"" + clean.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r") + "\""
        val content = clean.replace("$", "${'$'}{'${'$'}'}")
        val inner = indentation + "    "
        return "\"\"\"\n" + content.lines().joinToString("\n") { if (it.isEmpty()) "" else inner + it } + "\n" + inner + "\"\"\""
      }
      fun setExpression(expression: org.jetbrains.kotlin.psi.KtExpression, value: String) {
        require(expression is KtStringTemplateExpression) { "Snapshot must be a literal string, got ${expression.text}." }
        edits += Triple(expression.textRange.startOffset, expression.textRange.endOffset, literal(value))
      }
      val implicitBlocks = values.entries.filter { argumentNames[it.key] in setOf("__generatedBlock", "__firBlock", "__irBlock", "__diagnosticsBlock") }
      if (implicitBlocks.isNotEmpty()) {
        require(!versioned) { "Record shared snapshots before version-specific output." }
        val order = listOf("__generatedBlock", "__firBlock", "__irBlock", "__diagnosticsBlock")
        val ordered = implicitBlocks.sortedBy { order.indexOf(argumentNames[it.key]) }
        val statements = ordered.map { entry ->
          when (argumentNames[entry.key]) {
            "__generatedBlock" -> "generated(${literal(entry.value)})"
            "__firBlock" -> "fir(${literal(entry.value)})"
            "__irBlock" -> "ir(${literal(entry.value)})"
            else -> "diagnostics { snapshot(${literal(entry.value)}) }"
          }
        }
        val lambda = call.lambdaArguments.singleOrNull()?.getLambdaExpression()
        if (lambda == null) {
          require(call.lambdaArguments.isEmpty()) { "Ambiguous assertion lambda." }
          edits += Triple(call.textRange.endOffset, call.textRange.endOffset, " { " + statements.joinToString("; ") + " }")
        } else {
          val close = lambda.textRange.endOffset - 1
          require(original[close] == '}') { "Assertion block has no closing brace." }
          edits += Triple(close, close, "\n" + statements.joinToString("\n") { "${indentation}  $it" } + "\n$indentation")
        }
      }
      if (assertionName == "diagnostics") {
        require(!versioned && values.size == 1 && args.arguments.size <= 1 && call.lambdaArguments.isEmpty()) {
          "Record a shared diagnostics() snapshot before version-specific output."
        }
        val argument = args.arguments.singleOrNull()
        require(argument == null || argument.getArgumentName()?.asName?.asString() in listOf(null, "location")) {
          "Unsupported diagnostics() argument."
        }
        val location = argument?.getArgumentExpression()?.text?.let { ", location = $it" } ?: ""
        val value = values.values.single()
        edits += Triple(call.textRange.startOffset, call.textRange.endOffset, "diagnostics { snapshot(${literal(value)}$location) }")
      } else for ((kind, value) in values) {
        val name = argumentNames[kind] ?: error("Unsupported inline snapshot kind: ${kind.name}.")
        if (name in setOf("__generatedBlock", "__firBlock", "__irBlock", "__diagnosticsBlock")) continue
        val named = args.arguments.filter { it.getArgumentName()?.asName?.asString() == name }
        val positional = args.arguments.filter { it.getArgumentName() == null }
        val special = assertionName in setOf("sourceDiagnosticsSnapshot", "snapshot", "generated", "fir", "ir", "bytecode") && name == "expected"
        val matches = if (named.isNotEmpty() || !special) named else {
          val first = positional.firstOrNull()?.getArgumentExpression()
          val second = positional.getOrNull(1)?.getArgumentExpression()
          val firstIsExpectation = first is KtStringTemplateExpression ||
            (first is KtCallExpression && first.calleeExpression?.text in setOf("snapshot", "SnapshotExpectation"))
          val secondIsExpectation = second is KtStringTemplateExpression ||
            (second is KtCallExpression && second.calleeExpression?.text in setOf("snapshot", "SnapshotExpectation"))
          val index = if (assertionName == "snapshot" && !firstIsExpectation && secondIsExpectation) 1 else 0
          listOfNotNull(positional.getOrNull(index))
        }
        require(matches.size <= 1) { "More than one $name argument at $file:$line." }
        val argument = matches.singleOrNull()
        if (argument == null) {
          require(!versioned) { "Record the shared $name expectation before a version-specific one." }
          if (assertionName in setOf("sourceDiagnosticsSnapshot", "snapshot", "generated", "fir", "ir", "bytecode") && name == "expected") {
            val first = args.arguments.firstOrNull()
            val insert = first?.textRange?.startOffset ?: args.rightParenthesis?.textRange?.startOffset ?: error("Missing closing parenthesis.")
            val replacement = if (first == null) literal(value) else "expected = ${literal(value)}, "
            edits += Triple(insert, insert, replacement)
          } else additions += name to value
          continue
        }
        val rawExpression = argument.getArgumentExpression()
        if (assertionName in setOf("sourceDiagnosticsSnapshot", "snapshot", "generated", "fir", "ir", "bytecode") && name == "expected" && rawExpression is KtStringTemplateExpression) {
          require(!versioned) { "Version-specific recording requires a SnapshotExpectation." }
          setExpression(rawExpression, value)
          continue
        }
        val expression = rawExpression as? KtCallExpression
          ?: error("$name must be snapshot() or SnapshotExpectation(...).")
        if (expression.calleeExpression?.text == "snapshot" && expression.valueArguments.isEmpty()) {
          require(!versioned) { "Record the shared $name expectation before a version-specific one." }
          edits += Triple(expression.textRange.startOffset, expression.textRange.endOffset, "SnapshotExpectation(${literal(value)})")
          continue
        }
        require(expression.calleeExpression?.text == "SnapshotExpectation") { "Unsupported $name expectation expression: ${expression.text}" }
        val expectationArgs = expression.valueArguments
        val default = expectationArgs.firstOrNull { it.getArgumentName()?.asName?.asString() == "default" }
          ?: expectationArgs.firstOrNull { it.getArgumentName() == null }
          ?: error("$name expectation has no default literal.")
        val versionArg = expectationArgs.firstOrNull { it.getArgumentName()?.asName?.asString() == "versions" }
          ?: expectationArgs.filter { it.getArgumentName() == null }.getOrNull(1)
        val versionMap = versionArg?.getArgumentExpression() as? KtCallExpression
        if (versionArg != null) require(versionMap?.calleeExpression?.text == "mapOf") { "Version overrides must use mapOf with literal keys." }
        val entries = versionMap?.valueArguments?.map {
          val pair = it.getArgumentExpression() as? KtBinaryExpression ?: error("Version overrides must be literal pairs.")
          val key = pair.left as? KtStringTemplateExpression ?: error("Version selectors must be literal strings.")
          require(pair.operationReference.text == "to" && key.text.matches(Regex("\"[^\"${'$'}\\\\]*\""))) {
            "Version overrides must use literal selectors."
          }
          key.text.removeSurrounding("\"") to pair
        }.orEmpty()
        require(entries.map { it.first }.toSet().size == entries.size) { "Duplicate version selector in $name expectation." }
        val selector = if (versioned) version.takeIf { candidate -> entries.any { it.first == candidate } }
          else selectCompilerVersion(entries.map { it.first }.toSet(), version)
        val entry = entries.firstOrNull { it.first == selector }?.second
        when {
          entry != null -> setExpression(entry.right ?: error("Missing version expectation."), value)
          versioned && versionMap != null -> {
            val list = versionMap.valueArgumentList ?: error("Invalid version map.")
            val insert = list.rightParenthesis?.textRange?.startOffset ?: error("Invalid version map.")
            val separator = if (list.arguments.isEmpty()) "" else if (list.text.trimEnd().dropLast(1).trimEnd().endsWith(",")) " " else ", "
            edits += Triple(insert, insert, "$separator\"$version\" to ${literal(value)}")
          }
          versioned -> {
            val list = expression.valueArgumentList ?: error("Invalid expectation.")
            val insert = list.rightParenthesis?.textRange?.startOffset ?: error("Invalid expectation.")
            val separator = if (list.text.trimEnd().dropLast(1).trimEnd().endsWith(",")) " " else ", "
            edits += Triple(insert, insert, "${separator}versions = mapOf(\"$version\" to ${literal(value)})")
          }
          else -> setExpression(default.getArgumentExpression() ?: error("Missing default expectation."), value)
        }
      }
      if (additions.isNotEmpty()) {
        val close = args.rightParenthesis?.textRange?.startOffset ?: error("assertCompiler has no closing parenthesis.")
        val beforeClose = original.substring(args.textRange.startOffset, close)
        val separator = if (beforeClose.trimEnd().endsWith(",")) "" else ","
        val additionText = additions.joinToString("") { (name, value) -> "${indentation}  $name = SnapshotExpectation(${literal(value)}),\n" }
        val lineStart = original.lastIndexOf('\n', close - 1) + 1
        require(original.substring(lineStart, close).isBlank()) { "The closing parenthesis must be on its own line." }
        edits += Triple(lineStart, lineStart, additionText)
        if (separator.isNotEmpty()) {
          val lastArg = args.arguments.lastOrNull() ?: error("assertCompiler needs source and directory arguments.")
          edits += Triple(lastArg.textRange.endOffset, lastArg.textRange.endOffset, separator)
        }
      }
      var updated = original
      edits.sortedByDescending { it.first }.forEach { (start, end, replacement) ->
        updated = updated.replaceRange(start, end, replacement)
      }
      // Don't overwrite edits made by the developer while compilation was in progress.
      check(Files.readString(file) == original) { "Source changed while recording: $file." }
      val temporary = Files.createTempFile(file.parent, ".knapshots-snapshot-", ".tmp")
      try {
        Files.writeString(temporary, updated)
        val attributes = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
        if (attributes != null) Files.setPosixFilePermissions(temporary, attributes.readAttributes().permissions())
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        if (compiledSourceLine) {
          val state = history ?: SourceHistory(original, mutableSetOf(original), mutableListOf()).also { histories[key] = it }
          state.current = updated
          state.versions.add(updated)
          state.rewrites.add(Rewrite(line, updated.count { it == '\n' } - original.count { it == '\n' }))
        }
      } finally {
        Files.deleteIfExists(temporary)
      }
    } finally {
      val application = ApplicationManager.getApplication()
      if (application == null) Disposer.dispose(disposable)
      else application.runWriteAction { Disposer.dispose(disposable) }
    }
  }

  /** Stage all rewrites in a copy, then atomically replace the real source only on success. */
  fun writeBatch(updates: List<InlineSnapshotUpdate>, version: String, versioned: Boolean) = synchronized(lock) {
    val file = updates.first().location.file.toAbsolutePath().normalize()
    val original = Files.readString(file)
    val previous = histories[file]
    check(previous == null || previous.current == original) { "Source changed outside snapshot recording: $file." }
    val stage = Files.createTempFile(file.parent, ".knapshots-batch-", ".kt")
    val stageKey = stage.toAbsolutePath().normalize()
    try {
      Files.copy(file, stage, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
      if (previous != null) histories[stageKey] = SourceHistory(previous.current, previous.versions.toMutableSet(), previous.rewrites.toMutableList())
      for (update in updates) {
        val captures = update.values.mapKeys { CompilerSnapshotKind(it.key) }
        write(stage, update.location.line, captures, version, versioned, update.expectedSource,
          compiledSourceLine = true, assertionName = update.assertionName,
          argumentNames = captures.keys.associateWith { it.name })
      }
      check(Files.readString(file) == original) { "Source changed while recording: $file." }
      Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      histories[file] = checkNotNull(histories[stageKey])
    } finally {
      histories.remove(stageKey)
      Files.deleteIfExists(stage)
    }
  }

}
