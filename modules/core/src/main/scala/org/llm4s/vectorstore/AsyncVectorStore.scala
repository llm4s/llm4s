package org.llm4s.vectorstore

import org.llm4s.types.AsyncResult
import scala.concurrent.ExecutionContext

/**
 * Asynchronous, non-blocking vector storage abstraction for high-throughput RAG.
 *
 * This mitigates thread-exhaustion vulnerabilities found in the synchronous
 * VectorStore by wrapping operations in AsyncResult (Futures) executed on
 * a dedicated blocking context.
 */
trait AsyncVectorStore {

  def upsert(record: VectorRecord)(implicit ec: ExecutionContext): AsyncResult[Unit]

  def upsertBatch(records: Seq[VectorRecord])(implicit ec: ExecutionContext): AsyncResult[Unit]

  def search(
    queryVector: Array[Float],
    topK: Int = 10,
    filter: Option[MetadataFilter] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[ScoredRecord]]

  def get(id: String)(implicit ec: ExecutionContext): AsyncResult[Option[VectorRecord]]

  def getBatch(ids: Seq[String])(implicit ec: ExecutionContext): AsyncResult[Seq[VectorRecord]]

  def delete(id: String)(implicit ec: ExecutionContext): AsyncResult[Unit]

  def deleteBatch(ids: Seq[String])(implicit ec: ExecutionContext): AsyncResult[Unit]

  def deleteByPrefix(prefix: String)(implicit ec: ExecutionContext): AsyncResult[Long]

  def deleteByFilter(filter: MetadataFilter)(implicit ec: ExecutionContext): AsyncResult[Long]

  def count(filter: Option[MetadataFilter] = None)(implicit ec: ExecutionContext): AsyncResult[Long]

  def list(
    limit: Int = 100,
    offset: Int = 0,
    filter: Option[MetadataFilter] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[VectorRecord]]

  def clear()(implicit ec: ExecutionContext): AsyncResult[Unit]

  def stats()(implicit ec: ExecutionContext): AsyncResult[VectorStoreStats]

  def close(): Unit
}

object AsyncVectorStore {

  /**
   * Create an AsyncVectorStore wrapping an existing VectorStore instance.
   * This uses AsyncPgVectorStore internally as the default async wrapper implementation.
   */
  def apply(syncStore: VectorStore): AsyncVectorStore =
    AsyncPgVectorStore(syncStore)
}
