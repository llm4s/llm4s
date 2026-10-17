package org.llm4s.agent.harness.memory

import org.llm4s.agent.memory.{ EntityId, Memory, MemoryFilter, MemoryId, MemoryStore, ScoredMemory }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * The store a manager is given (design §4.31, "The manager's view of the store"). Reads go to the tenant's current
 * store, each under the tenant's lock, so none runs while another thread of the tenant writes. In [[MemoryView.Mode.Record]], `store` and `storeAll` are buffered, for the middleware to apply as one
 * effect; in [[MemoryView.Mode.Consolidate]], each `replace` is performed at once, as one effect. Every other write is
 * refused, so a manager cannot change the store behind the effects.
 */
final private[memory] class MemoryView private (
  cell: TenantMemory,
  mode: MemoryView.Mode,
  val buffered: Vector[Memory]
) extends MemoryStore:
  import MemoryView.Mode

  override def store(memory: Memory): Result[MemoryStore] = storeAll(Seq(memory))

  override def storeAll(memories: Seq[Memory]): Result[MemoryStore] = mode match
    case Mode.Record => Right(new MemoryView(cell, mode, buffered ++ memories))
    case _           => refused("store")

  override def replace(add: Seq[Memory], remove: Seq[MemoryId]): Result[MemoryStore] = mode match
    case Mode.Consolidate(perform) => perform(add, remove).map(_ => this)
    case _                         => refused("replace")

  override def get(id: MemoryId): Result[Option[Memory]] = cell.read(_.get(id))

  override def recall(filter: MemoryFilter, limit: Int): Result[Seq[Memory]] = cell.read(_.recall(filter, limit))

  override def search(query: String, topK: Int, filter: MemoryFilter): Result[Seq[ScoredMemory]] =
    cell.read(_.search(query, topK, filter))

  override def count(filter: MemoryFilter): Result[Long] = cell.read(_.count(filter))

  override def recent(limit: Int, filter: MemoryFilter): Result[Seq[Memory]] = cell.read(_.recent(limit, filter))

  override def getEntityMemories(entityId: EntityId): Result[Seq[Memory]] = cell.read(_.getEntityMemories(entityId))

  override def getConversation(conversationId: String, limit: Int): Result[Seq[Memory]] =
    cell.read(_.getConversation(conversationId, limit))

  override def exists(id: MemoryId): Result[Boolean] = cell.read(_.exists(id))

  override def important(threshold: Double, limit: Int): Result[Seq[Memory]] = cell.read(_.important(threshold, limit))

  override def delete(id: MemoryId): Result[MemoryStore]                           = refused("delete")
  override def deleteMatching(filter: MemoryFilter): Result[MemoryStore]           = refused("deleteMatching")
  override def update(id: MemoryId, update: Memory => Memory): Result[MemoryStore] = refused("update")
  override def clear(): Result[MemoryStore]                                        = refused("clear")

  private def refused(operation: String): Result[MemoryStore] =
    Left(
      ValidationError(
        "semantic memory",
        s"a memory manager may not $operation here: SemanticMemoryMiddleware writes only by recording and replace"
      )
    )

private[memory] object MemoryView:

  enum Mode:
    case Read
    case Record
    case Consolidate(perform: (Seq[Memory], Seq[MemoryId]) => Result[Unit])

  def reading(cell: TenantMemory): MemoryView   = new MemoryView(cell, Mode.Read, Vector.empty)
  def recording(cell: TenantMemory): MemoryView = new MemoryView(cell, Mode.Record, Vector.empty)
  def consolidating(cell: TenantMemory)(perform: (Seq[Memory], Seq[MemoryId]) => Result[Unit]): MemoryView =
    new MemoryView(cell, Mode.Consolidate(perform), Vector.empty)
