package org.llm4s.trace

import org.llm4s.security.Secret
import org.llm4s.http.Llm4sHttpClient
import org.slf4j.LoggerFactory

/**
 * Sends to Langfuse what is not an [[LangfuseTracing]] event: an OpenTelemetry trace export and a batch of scores.
 * `RAGASLangfuseObserver` in `llm4s-rag` sends its evaluations through it.
 *
 * Implementations handle the HTTP communication with the Langfuse API, including authentication, error handling and
 * logging, and never throw.
 *
 * @see [[DefaultLangfuseBatchSender]] for the standard implementation
 */
trait LangfuseBatchSender {

  /**
   * Posts an OTLP/JSON `ExportTraceServiceRequest` to Langfuse's OpenTelemetry endpoint,
   * `<langfuseUrl>/api/public/otel/v1/traces`.
   *
   * @param request the export: `resourceSpans`, each with `scopeSpans` and their `spans`
   * @param config  the Langfuse URL and credentials
   */
  def sendTraces(request: ujson.Obj, config: LangfuseHttpApiCaller): Unit

  /**
   * Posts scores to `<langfuseUrl>/api/public/scores`, as one JSON array.
   *
   * @param scores one object per score: `traceId`, `name`, `value` and optionally `id`, `observationId`, `comment`,
   *               `dataType`
   * @param config the Langfuse URL and credentials
   */
  def sendScores(scores: Seq[ujson.Obj], config: LangfuseHttpApiCaller): Unit
}

/**
 * Configuration for calling the Langfuse HTTP API.
 *
 * @param langfuseUrl the Langfuse host (`https://cloud.langfuse.com`, or a self-hosted server's base URL). A URL
 *                    ending in one of Langfuse's API paths, such as the legacy `/api/public/ingestion`, names the
 *                    host it is on; each request appends its own path
 * @param publicKey Langfuse public key for authentication
 * @param secretKey Langfuse secret key for authentication
 */
case class LangfuseHttpApiCaller(
  langfuseUrl: String,
  publicKey: Secret,
  secretKey: Secret
)

/**
 * Default implementation of [[LangfuseBatchSender]] over HTTP.
 *
 * Authenticates with HTTP Basic (the public key as user, the secret key as password) and sends
 * `x-langfuse-ingestion-version: 4`. A 2xx response is a success, unless its body says some of what was sent was
 * rejected - an OTLP partial success, or a `207` from the scores endpoint - which is logged as an error. A failed
 * request, or a 4xx or 5xx response, is logged with its body redacted, and not retried. Without both keys it logs a
 * warning and sends nothing.
 */
class DefaultLangfuseBatchSender(
  httpClient: Llm4sHttpClient = Llm4sHttpClient.create(),
  restoreInterrupt: () => Unit = () => Thread.currentThread().interrupt()
) extends LangfuseBatchSender {
  private val logger = LoggerFactory.getLogger(getClass)

  override def sendTraces(request: ujson.Obj, config: LangfuseHttpApiCaller): Unit =
    send(LangfuseExport.traces(config.langfuseUrl, request), config)

  override def sendScores(scores: Seq[ujson.Obj], config: LangfuseHttpApiCaller): Unit =
    if (scores.nonEmpty) send(LangfuseExport.scores(config.langfuseUrl, scores), config)

  private def send(post: LangfuseExport.Post, config: LangfuseHttpApiCaller): Unit = {
    // the outcome is logged by LangfuseExport; this sender's callers expect no result
    val _ = LangfuseExport.send(post, config.publicKey, config.secretKey, httpClient, restoreInterrupt, logger)
  }
}
