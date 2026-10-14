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
final private[memory] class TenantMemory(val initial: MemoryStore) extends AppliedKeys:
  private val lock                        = new ReentrantLock()
  @volatile private var held: MemoryStore = initial

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
   * For a store that is not transactional: stores `add`, deletes `remove`, then deletes the overflow beyond `bounds`,
   * under the lock. Serialised in this process, but not one transaction.
   */
  def writePlain(add: Seq[Memory], remove: Seq[MemoryId], bounds: MemoryBounds): Result[AppliedWrite] =
    locked {
      for
        present <- held.recall(MemoryFilter.All, Int.MaxValue)
        ids = present.map(_.id).toSet
        replaced <- held.replace(add, remove)
        all      <- replaced.recall(MemoryFilter.All, Int.MaxValue)
        evicted = bounds.overflow(all.map(MemoryBounds.Entry.of))
        trimmed <- evicted.foldLeft[Result[MemoryStore]](Right(replaced))((acc, id) => acc.flatMap(_.delete(id)))
      yield
        held = trimmed
        val added = add.map(_.id).distinct.toVector
        AppliedWrite(
          added,
          remove.distinct.filter(id => ids.contains(id) && !added.contains(id)).toVector,
          evicted.size
        )
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
