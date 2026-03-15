package org.llm4s.vectorstore

import org.llm4s.types.{ AsyncResult, Result }
import scala.concurrent.{ ExecutionContext, Future }

/**
 * Asynchronous wrapper for `PgVectorStore`.
 *
 * This implementation shifts all synchronous, thread-blocking JDBC network I/O
 * (from `PgVectorStore`) onto the user-provided `ExecutionContext`. In a production
 * environment, this ExecutionContext should be backed by a bounded blocking
 * thread pool (e.g., `ExecutionContext.fromExecutor(Executors.newFixedThreadPool(...))`),
 * preventing standard compute thread starvation during high-concurrency RAG operations.
 */
final class AsyncPgVectorStore(
  private val syncStore: VectorStore
) extends AsyncVectorStore {

  override def upsert(record: VectorRecord)(implicit ec: ExecutionContext): AsyncResult[Unit] =
    Future(syncStore.upsert(record))

  override def upsertBatch(records: Seq[VectorRecord])(implicit ec: ExecutionContext): AsyncResult[Unit] =
    Future(syncStore.upsertBatch(records))

  override def search(
    queryVector: Array[Float],
    topK: Int = 10,
    filter: Option[MetadataFilter] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[ScoredRecord]] =
    Future(syncStore.search(queryVector, topK, filter))

  override def get(id: String)(implicit ec: ExecutionContext): AsyncResult[Option[VectorRecord]] =
    Future(syncStore.get(id))

  override def getBatch(ids: Seq[String])(implicit ec: ExecutionContext): AsyncResult[Seq[VectorRecord]] =
    Future(syncStore.getBatch(ids))

  override def delete(id: String)(implicit ec: ExecutionContext): AsyncResult[Unit] =
    Future(syncStore.delete(id))

  override def deleteBatch(ids: Seq[String])(implicit ec: ExecutionContext): AsyncResult[Unit] =
    Future(syncStore.deleteBatch(ids))

  override def deleteByPrefix(prefix: String)(implicit ec: ExecutionContext): AsyncResult[Long] =
    Future(syncStore.deleteByPrefix(prefix))

  override def deleteByFilter(filter: MetadataFilter)(implicit ec: ExecutionContext): AsyncResult[Long] =
    Future(syncStore.deleteByFilter(filter))

  override def count(filter: Option[MetadataFilter] = None)(implicit ec: ExecutionContext): AsyncResult[Long] =
    Future(syncStore.count(filter))

  override def list(
    limit: Int = 100,
    offset: Int = 0,
    filter: Option[MetadataFilter] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[VectorRecord]] =
    Future(syncStore.list(limit, offset, filter))

  override def clear()(implicit ec: ExecutionContext): AsyncResult[Unit] =
    Future(syncStore.clear())

  override def stats()(implicit ec: ExecutionContext): AsyncResult[VectorStoreStats] =
    Future(syncStore.stats())

  override def close(): Unit = syncStore.close()
}

object AsyncPgVectorStore {

  /**
   * Create an AsyncPgVectorStore from a synchronous configuration.
   *
   * @param config The store configuration
   * @return The async vector store or error
   */
  def apply(config: PgVectorStore.Config): Result[AsyncPgVectorStore] =
    PgVectorStore(config).map(new AsyncPgVectorStore(_))

  /**
   * Create an AsyncPgVectorStore wrapping an existing VectorStore instance.
   */
  def apply(syncStore: VectorStore): AsyncPgVectorStore =
    new AsyncPgVectorStore(syncStore)
}
