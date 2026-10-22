package org.llm4s.javaapi.rag

import org.llm4s.chunking.ChunkerFactory
import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.config.Llm4sConfig
import org.llm4s.javaapi.{ FacadeInternals, JEmbeddingClient, JLlmClient, LlmResult }
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.rag.{ RAG, RAGConfig }
import org.llm4s.types.Result

import scala.jdk.OptionConverters.*

/**
 * Not part of the API: what [[JRagBuilder.build]] does with what it was told, and the guard every [[JRag]] call runs
 * in. The pipeline is `llm4s-rag`'s [[org.llm4s.rag.RAG RAG]], built from its default [[org.llm4s.rag.RAGConfig
 * RAGConfig]] with the builder's settings applied, embedding through the caller's `JEmbeddingClient` itself
 * ([[JEmbeddingProvider]]), so a question is embedded exactly as `JEmbeddingClient.embed` would embed it.
 *
 * Every check of the caller's values is a [[org.llm4s.error.ValidationError ValidationError]] naming the builder's
 * field, so a Java caller reads one kind, `VALIDATION`, for anything it set wrong or left out.
 */
private[rag] object RagBuilds {

  def build(
    embeddings: JEmbeddingClient,
    llm: JLlmClient,
    store: JRagStore,
    chunkSize: java.lang.Integer,
    chunkOverlap: java.lang.Integer,
    topK: java.lang.Integer,
    systemPrompt: String
  ): LlmResult[JRag] =
    guarded("JRag.build") {
      for {
        client <- Option(embeddings).toRight(ValidationError.required("embeddings"))
        where  <- storeOf(store)
        chunks <- chunking(Option(chunkSize).map(_.intValue), Option(chunkOverlap).map(_.intValue))
        k      <- Option(topK).map(_.intValue).fold(Right(None))(k => positive("topK", k).map(Some(_)))
        config = settings(chunks, k, Option(llm), Option(systemPrompt), where)
        registry <- Llm4sConfig.modelRegistryService()
        provider = new JEmbeddingProvider(client)
        rag <- RAG.buildWithEmbeddings(config, new EmbeddingClient(provider)(using registry), provider.model)
      } yield new JRag(RagFacadeToken.INSTANCE, rag)
    }

  /** `call`, whose exceptions and interrupts become failed results, as Java callers read them. */
  def guarded[A](operation: String)(call: => Result[A]): LlmResult[A] =
    FacadeInternals.result(CancelledError.attempt(operation)(Safety.safely(call).flatMap(identity)))

  /** The SQLite file, if the store is one; a SQLite store needs a file. */
  private def storeOf(store: JRagStore): Result[Option[String]] =
    if (store == null) Left(ValidationError.required("store"))
    else if (store.isInMemory) Right(None)
    else
      store.file.toScala
        .map(_.toString)
        .filter(_.trim.nonEmpty)
        .map(Some(_))
        .toRight(ValidationError.required("store file"))

  private def chunking(size: Option[Int], overlap: Option[Int]): Result[Option[(Int, Int)]] =
    (size, overlap) match {
      case (Some(s), Some(o)) =>
        for {
          s <- positive("chunk size", s)
          o <-
            if (o < 0 || o >= s) Left(ValidationError.invalid("chunk overlap", s"must be at least 0 and less than $s"))
            else Right(o)
        } yield Some((s, o))
      case _ => Right(None)
    }

  private def positive(field: String, value: Int): Result[Int] =
    if (value >= 1) Right(value) else Left(ValidationError.invalid(field, s"must be at least 1, got $value"))

  private def settings(
    chunks: Option[(Int, Int)],
    topK: Option[Int],
    llm: Option[JLlmClient],
    systemPrompt: Option[String],
    sqlite: Option[String]
  ): RAGConfig = {
    val steps: Seq[RAGConfig => RAGConfig] = Seq(
      c => chunks.fold(c)((s, o) => c.withChunking(ChunkerFactory.Strategy.Sentence, s, o)),
      c => topK.fold(c)(c.withTopK),
      c => llm.fold(c)(l => c.withLLM(l.underlying)),
      c => systemPrompt.fold(c)(c.withSystemPrompt),
      c => sqlite.fold(c)(c.withSQLite)
    )
    steps.foldLeft(RAGConfig.default)((config, step) => step(config))
  }
}
