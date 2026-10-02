package org.llm4s.reranker

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory
import ujson.{ Arr, Obj, read }

import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
 * Cohere Rerank API implementation.
 *
 * Calls POST https://api.cohere.com/v1/rerank with:
 * - model: reranking model name (e.g., "rerank-english-v3.0")
 * - query: search query
 * - documents: array of document strings
 * - top_n: number of results to return
 * - return_documents: whether to include document text
 *
 * @see https://docs.cohere.com/reference/rerank
 */
class CohereReranker(config: RerankProviderConfig) extends Reranker {

  private val httpClient = Llm4sHttpClient.create()
  private val logger     = LoggerFactory.getLogger(getClass)

  override def rerank(request: RerankRequest): Result[RerankResponse] = {
    val topN = request.topK.getOrElse(request.documents.size)

    val payload = Obj(
      "model"            -> config.model,
      "query"            -> request.query,
      "documents"        -> Arr.from(request.documents),
      "top_n"            -> topN,
      "return_documents" -> request.returnDocuments
    )

    val url = s"${config.baseUrl}/v1/rerank"

    logger.debug(s"[CohereReranker] POST $url model=${config.model} docs=${request.documents.size} topN=$topN")

    val headers = Map("Authorization" -> s"Bearer ${config.apiKey}", "Content-Type" -> "application/json")

    // The client never throws: a timeout, I/O failure or interruption (flag restored) is a Left
    val respEither: Either[RerankError, org.llm4s.http.HttpResponse] =
      httpClient
        .post(url, headers, payload.render(), timeout = 2.minutes)
        .left
        .map(e => RerankError(code = None, message = s"HTTP request failed: ${e.message}", provider = "cohere"))

    respEither.flatMap { response =>
      response.statusCode match {
        case 200 =>
          Try {
            val json = read(response.body)
            val results = json("results").arr.map { r =>
              val index = r("index").num.toInt
              val score = r("relevance_score").num
              val document = if (request.returnDocuments) {
                r("document")("text").str
              } else {
                request.documents(index)
              }
              RerankResult(index = index, score = score, document = document)
            }.toSeq

            val metadata = Map(
              "provider"  -> "cohere",
              "model"     -> config.model,
              "doc_count" -> request.documents.size.toString,
              "top_n"     -> topN.toString
            )

            RerankResponse(results = results, metadata = metadata)
          }.toEither.left
            .map { ex =>
              logger.error(s"[CohereReranker] Parse error: ${ex.getMessage}")
              RerankError(
                code = None,
                message = s"Parsing error: ${ex.getMessage}",
                provider = "cohere"
              )
            }
        case status =>
          val body = Redaction.truncateForLog(response.body)
          logger.error(s"[CohereReranker] HTTP error: $body")
          Left(
            RerankError(
              code = Some(status.toString),
              message = body,
              provider = "cohere"
            )
          )
      }
    }
  }
}

object CohereReranker {

  /** Default Cohere API base URL */
  val DEFAULT_BASE_URL: String = "https://api.cohere.com"

  /** Default reranking model */
  val DEFAULT_MODEL: String = "rerank-english-v3.0"

  /**
   * Create a Cohere reranker from config.
   *
   * @param config Provider configuration
   * @return Cohere reranker instance
   */
  def apply(config: RerankProviderConfig): CohereReranker =
    new CohereReranker(config)

  /**
   * Create a Cohere reranker from individual parameters.
   *
   * @param apiKey Cohere API key
   * @param model Reranking model (default: rerank-english-v3.0)
   * @param baseUrl API base URL (default: https://api.cohere.com)
   * @return Cohere reranker instance
   */
  def apply(
    apiKey: String,
    model: String = DEFAULT_MODEL,
    baseUrl: String = DEFAULT_BASE_URL
  ): CohereReranker =
    new CohereReranker(RerankProviderConfig(baseUrl, apiKey, model))
}
