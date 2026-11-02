package org.llm4s.security

import org.llm4s.util.{ ExactMatcher, Redaction }
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

/**
 * Every credential llm4s holds, so that diagnostic text can be scrubbed of it by exact match (layer A of
 * the layered redaction design, #1932). A `Secret` registers itself when it is created; a token source
 * registers each token it obtains and forgets the generation before the one it replaced. Scrubbing reads
 * one immutable snapshot, so it takes no lock and is linear in the text.
 */
private[llm4s] object KnownSecrets {
  enum Kind { case Configured, Token, Identifier }

  val MinLength = 8
  val Cap       = 4096

  private val log = LoggerFactory.getLogger(getClass)

  /** One registered value: its kind, and how many registrations hold it (`forget` releases one). */
  final private case class Entry(value: String, kind: Kind, holds: Int)

  /** The matcher over the entries' forms, built on first scan so that a burst of registrations builds it once. */
  final private class Matcher(entries: Vector[Entry]) {
    lazy val built: Option[ExactMatcher] = {
      val forms = entries.iterator
        .map(_.value)
        .flatMap(v => Iterator(v, URLEncoder.encode(v, StandardCharsets.UTF_8), ExactMatcher.jsonEscaped(v)))
        .toSeq
        .distinct
      if (forms.isEmpty) None else Some(ExactMatcher(forms))
    }
  }

  /**
   * Entries ordered from least to most recently registered, plus the matcher over their forms. `overCapWarned` is
   * set once, by the first registration that leaves the registry over [[Cap]], so that the warning is logged once.
   */
  final private case class State(
    entries: Vector[Entry],
    matcher: Matcher,
    refused: Set[String],
    overCapWarned: AtomicBoolean
  )

  private def empty  = State(Vector.empty, new Matcher(Vector.empty), Set.empty, new AtomicBoolean(false))
  private val global = new AtomicReference[State](empty)
  // set only by `isolated`; inherited by threads the test body starts
  private val scoped                        = new InheritableThreadLocal[AtomicReference[State]]
  private def state: AtomicReference[State] = Option(scoped.get()).getOrElse(global)

  def register(value: String, kind: Kind): Unit = Option(value).map(_.trim).foreach { v =>
    val after = state.updateAndGet { s =>
      if (v.length < MinLength) {
        if (!s.refused.contains(v)) log.debug(s"Not registering a credential shorter than $MinLength characters")
        s.copy(refused = s.refused + v)
      } else
        s.entries.indexWhere(_.value == v) match {
          case -1 => rebuild(evict(s.entries :+ Entry(v, kind, 1)), s.refused, s.overCapWarned)
          case i  =>
            // Configured outranks Identifier, which outranks Token: a value that is also a configured key is never
            // evicted or forgotten. A token registered again is in use again, so it moves to the most recent end and
            // is the last to be evicted. The forms are unchanged, so the matcher is kept.
            val e       = s.entries(i)
            val kept    = if (e.kind == Kind.Configured || kind == Kind.Token) e.kind else kind
            val updated = e.copy(kind = kept, holds = e.holds + 1)
            val entries =
              if (updated.kind == Kind.Token) s.entries.patch(i, Nil, 1) :+ updated
              else s.entries.updated(i, updated)
            s.copy(entries = entries)
        }
    }
    if (after.entries.size > Cap && after.overCapWarned.compareAndSet(false, true))
      log.warn(
        s"llm4s holds ${after.entries.size} credentials, more than $Cap: configured keys and identifiers are never " +
          "evicted, so the registry grows with each distinct one (logged once)"
      )
  }

  def registerIdentifier(value: String): Unit = register(value, Kind.Identifier)

  /**
   * Registers, as configured secrets, the value of each header in `headers` with a credential name
   * (`Redaction.isCredentialName`: `Authorization`, `x-api-key`, ...): the whole value and, for a value with an
   * auth scheme (`Bearer <token>`, `Basic <b64>`), the credential after the scheme too, since a provider may echo
   * the token without it.
   */
  def registerHeaders(headers: Iterable[(String, String)]): Unit =
    headers.iterator
      .collect { case (name, value) if value != null && Redaction.isCredentialName(name) => value.trim }
      .foreach { v =>
        register(v, Kind.Configured)
        v.split("\\s+", 2) match {
          case Array(scheme, credential) if scheme.forall(_.isLetter) => register(credential, Kind.Configured)
          case _                                                      => ()
        }
      }

  /**
   * Releases one hold on a token. The value stays registered while another holder has it, and a configured key or
   * identifier is never forgotten.
   */
  def forget(value: String): Unit = Option(value).map(_.trim).foreach { v =>
    state.updateAndGet { s =>
      s.entries.indexWhere(_.value == v) match {
        case -1 => s
        case i =>
          val e = s.entries(i)
          if (e.kind != Kind.Token) s
          else if (e.holds > 1) s.copy(entries = s.entries.updated(i, e.copy(holds = e.holds - 1)))
          else rebuild(s.entries.patch(i, Nil, 1), s.refused, s.overCapWarned)
      }
    }: Unit
  }

  def scrub(text: String, placeholder: String): String =
    if (text == null || text.isEmpty) text
    else state.get().matcher.built.fold(text)(_.replaceAll(text, placeholder))

  private[llm4s] def size: Int            = state.get().entries.size
  private[security] def refusedCount: Int = state.get().refused.size

  /**
   * Runs `body` against an empty registry of its own, seen by the calling thread and the threads it starts, and never
   * touches the process-wide one (tests only). Suites run in parallel, so swapping the shared registry would let one
   * suite's registrations vanish from, or leak into, another's.
   */
  def isolated[A](body: => A): A = {
    val outer = scoped.get()
    scoped.set(new AtomicReference[State](empty))
    scala.util.Using.resource(new AutoCloseable { def close(): Unit = scoped.set(outer) })(_ => body)
  }

  /**
   * Drops the least recently registered tokens until the registry is back at [[Cap]]. The last entry, the one being
   * added, is never dropped, nor are configured keys and identifiers, so a process holding more than [[Cap]] of them
   * keeps them all: the cap bounds the runtime tokens only.
   */
  private def evict(entries: Vector[Entry]): Vector[Entry] =
    if (entries.size <= Cap) entries
    else {
      val overflow = entries.size - Cap
      val newest   = entries.size - 1
      val evictable = entries.zipWithIndex
        .collect { case (e, i) if e.kind == Kind.Token && i != newest => i }
        .take(overflow)
        .toSet
      entries.zipWithIndex.collect { case (e, i) if !evictable(i) => e }
    }

  /** Whether the over-[[Cap]] warning has been logged for the current registry (tests only). */
  private[security] def overCapWarned: Boolean = state.get().overCapWarned.get()

  private def rebuild(entries: Vector[Entry], refused: Set[String], warned: AtomicBoolean): State =
    State(entries, new Matcher(entries), refused, warned)
}
