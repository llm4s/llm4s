package org.llm4s.agent.graph.tool

import org.llm4s.annotation.Experimental

import scala.annotation.tailrec
import scala.collection.immutable.Queue

/**
 * A path pattern, matched against a whole target string: a tool name, a `/`-separated path, or a URL.
 *
 *  - `*` matches any run of characters other than `/`, so it stays within one segment;
 *  - `?` matches one character other than `/`;
 *  - `**` matches any run of characters, `/` included; as a whole segment it also matches zero
 *    segments, so `src/&#42;&#42;/a.scala` matches `src/a.scala`, and `src/&#42;&#42;` matches `src`.
 *
 * Every other character is literal: there are no escapes, character classes or braces, so every
 * string is a valid pattern. Two globs are equal when their patterns are.
 *
 * Besides matching one target, two globs compare as sets of targets: [[overlaps]] (some target matches
 * both) and [[covers]] (every target the other matches, this one matches). A permission check uses them
 * for a resource whose target is itself a pattern, an envelope of the targets a call may touch.
 * Matching and both comparisons run on one automaton built from the pattern, so they cannot disagree.
 */
@Experimental
final class Glob private (val pattern: String):
  // built on first use, never at construction: a caller can bound an untrusted pattern's length
  // (FileBackend's glob and grep do) before any work proportional to it is done
  private lazy val nfa: Glob.Nfa = Glob.Nfa.of(pattern)

  /** Whether `target` matches this pattern as a whole. */
  def matches(target: String): Boolean =
    val end = target.foldLeft(nfa.closure(Set(nfa.start)))((states, c) => nfa.step(states, c))
    end.contains(nfa.accept)

  /** Whether some target matches both this pattern and `other`. */
  def overlaps(other: Glob): Boolean = Glob.search(this, other)((a, b) => a && b)

  /** Whether every target `other` matches, this pattern matches too. */
  def covers(other: Glob): Boolean = !Glob.search(other, this)((inOther, inThis) => inOther && !inThis)

  override def equals(other: Any): Boolean = other match
    case g: Glob => g.pattern == pattern
    case _       => false
  override def hashCode: Int    = pattern.hashCode
  override def toString: String = s"Glob($pattern)"

object Glob:
  def apply(pattern: String): Glob = new Glob(pattern)

  /** What one transition reads: a literal character, any character but `/`, or any character. */
  private enum Cls:
    case Lit(c: Char)
    case NonSlash
    case AnyChar

    def accepts(c: Char): Boolean = this match
      case Lit(l)   => l == c
      case NonSlash => c != '/'
      case AnyChar  => true

  /** A pattern as a nondeterministic automaton, state 0 the start: `edges` read a character, `eps` none. */
  final private case class Nfa(start: Int, accept: Int, edges: Vector[(Int, Cls, Int)], eps: Vector[(Int, Int)]):
    private val epsFrom   = eps.groupMap(_._1)(_._2)
    private val edgesFrom = edges.groupBy(_._1)

    def closure(states: Set[Int]): Set[Int] =
      @tailrec def grow(found: Set[Int], frontier: List[Int]): Set[Int] = frontier match
        case Nil => found
        case s :: rest =>
          val next = epsFrom.getOrElse(s, Vector.empty).filterNot(found)
          grow(found ++ next, next.toList ++ rest)
      grow(states, states.toList)

    def step(states: Set[Int], c: Char): Set[Int] =
      closure(states.flatMap(s => edgesFrom.getOrElse(s, Vector.empty).collect { case (_, k, t) if k.accepts(c) => t }))

    /** The literal characters the pattern names: with `/` and one other, they stand for every character. */
    def literals: Set[Char] = edges.collect { case (_, Cls.Lit(c), _) => c }.toSet

  private object Nfa:
    def of(pattern: String): Nfa =
      val n                    = pattern.length
      def segmentStart(i: Int) = i == 0 || pattern.charAt(i - 1) == '/'
      // states are allocated as the pattern is read; `at` is the state reached so far
      @tailrec def build(i: Int, at: Int, next: Int, edges: Vector[(Int, Cls, Int)], eps: Vector[(Int, Int)]): Nfa =
        if i >= n then Nfa(0, at, edges, eps)
        // a trailing "/**": the segment before it, or anything beneath it - nothing, or '/' then anything
        else if pattern.startsWith("/**", i) && i + 3 == n then
          val (below, end) = (next, next + 1)
          build(
            n,
            end,
            next + 2,
            edges :+ (at, Cls.Lit('/'), below) :+ (below, Cls.AnyChar, below),
            eps :+ (at, end) :+ (below, end)
          )
        // a whole "**" segment followed by more: zero or more segments - nothing, or anything then '/'
        else if pattern.startsWith("**/", i) && segmentStart(i) then
          val (any, end) = (next, next + 1)
          build(
            i + 3,
            end,
            next + 2,
            edges :+ (any, Cls.AnyChar, any) :+ (any, Cls.Lit('/'), end),
            eps :+ (at, end) :+ (at, any)
          )
        else if pattern.startsWith("**", i) then
          build(i + 2, next, next + 1, edges :+ (at, Cls.AnyChar, at), eps :+ (at, next))
        else
          pattern.charAt(i) match
            case '*' => build(i + 1, next, next + 1, edges :+ (at, Cls.NonSlash, at), eps :+ (at, next))
            case '?' => build(i + 1, next, next + 1, edges :+ (at, Cls.NonSlash, next), eps)
            case c   => build(i + 1, next, next + 1, edges :+ (at, Cls.Lit(c), next), eps)
      build(0, 0, 1, Vector.empty, Vector.empty)

  /**
   * Whether some string brings `a` and `b` to a pair of state sets for which `found(aAccepts, bAccepts)`
   * holds: a search over the pair of subset constructions, reading the patterns' literal characters, `/`,
   * and one character neither names - which stands for every other, since no transition tells them apart.
   */
  private def search(a: Glob, b: Glob)(found: (Boolean, Boolean) => Boolean): Boolean =
    val named    = a.nfa.literals ++ b.nfa.literals + '/'
    val other    = Iterator.from('a'.toInt).map(_.toChar).find(c => !named(c)).getOrElse('\u0000')
    val alphabet = (named + other).toVector
    val start    = (a.nfa.closure(Set(a.nfa.start)), b.nfa.closure(Set(b.nfa.start)))
    @tailrec def explore(seen: Set[(Set[Int], Set[Int])], frontier: Queue[(Set[Int], Set[Int])]): Boolean =
      frontier.dequeueOption match
        case None => false
        case Some(((sa, sb), rest)) =>
          if found(sa.contains(a.nfa.accept), sb.contains(b.nfa.accept)) then true
          else
            // a dead side stays dead; a pair where `a` is dead can no longer reach a match of `a`
            val next = alphabet
              .map(c => (a.nfa.step(sa, c), b.nfa.step(sb, c)))
              .filter((na, _) => na.nonEmpty)
              .filterNot(seen)
              .distinct
            explore(seen ++ next, rest.enqueueAll(next))
    explore(Set(start), Queue(start))
