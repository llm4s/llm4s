package org.llm4s.reranker

import org.llm4s.error.CancelledError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.utils.CodePointBoundary
import org.llm4s.llmconnect.model.{ Conversation, SystemMessage, UserMessage }
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.util.BoundedJson
import ujson._

import scala.util.Try

/**
 * LLM-based reranker using a language model to score documents.
 *
 * Uses a structured prompt to ask the LLM to rate document relevance
 * on a scale of 0 to 1. This is slower and more expensive than
 * cross-encoder rerankers (like Cohere) but works with any LLM.
 *
 * Usage:
 * {{{
 * val llmClient = /* build LLMClient with your ProviderConfig */
 * val reranker  = LLMReranker(llmClient)
 *
 * val request = RerankRequest(
 *   query = "What is Scala?",
 *   documents = Seq("Scala is a programming language", "Python is popular"),
 *   topK = Some(5)
 * )
 *
 * val response = reranker.rerank(request)
 * }}}
 *
 * @param client LLM client for generating scores
 * @param batchSize Number of documents to score per LLM call
 * @param systemPrompt Custom system prompt (optional)
 */
class LLMReranker(
  client: LLMClient,
  batchSize: Int = 10,
  systemPrompt: Option[String] = None
) extends Reranker {

  require(batchSize > 0, "batchSize must be positive")

  private val defaultSystemPrompt: String =
    """You are a relevance scoring assistant. Your task is to rate how relevant each document is to a given query.

For each document, output a relevance score between 0.0 and 1.0 where:
- 1.0 = Highly relevant, directly answers or addresses the query
- 0.7-0.9 = Relevant, contains useful information about the query
- 0.4-0.6 = Somewhat relevant, tangentially related
- 0.1-0.3 = Slightly relevant, mentions related concepts
- 0.0 = Not relevant at all

You MUST respond with ONLY a JSON array of numbers in the same order as the documents.
Example response for 3 documents: [0.95, 0.3, 0.72]

Do not include any explanation or text outside the JSON array."""

  override def rerank(request: RerankRequest): Result[RerankResponse] =
    if (request.documents.isEmpty) {
      Right(RerankResponse(results = Seq.empty))
    } else {
      CancelledError.attempt("llm.rerank") {
        scoreAll(request).map { allScores =>
          // Build results with scores
          val results = allScores
            .map { case (idx, score) =>
              RerankResult(
                index = idx,
                score = score,
                document = if (request.returnDocuments) request.documents(idx) else ""
              )
            }
            .sortBy(-_.score)

          // Apply topK filter
          val finalResults = request.topK match {
            case Some(k) => results.take(k)
            case None    => results
          }

          RerankResponse(
            results = finalResults,
            metadata = Map("provider" -> "llm", "batch_size" -> batchSize.toString)
          )
        }
      }
    }

  /**
   * Scores the documents in batches. A batch the model fails on gets neutral scores, so one bad
   * answer does not lose the ranking - but a cancellation is not a bad answer: it ends the call at
   * once and is returned as it is, with no further batch sent (design section 4.4).
   */
  private def scoreAll(request: RerankRequest): Result[Seq[(Int, Double)]] =
    request.documents
      .grouped(batchSize)
      .zipWithIndex
      .foldLeft[Result[Vector[(Int, Double)]]](Right(Vector.empty)) { case (done, (batch, batchIdx)) =>
        done.flatMap { scored =>
          scoreBatch(request.query, batch, batchIdx * batchSize) match {
            case Right(scores)                   => Right(scored ++ scores)
            case Left(cancelled: CancelledError) => Left(cancelled)
            case Left(_)                         =>
              // On error, assign neutral scores
              Right(scored ++ batch.indices.map(i => (batchIdx * batchSize + i, 0.5)))
          }
        }
      }

  /**
   * Score a batch of documents using LLM.
   */
  private def scoreBatch(
    query: String,
    documents: Seq[String],
    startIndex: Int
  ): Result[Seq[(Int, Double)]] = {
    val userPrompt = buildUserPrompt(query, documents)

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt.getOrElse(defaultSystemPrompt)),
        UserMessage(userPrompt)
      )
    )

    client.complete(conversation).flatMap(response => parseScores(response.content, documents.length, startIndex))
  }

  /**
   * Build the user prompt for scoring.
   */
  private def buildUserPrompt(query: String, documents: Seq[String]): String = {
    val docsSection = documents.zipWithIndex
      .map { case (doc, idx) =>
        s"Document ${idx + 1}:\n${CodePointBoundary.take(doc, 1000)}" // Truncate long documents, never inside a surrogate pair
      }
      .mkString("\n\n")

    s"""Query: $query

$docsSection

Rate the relevance of each document to the query. Respond with ONLY a JSON array of scores."""
  }

  /**
   * Parse LLM response into scores.
   */
  private def parseScores(
    response: String,
    expectedCount: Int,
    startIndex: Int
  ): Result[Seq[(Int, Double)]] =
    // Extract JSON array from response (handle markdown code blocks). The text is the model's: a
    // document nested more than 512 levels deep is refused before it is parsed (#1562).
    Try(extractJsonArray(response)).toResult
      .flatMap(jsonStr => BoundedJson.read(jsonStr))
      .flatMap { json =>
        Try {
          val arr = json.arr
          val scores = arr.zipWithIndex.map { case (v, idx) =>
            val score = v match {
              case Num(n) => math.max(0.0, math.min(1.0, n))
              case Str(s) => math.max(0.0, math.min(1.0, s.toDouble))
              case _      => 0.5
            }
            (startIndex + idx, score)
          }.toSeq

          // Pad with neutral scores if LLM returned fewer than expected
          if (scores.length < expectedCount) {
            scores ++ (scores.length until expectedCount).map(i => (startIndex + i, 0.5))
          } else {
            scores.take(expectedCount)
          }
        }.toResult
      }
      .left
      .map { e =>
        RerankError(
          code = Some("PARSE_ERROR"),
          message = s"Failed to parse LLM response: ${e.message}",
          provider = "llm"
        )
      }

  /**
   * Extract JSON array from potentially markdown-wrapped response.
   */
  private def extractJsonArray(response: String): String = {
    val trimmed = response.trim

    // Handle markdown code blocks
    val withoutCodeBlock = if (trimmed.startsWith("```")) {
      val lines = trimmed.split("\n")
      val start = if (lines.head.contains("json")) 1 else 1
      val end   = lines.lastIndexWhere(_.trim == "```")
      if (end > start) {
        lines.slice(start, end).mkString("\n")
      } else {
        trimmed.stripPrefix("```json").stripPrefix("```").stripSuffix("```")
      }
    } else {
      trimmed
    }

    // Find JSON array bounds
    val startIdx = withoutCodeBlock.indexOf('[')
    val endIdx   = withoutCodeBlock.lastIndexOf(']')

    if (startIdx >= 0 && endIdx > startIdx) {
      withoutCodeBlock.substring(startIdx, endIdx + 1)
    } else {
      withoutCodeBlock
    }
  }
}

object LLMReranker {

  /**
   * Create a new LLM-based reranker.
   *
   * @param client LLM client for generating scores
   * @param batchSize Number of documents to score per LLM call (default: 10)
   * @param systemPrompt Custom system prompt (optional)
   */
  def apply(
    client: LLMClient,
    batchSize: Int = DEFAULT_BATCH_SIZE,
    systemPrompt: Option[String] = None
  ): LLMReranker = new LLMReranker(client, batchSize, systemPrompt)

  /**
   * Default batch size for LLM calls.
   */
  val DEFAULT_BATCH_SIZE: Int = 10
}
