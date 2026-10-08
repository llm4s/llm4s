package org.llm4s.trace

import org.llm4s.error.ExecutionError
import org.llm4s.http.Llm4sHttpClient
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.*

/**
 * Abstraction for sending trace events to Langfuse in batches.
 *
 * Implementations handle the HTTP communication with the Langfuse API,
 * including authentication, error handling, and logging.
 *
 * @see [[DefaultLangfuseBatchSender]] for the standard implementation
 */
trait LangfuseBatchSender {

  /**
   * Sends a batch of trace events to Langfuse.
   *
   * @param events Sequence of JSON objects representing trace events
   * @param config HTTP API configuration including URL and credentials
   */
  def sendBatch(events: Seq[ujson.Obj], config: LangfuseHttpApiCaller): Unit
}

/**
 * Configuration for calling the Langfuse HTTP API.
 *
 * @param langfuseUrl Base URL of the Langfuse API endpoint
 * @param publicKey Langfuse public key for authentication
 * @param secretKey Langfuse secret key for authentication
 */
case class LangfuseHttpApiCaller(
  langfuseUrl: String,
  publicKey: String,
  secretKey: String
)

/**
 * Default implementation of [[LangfuseBatchSender]] using HTTP requests.
 *
 * Sends trace events to Langfuse using basic authentication with
 * the provided public and secret keys. A 2xx response is a success; a 207 Multi-Status
 * response is read per event, and any events it lists as rejected are logged as an error.
 *
 * Logs warnings if credentials are not configured and errors
 * if the HTTP request fails.
 */
class DefaultLangfuseBatchSender(
  httpClient: Llm4sHttpClient = Llm4sHttpClient.create(),
  restoreInterrupt: () => Unit = () => Thread.currentThread().interrupt()
) extends LangfuseBatchSender {
  private val logger = LoggerFactory.getLogger(getClass)

  override def sendBatch(events: Seq[ujson.Obj], config: LangfuseHttpApiCaller): Unit = {
    if (config.publicKey.isEmpty || config.secretKey.isEmpty) {
      logger.warn("[Langfuse] Public or secret key not set in environment. Skipping export.")
      logger.warn(s"[Langfuse] Expected environment variables: LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY")
      logger.warn(s"[Langfuse] Current URL: ${config.langfuseUrl}")
      return
    }

    // Ensure URL includes the API ingestion endpoint (same normalization as LangfuseTracing)
    val apiUrl = DefaultLangfuseBatchSender.normalizeIngestionUrl(config.langfuseUrl)

    logger.debug(s"[Langfuse] Sending batch to URL: $apiUrl")
    logger.debug(s"[Langfuse] Using public key: ${config.publicKey.take(10)}...")
    logger.debug(s"[Langfuse] Events in batch: ${events.length}")

    val batchPayload = ujson.Obj("batch" -> ujson.Arr(events: _*))
    val credentials =
      Base64.getEncoder.encodeToString(s"${config.publicKey}:${config.secretKey}".getBytes(StandardCharsets.UTF_8))

    httpClient.post(
      url = apiUrl,
      headers = Map(
        "Content-Type"  -> "application/json",
        "User-Agent"    -> "llm4s-scala/1.0.0",
        "Authorization" -> s"Basic $credentials"
      ),
      body = batchPayload.render(),
      timeout = 30.seconds
    ) match {
      case Left(_: ExecutionError) =>
        // The HTTP client reports an interrupted request as an ExecutionError, having restored
        // the interrupt flag; restoreInterrupt keeps the hook callers rely on.
        restoreInterrupt()
        logger.warn("[Langfuse] Batch export was interrupted.")
      case Left(error) =>
        logger.error(s"[Langfuse] Batch export failed: ${error.message}")
        logger.error(s"[Langfuse] Request URL: $apiUrl")
      case Right(response) if response.statusCode == 207 =>
        // Multi-Status: per-event results. Events listed under `errors` were dropped.
        LangfuseIngestionResponse.rejections(response.body) match {
          case Right(rejected) if rejected.nonEmpty =>
            logger.error(s"[Langfuse] ${LangfuseIngestionResponse.summary(rejected, events.length)}")
          case Right(_) =>
            logger.info("[Langfuse] Batch export successful: 207, every event accepted")
          case Left(reason) =>
            logger.warn(s"[Langfuse] Batch export returned 207 with an $reason; treating it as accepted")
        }
      case Right(response) if response.statusCode >= 200 && response.statusCode < 300 =>
        logger.info(s"[Langfuse] Batch export successful: ${response.statusCode}")
      case Right(response) =>
        logger.error(s"[Langfuse] Batch export failed: ${response.statusCode}")
        logger.error(s"[Langfuse] Response body: ${org.llm4s.util.Redaction.safeBody(response.body)}")
        logger.error(s"[Langfuse] Request URL: $apiUrl")
        logger.error(s"[Langfuse] Request payload size: ${batchPayload.render().length} bytes")
    }
  }
}

object DefaultLangfuseBatchSender {

  /**
   * Appends the Langfuse ingestion API path to `baseUrl` unless it's already present.
   */
  private[trace] def normalizeIngestionUrl(baseUrl: String): String =
    if (baseUrl.endsWith("/api/public/ingestion")) baseUrl
    else baseUrl.stripSuffix("/") + "/api/public/ingestion"
}
