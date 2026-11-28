package org.llm4s.agent.harness.memory

import org.llm4s.agent.graph.{ AppliedKeys, EffectKey, FencingToken }
import org.llm4s.agent.memory.{ AppliedWrite, MemoryBounds, MemoryFilter, MemoryId, MemoryStore, Memory }
import org.llm4s.agent.memory.TransactionalMemoryStore
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.util.concurrent.locks.ReentrantLock
import scala.util.Using

/**
 * One tenant's memory: the tenant's current store, and the lock every write to it takes, so one tenant's writes are
 * serialised (design §4.31, "Tenant scope"). It is the `Transactional` target's `AppliedKeys` for C4's guard: its
 * `forget` is the store's `forgetApplied`, under the same lock and fenced by the same token.
 */
final private[memory] class TenantMemory(
  val initial: MemoryStore,
  val identity: Option[String] = None,
  val progress: ConsolidationProgress = new ConsolidationProgress
) extends AppliedKeys:
  private val lock                        = new ReentrantLock()
  @volatile private var held: MemoryStore = initial

  // Guarded by the middleware's resolving lock: how many calls hold this cell, and whether it was evicted or closed.
  private[memory] var users: Int       = 0
  private[memory] var retired: Boolean = false

  /** Whether a consolidation is due, and where its page starts: the tenant's, kept by the middleware past eviction. */
  def consolidationDue(now: java.time.Instant, interval: scala.concurrent.duration.FiniteDuration): Boolean =
    progress.due(now, interval)

  private[memory] def consolidationCursor: Option[org.llm4s.agent.memory.ConsolidationCursor] = progress.cursor
  private[memory] def consolidationCursor_=(c: Option[org.llm4s.agent.memory.ConsolidationCursor]): Unit =
    progress.cursor = c

  /**
   * `f` on the store as the last write left it, under the lock: a store need not be thread-safe
   * (`SQLiteMemoryStore` is one connection), so no read runs while another thread of the tenant writes.
   */
  def read[A](f: MemoryStore => A): A = locked(f(held))

  /** Whether the store records applied writes, so its writes replay as `Transactional`. */
  def transactional: Boolean = held.isInstanceOf[TransactionalMemoryStore]

  /** `f` on the transactional store under the lock; the store it returns becomes the current one. */
  def write[A](f: TransactionalMemoryStore => Result[(TransactionalMemoryStore, A)]): Result[A] =
    locked {
      held match
        case store: TransactionalMemoryStore =>
          f(store).map { (next, value) =>
            held = next
            value
          }
        case other =>
          Left(ValidationError("semantic memory", s"${other.getClass.getSimpleName} is not a TransactionalMemoryStore"))
    }

  /**
   * For a store that is not transactional: stores `add`, deletes `remove`, then deletes the overflow beyond `bounds`
   * among the memories it counts, under the lock. Serialised in this process, but not one transaction.
   */
  def writePlain(add: Seq[Memory], remove: Seq[MemoryId], bounds: MemoryBounds): Result[AppliedWrite] =
    locked {
      // Only what the write touches and what the bounds count is read (#3040), never the whole store
      val counted = bounds.owner.fold[MemoryFilter](MemoryFilter.All)(o => MemoryFilter.ByMetadata(o.key, o.value))
      for
        present <- remove.distinct.foldLeft[Result[Vector[MemoryId]]](Right(Vector.empty))((acc, id) =>
          acc.flatMap(found => held.exists(id).map(there => if there then found :+ id else found))
        )
        // as `applyAtomically`: a memory both added and removed is kept, written as added
        replaced <- held.replace(add, remove.filterNot(add.map(_.id).toSet))
        all      <- replaced.recall(counted, Int.MaxValue)
        evicted = bounds.overflow(all.filter(m => bounds.counts(m.metadata)).map(MemoryBounds.Entry.of))
        trimmed <- evicted.foldLeft[Result[MemoryStore]](Right(replaced))((acc, id) => acc.flatMap(_.delete(id)))
      yield
        held = trimmed
        val added = add.map(_.id).distinct.toVector
        AppliedWrite(added, present.filter(id => !added.contains(id)), evicted.size)
    }

  override def forget(key: EffectKey, token: FencingToken): Result[Unit] =
    if transactional then write(_.forgetApplied(MemoryEffects.keyOf(key), token.value).map(_ -> ()))
    else Right(())

  /** How many applied keys the store holds; `0` for a store that is not transactional. */
  def appliedCount: Result[Int] = read {
    case store: TransactionalMemoryStore => store.appliedWriteCount
    case _                               => Right(0)
  }

  private def locked[A](body: => A): A = Using.resource(new TenantMemory.Held(lock))(_ => body)

private[memory] object TenantMemory:

  /** Holds `lock` from construction until `close`. */
  final private class Held(lock: ReentrantLock) extends AutoCloseable:
    lock.lock()
    def close(): Unit = lock.unlock()

/**
 * A tenant's consolidation schedule and scan position (#3040): when its last consolidation began, and where the next
 * page starts. Small, and kept by the middleware apart from the tenant's store, so neither is lost when the store is
 * evicted and opened again.
 */
final private[memory] class ConsolidationProgress:
  @volatile private var last: Option[java.time.Instant]                                    = None
  @volatile private[memory] var cursor: Option[org.llm4s.agent.memory.ConsolidationCursor] = None

  /** True, and the time recorded, if no consolidation began within `interval` of `now`. */
  def due(now: java.time.Instant, interval: scala.concurrent.duration.FiniteDuration): Boolean = synchronized {
    val isDue = last.forall(l => !now.isBefore(l.plusNanos(interval.toNanos)))
    if isDue then last = Some(now)
    isDue
  }
