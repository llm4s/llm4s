package org.llm4s.javaapi.rag

import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.javaapi.{ FacadeInternals, LlmResult }
import org.llm4s.rag.{ RAG, RAGAnswerResult, RAGSearchResult }
import org.llm4s.types.Result

import java.nio.file.{ Files, Path }
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * Retrieval-augmented generation from Java: ingest documents, then ask questions that a model answers from the chunks
 * of them that match, with those chunks returned as the answer's sources. It is `llm4s-rag`'s pipeline - chunking,
 * embedding, a vector and keyword (hybrid) search and the answer prompt - with no Scala type in sight.
 *
 * {{{
 * try (JRag rag = JRag.builder()
 *         .embeddings(Llm4s.createDefaultEmbeddingClient().get())
 *         .llm(Llm4s.createDefaultClient().get())
 *         .build()
 *         .get()) {
 *     rag.ingest(Path.of("docs")).get();
 *     JRagAnswer answer = rag.ask("How do I configure a provider?").get();
 *     System.out.println(answer.answer());
 *     answer.sources().forEach(s -> System.out.println("  " + s.documentId() + " (" + s.score() + ")"));
 * }
 * }}}
 *
 * Obtain one from [[JRag.builder]]. Every call blocks the calling thread and never throws: a failure - of the
 * embedding provider, the model, a file or an argument - comes back inside the [[org.llm4s.javaapi.LlmResult LlmResult]],
 * whose `getError().getKind()` says which, as for `JLlmClient` and `JEmbeddingClient`. Interrupting the blocked thread
 * returns a failed result of kind `CANCELLED`, with the interrupt flag still set.
 *
 * Different documents may be ingested from several threads at once; ingesting the same document id from two threads
 * at once is not supported. `close()` releases the store - the SQLite connections, or the memory of an in-memory store
 * - and not the clients it was built with, which stay the caller's to close.
 */
final class JRag private[rag] (token: RagFacadeToken, rag: RAG) extends AutoCloseable {

  RagFacadeToken.check(token, "JRag")

  /**
   * Ingests a file, or every file directly in a directory whose name ends in `.txt`, `.md`, `.pdf`, `.docx`, `.json`,
   * `.xml` or `.html`, returning how many chunks were indexed. A file's id is its name: ingesting a file of the same
   * name again replaces what was indexed under it. The same as `ingest(path, Map.of())`.
   */
  def ingest(path: Path): LlmResult[Integer] = ingest(path, java.util.Map.of())

  /**
   * Ingests a file or a directory as `ingest(path)` does, attaching `metadata` to every chunk; each chunk also gets
   * the file's name as `source` and its absolute path as `path`. A `null` argument, a `null` key or value, or a path
   * that does not exist is a failed result of kind `VALIDATION`.
   */
  def ingest(path: Path, metadata: java.util.Map[String, String]): LlmResult[Integer] =
    RagBuilds.guarded("JRag.ingest") {
      for {
        file <- Option(path).toRight(ValidationError.required("path"))
        _ <-
          if (Files.exists(file)) Right(())
          else Left(ValidationError.invalid("path", s"$file does not exist"))
        tags   <- JRag.metadataOf(metadata)
        chunks <- rag.ingestPath(file, tags)
      } yield Integer.valueOf(chunks)
    }

  /**
   * Ingests `text` as the document `documentId`, returning how many chunks were indexed. Ingesting an id again
   * replaces what was indexed under it. The same as `ingestText(text, documentId, Map.of())`.
   */
  def ingestText(text: String, documentId: String): LlmResult[Integer] =
    ingestText(text, documentId, java.util.Map.of())

  /**
   * Ingests `text` as the document `documentId`, attaching `metadata` to every chunk. A `null` argument, a blank id,
   * or a `null` key or value is a failed result of kind `VALIDATION`.
   */
  def ingestText(text: String, documentId: String, metadata: java.util.Map[String, String]): LlmResult[Integer] =
    RagBuilds.guarded("JRag.ingestText") {
      for {
        content <- Option(text).toRight(ValidationError.required("text"))
        id      <- JRag.nonBlank("documentId", documentId)
        tags    <- JRag.metadataOf(metadata)
        chunks  <- rag.ingestText(content, id, tags)
      } yield Integer.valueOf(chunks)
    }

  /**
   * Answers `question` from the chunks that match it best, at most the builder's `topK`: the question is embedded,
   * the chunks found, and the model asked to answer from them alone. The answer lists those chunks as its sources;
   * with nothing indexed it has none, and the model is still asked, so its answer says it cannot tell.
   *
   * A pipeline built without a chat client (`llm`) returns a failed result of kind `CONFIGURATION`; a `null` or blank
   * question one of kind `VALIDATION`. A failure of the embedding provider or the model has the kind of that failure.
   */
  def ask(question: String): LlmResult[JRagAnswer] = asking(question, None)

  /** Answers `question` as `ask(question)` does, from at most `topK` chunks, at least 1, instead of the builder's. */
  def ask(question: String, topK: Int): LlmResult[JRagAnswer] = asking(question, Some(topK))

  private def asking(question: String, topK: Option[Int]): LlmResult[JRagAnswer] =
    RagBuilds.guarded("JRag.ask") {
      for {
        q      <- JRag.nonBlank("question", question)
        k      <- JRag.topKOf(topK)
        _      <- Either.cond(rag.config.llmClient.isDefined, (), JRag.NoChatClient)
        answer <- rag.queryWithAnswer(q, k)
      } yield JRag.answer(answer)
    }

  /**
   * The chunks that match `query` best, at most the builder's `topK`, best first, without asking a model: what `ask`
   * would answer from. Works with or without a chat client. A `null` or blank query is a failed result of kind
   * `VALIDATION`. An unmodifiable list.
   */
  def search(query: String): LlmResult[java.util.List[JRagSource]] = searching(query, None)

  /** The chunks that match `query` best, as `search(query)` returns them, at most `topK`, at least 1. */
  def search(query: String, topK: Int): LlmResult[java.util.List[JRagSource]] = searching(query, Some(topK))

  private def searching(query: String, topK: Option[Int]): LlmResult[java.util.List[JRagSource]] =
    RagBuilds.guarded("JRag.search") {
      for {
        q       <- JRag.nonBlank("query", query)
        k       <- JRag.topKOf(topK)
        results <- rag.query(q, k)
      } yield JRag.sources(results)
    }

  /** How many documents this pipeline has indexed: a document ingested twice counts once. */
  def documentCount: Int = rag.documentCount

  /** How many chunks this pipeline has indexed: a document ingested again replaces its chunks. */
  def chunkCount: Int = rag.chunkCount

  /** Releases the store. The embedding and chat clients the pipeline was built with are not closed. */
  override def close(): Unit = rag.close()

  override def toString: String = s"JRag($documentCount documents, $chunkCount chunks)"
}

object JRag {

  /** A builder for a pipeline: give it an embedding client, and a chat client to `ask`, then `build()` it. */
  def builder(): JRagBuilder = JRagBuilder.EMPTY

  /** A store in memory, the default: fast, and lost when the pipeline is closed. */
  def inMemory(): JRagStore = JRagStore.inMemory()

  /**
   * A store in SQLite files that outlive the pipeline: the vectors in `file`, and the keyword index beside it, in
   * the same name with `.db` replaced by `-fts.db` (`docs.db` and `docs-fts.db`). A file that does not exist is
   * created. A `null` file makes `build()` a failed result of kind `VALIDATION`.
   */
  def sqlite(file: Path): JRagStore = JRagStore.sqlite(file)

  private val NoChatClient: ConfigurationError =
    ConfigurationError(
      "JRag.ask needs a chat client to answer with: build the pipeline with JRag.builder().llm(client). " +
        "search(query) works without one."
    )

  private def nonBlank(field: String, value: String): Result[String] =
    if (value == null) Left(ValidationError.required(field))
    else if (value.trim.isEmpty) Left(ValidationError.invalid(field, "must not be blank"))
    else Right(value)

  private def topKOf(topK: Option[Int]): Result[Option[Int]] =
    topK match {
      case Some(k) if k < 1 => Left(ValidationError.invalid("topK", s"must be at least 1, got $k"))
      case other            => Right(other)
    }

  /** `metadata` copied, so a map another thread changes cannot make the check and the chunks disagree. */
  private def metadataOf(metadata: java.util.Map[String, String]): Result[Map[String, String]] =
    if (metadata == null) Left(ValidationError.required("metadata"))
    else {
      val entries = metadata.entrySet.asScala.toVector.map(e => (e.getKey, e.getValue))
      entries
        .collectFirst {
          case (null, _) => ValidationError.invalid("metadata", "must not have a null key")
          case (k, null) => ValidationError.invalid(s"metadata[$k]", "must not be null")
        }
        .toLeft(entries.toMap)
    }

  private def sources(results: Seq[RAGSearchResult]): java.util.List[JRagSource] =
    java.util.List.copyOf(results.map(source).asJava)

  private def source(result: RAGSearchResult): JRagSource =
    new JRagSource(
      result.id,
      result.metadata.getOrElse("docId", ""),
      result.content,
      result.score,
      java.util.Map.copyOf(result.metadata.asJava)
    )

  private def answer(result: RAGAnswerResult): JRagAnswer =
    new JRagAnswer(
      Option(result.answer).getOrElse(""),
      result.question,
      sources(result.contexts),
      result.usage.map(FacadeInternals.tokenUsage).toJava
    )
}
