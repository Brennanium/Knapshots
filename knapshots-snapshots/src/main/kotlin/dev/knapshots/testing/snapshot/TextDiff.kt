package dev.knapshots.testing.snapshot

private enum class Change { SAME, REMOVED, ADDED }
private data class DiffLine(val change: Change, val text: String)

/** A compact line-oriented diff. The bounded fallback avoids quadratic memory on large inputs. */
internal fun textDiff(expected: String, actual: String): String? {
  if (expected == actual) return null
  val old = expected.split('\n')
  val new = actual.split('\n')
  val lines = diffLines(old, new)
  val changed = lines.indices.filter { lines[it].change != Change.SAME }
  val ranges = mutableListOf<IntRange>()
  for (index in changed) {
    val start = (index - 3).coerceAtLeast(0)
    val end = (index + 3).coerceAtMost(lines.lastIndex)
    val last = ranges.lastOrNull()
    if (last != null && start <= last.last + 1) ranges[ranges.lastIndex] = last.first..maxOf(last.last, end)
    else ranges += start..end
  }
  fun visible(text: String): String {
    val end = text.indexOfLast { it != ' ' && it != '\t' && it != '\r' } + 1
    return text.substring(0, end) + text.substring(end).map {
      when (it) { ' ' -> "·"; '\t' -> "⇥"; '\r' -> "␍"; else -> it.toString() }
    }.joinToString("")
  }
  return buildString {
    append("− expected / + actual")
    var position = 0
    var oldBefore = 0
    var newBefore = 0
    for (range in ranges) {
      while (position < range.first) {
        if (lines[position].change != Change.ADDED) oldBefore++
        if (lines[position].change != Change.REMOVED) newBefore++
        position++
      }
      val chunk = lines.subList(range.first, range.last + 1)
      val oldStart = oldBefore + 1
      val newStart = newBefore + 1
      val oldCount = chunk.count { it.change != Change.ADDED }
      val newCount = chunk.count { it.change != Change.REMOVED }
      append("\n@@ -$oldStart,$oldCount +$newStart,$newCount @@")
      for (line in chunk) {
        append('\n')
        append(when (line.change) { Change.SAME -> "  "; Change.REMOVED -> "− "; Change.ADDED -> "+ " })
        append(visible(line.text))
      }
    }
    if (expected.endsWith('\n') != actual.endsWith('\n')) {
      append(if (expected.endsWith('\n')) "\n\\ No newline at end of actual" else "\n\\ No newline at end of expected")
    }
  }
}

private fun diffLines(old: List<String>, new: List<String>): List<DiffLine> {
  var prefix = 0
  while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
  var suffix = 0
  while (suffix < old.size - prefix && suffix < new.size - prefix && old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
  val removed = old.subList(prefix, old.size - suffix)
  val added = new.subList(prefix, new.size - suffix)
  val middle = if ((removed.size.toLong() + 1) * (added.size.toLong() + 1) > 1_000_000L) {
    removed.map { DiffLine(Change.REMOVED, it) } + added.map { DiffLine(Change.ADDED, it) }
  } else {
    diffMiddle(removed, added)
  }
  return old.take(prefix).map { DiffLine(Change.SAME, it) } + middle +
    old.takeLast(suffix).map { DiffLine(Change.SAME, it) }
}

private fun diffMiddle(old: List<String>, new: List<String>): List<DiffLine> {
  val width = new.size + 1
  val lengths = IntArray((old.size + 1) * width)
  fun length(i: Int, j: Int): Int = lengths[i * width + j]
  for (i in old.indices.reversed()) for (j in new.indices.reversed()) {
    lengths[i * width + j] = if (old[i] == new[j]) length(i + 1, j + 1) + 1
      else maxOf(length(i + 1, j), length(i, j + 1))
  }
  val result = mutableListOf<DiffLine>()
  var i = 0
  var j = 0
  while (i < old.size || j < new.size) {
    when {
      i < old.size && j < new.size && old[i] == new[j] -> { result += DiffLine(Change.SAME, old[i]); i++; j++ }
      i < old.size && (j == new.size || length(i + 1, j) >= length(i, j + 1)) -> { result += DiffLine(Change.REMOVED, old[i]); i++ }
      else -> { result += DiffLine(Change.ADDED, new[j]); j++ }
    }
  }
  return result
}
