package org.llm4s.vectorstore

import org.llm4s.types.{ AsyncResult, Result }
import scala.concurrent.{ ExecutionContext, Future }

/**
 * Asynchronous, non-blocking interface for KeywordIndex search capabilities.
 */
trait AsyncKeywordIndex {

  def index(doc: KeywordDocument)(implicit ec: ExecutionContext): AsyncResult[Unit]

  def indexBatch(docs: Seq[KeywordDocument])(implicit ec: ExecutionContext): AsyncResult[Unit]

  def search(
    query: String,
    topK: Int = 10,
    filter: Option[MetadataFilter] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[KeywordSearchResult]]

  def searchWithHighlights(
    query: String,
    topK: Int = 10,
    snippetLength: Int = 100,
    filter: Option[MetadataFilter] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[KeywordSearchResult]]

  def get(id: String)(implicit ec: ExecutionContext): AsyncResult[Option[KeywordDocument]]

  def delete(id: String)(implicit ec: ExecutionContext): AsyncResult[Unit]

  def deleteBatch(ids: Seq[String])(implicit ec: ExecutionContext): AsyncResult[Unit]

  def deleteByPrefix(prefix: String)(implicit ec: ExecutionContext): AsyncResult[Long]

  def update(doc: KeywordDocument)(implicit ec: ExecutionContext): AsyncResult[Unit]

  def count()(implicit ec: ExecutionContext): AsyncResult[Long]

  def clear()(implicit ec: ExecutionContext): AsyncResult[Unit]

  def stats()(implicit ec: ExecutionContext): AsyncResult[KeywordIndexStats]

  def close(): Unit
}

/**
 * Concrete asynchronous wrapper for a synchronous KeywordIndex.
 * Isolates blocking database lookup operations onto an ExecutionContext
 * dedicated to IO.
 */
final class AsyncKeywordIndexWrapper(
  protected val syncStore: KeywordIndex
) extends AsyncKeywordIndex {

  override def index(doc: KeywordDocument)(implicit ec: ExecutionContext): AsyncResult[Unit] =
    Future(syncStore.index(doc))

  override def indexBatch(docs: Seq[KeywordDocument])(implicit ec: ExecutionContext): AsyncResult[Unit] =
    Future(syncStore.indexBatch(docs))

  override def search(
    query: String,
    topK: Int = 10,
    filter: Option[MetadataFilter] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[KeywordSearchResult]] =
    Future(syncStore.search(query, topK, filter))

  override def searchWithHighlights(
    query: String,
    topK: Int = 10,
    snippetLength: Int = 100,
    filter: Option[MetadataFilter] = None
  )(implicit ec: ExecutionContext): AsyncResult[Seq[KeywordSearchResult]] =
    Future(syncStore.searchWithHighlights(query, topK, snippetLength, filter))

  override def get(id: String)(implicit ec: ExecutionContext): AsyncResult[Option[KeywordDocument]] =
    Future(syncStore.get(id))

  override def delete(id: String)(implicit ec: ExecutionContext): AsyncResult[Unit] =
    Future(syncStore.delete(id))

  override def deleteBatch(ids: Seq[String])(implicit ec: ExecutionContext): AsyncResult[Unit] =
    Future(syncStore.deleteBatch(ids))

  override def deleteByPrefix(prefix: String)(implicit ec: ExecutionContext): AsyncResult[Long] =
    Future(syncStore.deleteByPrefix(prefix))

  override def count()(implicit ec: ExecutionContext): AsyncResult[Long] =
    Future(syncStore.count())

  override def clear()(implicit ec: ExecutionContext): AsyncResult[Unit] =
    Future(syncStore.clear())

  override def stats()(implicit ec: ExecutionContext): AsyncResult[KeywordIndexStats] =
    Future(syncStore.stats())

  override def update(doc: KeywordDocument)(implicit ec: ExecutionContext): AsyncResult[Unit] =
    Future {
      for {
        _ <- syncStore.delete(doc.id)
        _ <- syncStore.index(doc)
      } yield ()
    }

  override def close(): Unit = syncStore.close()
}

object AsyncKeywordIndex {
  def apply(syncStore: KeywordIndex): AsyncKeywordIndex =
    new AsyncKeywordIndexWrapper(syncStore)

  def inMemory(): Result[AsyncKeywordIndex] =
    KeywordIndex.inMemory().map(new AsyncKeywordIndexWrapper(_))
}
