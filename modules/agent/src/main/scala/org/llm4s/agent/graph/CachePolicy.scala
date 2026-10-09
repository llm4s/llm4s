package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.FiniteDuration

/**
 * Declares that a node's result depends only on its input, so a repeated input may be answered
 * from memory without running the node.
 *
 * The node must not depend on [[ThreadState]], on the clock, or on an external system: a node that
 * needs part of the state takes that part as input. The key is the node, its input schema version
 * and the encoded input. A hit returns the stored [[Command]] and the node does not run, so a cached
 * node should not rely on `RunContext.emit` or `progress`. Only a `Continue` result is stored: a
 * failure or a suspension is not.
 *
 * The cache belongs to the [[CompiledGraph]] instance and is shared by its runs. It is not
 * checkpointed, so another instance, another process or a restart starts empty and recomputes.
 * Entries beyond `maxEntries` are evicted least-recently-used first. Two tasks with one input
 * running at the same time both run the node.
 *
 * Every value is validated like [[RunBudgets]]: `apply` and the `with*` setters throw
 * `IllegalArgumentException`; [[CachePolicy.of]] returns a `ValidationError`.
 *
 * @param ttl how long an entry is served after it was stored; `None` means until evicted
 * @param maxEntries the most results kept for the node
 */
final case class CachePolicy private (ttl: Option[FiniteDuration], maxEntries: Int):
  def withTtl(t: FiniteDuration): CachePolicy         = CachePolicy(Some(t), maxEntries)
  def withTtl(t: Option[FiniteDuration]): CachePolicy = CachePolicy(t, maxEntries)
  def withMaxEntries(n: Int): CachePolicy             = CachePolicy(ttl, n)

object CachePolicy:
  private def problems(ttl: Option[FiniteDuration], maxEntries: Int): List[String] =
    List(
      ttl.filter(_.length <= 0).map(t => s"ttl must be positive, was $t"),
      Option.when(maxEntries <= 0)(s"maxEntries must be positive, was $maxEntries")
    ).flatten

  /** Throws `IllegalArgumentException` for an invalid value; use [[of]] for untrusted input. */
  def apply(ttl: Option[FiniteDuration] = None, maxEntries: Int = 1024): CachePolicy =
    val found = problems(ttl, maxEntries)
    require(found.isEmpty, found.mkString("; "))
    new CachePolicy(ttl, maxEntries)

  def of(ttl: Option[FiniteDuration] = None, maxEntries: Int = 1024): Result[CachePolicy] =
    problems(ttl, maxEntries) match
      case Nil   => Right(new CachePolicy(ttl, maxEntries))
      case found => Left(ValidationError("cachePolicy", found))

  val default: CachePolicy = apply()
