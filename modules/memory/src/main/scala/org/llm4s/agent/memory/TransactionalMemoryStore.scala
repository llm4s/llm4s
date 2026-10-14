package org.llm4s.agent.memory

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ ProcessingError, ValidationError }
import org.llm4s.types.Result

import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.util.Try

/**
 * The identity of one applied write in a [[TransactionalMemoryStore]]: `value` is the key, `scope` the fencing scope
 * it belongs to (one per agent thread). `llm4s-agent-memory` builds it from an agent `EffectKey`; this module knows no
 * agent type.
 */
@Experimental
final case class AppliedWriteKey(scope: String, value: String)

/**
 * What one applied write did: the ids it stored, the ids it deleted (only those that were there), and how many
 * memories it evicted to keep the store within its [[MemoryBounds]]. Bounded by the write itself, never by the size
 * of the store: evictions are a count.
 */
@Experimental
final case class AppliedWrite(added: Vector[MemoryId], removed: Vector[MemoryId], evicted: Int)

@Experimental
object AppliedWrite {

  /** The JSON format version [[toJson]] writes; [[fromJson]] refuses a newer one. */
  val Format: Int = 1

  def toJson(write: AppliedWrite): ujson.Value =
    ujson.Obj(
      "format"  -> Format,
      "added"   -> ujson.Arr.from(write.added.map(id => ujson.Str(id.value))),
      "removed" -> ujson.Arr.from(write.removed.map(id => ujson.Str(id.value))),
      "evicted" -> write.evicted
    )

  def fromJson(json: ujson.Value): Result[AppliedWrite] =
    Try {
      val format = json("format").num.toInt
      if (format > Format) Left(ValidationError("applied-write", s"format $format is newer than $Format"))
      else
        Right(
          AppliedWrite(
            json("added").arr.map(v => MemoryId(v.str)).toVector,
            json("removed").arr.map(v => MemoryId(v.str)).toVector,
            json("evicted").num.toInt
          )
        )
    }.toEither.left
      .map(e => ProcessingError("applied-write", s"unreadable applied write: ${e.getMessage}"))
      .flatten
}

/**
 * How much one store may hold: at most `maxMemories` memories and `maxBytes` bytes, measured by [[MemoryBounds.sizeOf]].
 * A [[TransactionalMemoryStore]] enforces both in the transaction that adds, by evicting.
 */
@Experimental
final case class MemoryBounds private (maxMemories: Int, maxBytes: Long) {
  def withMaxMemories(n: Int): MemoryBounds = MemoryBounds(n, maxBytes)
  def withMaxBytes(n: Long): MemoryBounds   = MemoryBounds(maxMemories, n)

  /**
   * The memories to evict from `entries` - (id, importance, timestamp, size) - so that the rest are within both
   * bounds: lowest importance first (a memory with none counts as 0.5), then oldest, then by id.
   */
  def overflow(entries: Seq[MemoryBounds.Entry]): Vector[MemoryId] = {
    val ordered = entries.sortBy(e => (e.importance.getOrElse(0.5), e.timestamp, e.id.value)).toVector
    @scala.annotation.tailrec
    def evict(i: Int, count: Long, bytes: Long): Int =
      if (i < ordered.size && (count > maxMemories || bytes > maxBytes))
        evict(i + 1, count - 1, bytes - ordered(i).size)
      else i
    ordered.take(evict(0, entries.size.toLong, entries.iterator.map(_.size).sum)).map(_.id)
  }
}

@Experimental
object MemoryBounds {

  /** The default bounds: 1000 memories, 4 MiB. */
  def apply(maxMemories: Int = 1000, maxBytes: Long = 4L * 1024 * 1024): MemoryBounds = {
    require(maxMemories >= 1, s"MemoryBounds: maxMemories must be at least 1, was $maxMemories")
    require(maxBytes >= 1, s"MemoryBounds: maxBytes must be at least 1, was $maxBytes")
    new MemoryBounds(maxMemories, maxBytes)
  }

  /** One stored memory, as [[MemoryBounds.overflow]] ranks it. */
  final case class Entry(id: MemoryId, importance: Option[Double], timestamp: Instant, size: Long)

  object Entry {
    def of(memory: Memory): Entry = Entry(memory.id, memory.importance, memory.timestamp, sizeOf(memory))
  }

  /** The size a memory counts for: the UTF-8 bytes of its content and of its metadata keys and values. */
  def sizeOf(memory: Memory): Long = sizeOf(memory.content, memory.metadata)

  /** [[sizeOf]] from the parts a store reads back. */
  def sizeOf(content: String, metadata: Map[String, String]): Long =
    utf8(content) + metadata.iterator.map { case (k, v) => utf8(k) + utf8(v) }.sum

  private def utf8(s: String): Long = s.getBytes(StandardCharsets.UTF_8).length.toLong
}

/**
 * A [[MemoryStore]] that applies a write and records it under a key in one transaction, so that a write run again
 * after a crash is not applied twice (the `Transactional` replay class of the agent runtime, design §4.24 and §4.31).
 *
 * Every method is one transaction, fenced by `token`: the store keeps, for each key `scope`, the newest token any
 * operation on that scope has carried. An operation with an older token is refused with a `Left` and changes nothing;
 * otherwise its token becomes the scope's newest and it goes ahead. So a writer that has lost its claim can neither
 * apply, nor read back, nor forget anything once its successor has touched the store.
 *
 * Applied keys are removed only by [[forgetApplied]]; `clear()` keeps them, and the fencing tokens.
 *
 * Each method returns the store to use next: [[InMemoryStore]] is immutable, so its applied keys and tokens are part
 * of its value. The SQL stores return themselves.
 */
@Experimental
trait TransactionalMemoryStore extends MemoryStore {

  /**
   * In one transaction: for a `key` already applied, its recorded outcome, writing nothing - even if the memories it
   * stored have since been deleted. Otherwise stores `add`, deletes `remove`, then evicts beyond `bounds`
   * ([[MemoryBounds.overflow]], over the whole store), and records `(key, outcome)`.
   */
  def applyAtomically(
    key: AppliedWriteKey,
    token: Long,
    add: Seq[Memory],
    remove: Seq[MemoryId],
    bounds: MemoryBounds
  ): Result[(TransactionalMemoryStore, AppliedWrite)]

  /** The outcome recorded for `key`, if it was applied; fenced like every operation. */
  def appliedWrite(key: AppliedWriteKey, token: Long): Result[(TransactionalMemoryStore, Option[AppliedWrite])]

  /** Removes `key`'s record; fenced like every operation. A key that is not there is `Right`. */
  def forgetApplied(key: AppliedWriteKey, token: Long): Result[TransactionalMemoryStore]

  /** How many applied keys the store holds. */
  def appliedWriteCount: Result[Int]
}

private[memory] object TransactionalMemoryStore {

  /** The refusal of a token older than the scope's newest. */
  def stale(scope: String, token: Long, newest: Long): ProcessingError =
    ProcessingError("memory-fence", s"token $token is older than the newest token $newest seen for scope $scope")

  /** `add` with a repeated id kept as its last occurrence, in first-seen order. */
  def distinctById(add: Seq[Memory]): Vector[Memory] = {
    val last = add.map(m => m.id -> m).toMap
    add.map(_.id).distinct.map(last).toVector
  }
}
