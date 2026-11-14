package org.llm4s.util

/**
 * An Aho-Corasick automaton over the reversed `patterns`. Scanning the input backwards gives, for every
 * position, the length of the longest pattern that starts there, in time linear in the input; a forward
 * pass then replaces each maximal run of overlapping occurrences, leftmost first, with the placeholder.
 * Transitions live in one `LongMap` keyed by `(state, char)` rather than a map per state.
 */
final private[llm4s] class ExactMatcher private (
  transitions: scala.collection.mutable.LongMap[Int],
  fail: Array[Int],
  longestTerminal: Array[Int]
) {
  private def key(state: Int, c: Char): Long = (state.toLong << 16) | c.toLong

  private def step(from: Int, c: Char): Int = {
    var state = from
    while (state != 0 && !transitions.contains(key(state, c))) state = fail(state)
    transitions.getOrElse(key(state, c), 0)
  }

  def replaceAll(input: String, placeholder: String): String = {
    val n = input.length
    // longestAt(i): the length of the longest pattern that starts at i, or 0.
    val longestAt = new Array[Int](n)
    var state     = 0
    var j         = n - 1
    while (j >= 0) {
      state = step(state, input.charAt(j))
      longestAt(j) = longestTerminal(state)
      j -= 1
    }
    val out = new java.lang.StringBuilder(n)
    var i   = 0
    while (i < n)
      if (longestAt(i) == 0) {
        out.append(input.charAt(i))
        i += 1
      } else {
        // Extend the run over every occurrence that starts inside it, then mask it once.
        var end = i + longestAt(i)
        var k   = i + 1
        while (k < end) {
          if (longestAt(k) > 0) end = math.max(end, k + longestAt(k))
          k += 1
        }
        out.append(placeholder)
        i = end
      }
    out.toString
  }
}

private[llm4s] object ExactMatcher {
  def apply(patterns: Seq[String]): ExactMatcher = {
    val transitions                    = scala.collection.mutable.LongMap.empty[Int]
    val parent                         = scala.collection.mutable.ArrayBuffer(0)
    val viaChar                        = scala.collection.mutable.ArrayBuffer('\u0000')
    val depth                          = scala.collection.mutable.ArrayBuffer(0)
    val terminal                       = scala.collection.mutable.ArrayBuffer(false)
    def key(state: Int, c: Char): Long = (state.toLong << 16) | c.toLong

    patterns.foreach { pattern =>
      var state = 0
      var p     = pattern.length - 1
      while (p >= 0) {
        val c = pattern.charAt(p)
        state = transitions.getOrElseUpdate(
          key(state, c), {
            parent += state; viaChar += c; depth += depth(state) + 1; terminal += false
            parent.length - 1
          }
        )
        p -= 1
      }
      terminal(state) = true
    }

    val size = parent.length
    // Breadth-first order: every state after its parent and after any shallower state its failure link can be.
    val order           = (0 until size).sortBy(depth(_)).toArray
    val fail            = new Array[Int](size)
    val longestTerminal = new Array[Int](size)
    order.foreach { s =>
      if (s != 0) {
        val p = parent(s)
        val c = viaChar(s)
        if (p != 0) {
          var f = fail(p)
          while (f != 0 && !transitions.contains(key(f, c))) f = fail(f)
          fail(s) = transitions.getOrElse(key(f, c), 0)
        }
        // A state's string is a suffix (in the reversed text, a prefix) of its own; its failure chain's first
        // terminal is the longest pattern ending here.
        longestTerminal(s) = if (terminal(s)) depth(s) else longestTerminal(fail(s))
      }
    }
    new ExactMatcher(transitions, fail, longestTerminal)
  }

  private[llm4s] def jsonEscaped(value: String): String = {
    val rendered = ujson.Str(value).render()
    rendered.substring(1, rendered.length - 1)
  }
}
