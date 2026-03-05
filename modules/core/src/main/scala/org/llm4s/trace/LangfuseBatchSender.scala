package org.llm4s.trace

import org.llm4s.http.Llm4sHttpClient
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.control.NonFatal

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
 * the provided public and secret keys. Handles both successful
 * responses (200-299) and partial success responses (207).
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
    val apiUrl = if (config.langfuseUrl.endsWith("/api/public/ingestion")) {
      config.langfuseUrl
    } else {
      config.langfuseUrl.stripSuffix("/") + "/api/public/ingestion"
    }

    logger.debug(s"[Langfuse] Sending batch to URL: $apiUrl")
    logger.debug(s"[Langfuse] Using public key: ${config.publicKey.take(10)}...")
    logger.debug(s"[Langfuse] Events in batch: ${events.length}")

    val batchPayload = ujson.Obj("batch" -> ujson.Arr(events: _*))
    val credentials =
      Base64.getEncoder.encodeToString(s"${config.publicKey}:${config.secretKey}".getBytes(StandardCharsets.UTF_8))

    // Catch only non-fatal exceptions. Fatal errors (OOM, StackOverflow, etc.) will crash fast.
    // InterruptedException is handled explicitly to restore the interrupt flag.
    try {
      val response = httpClient.post(
        url = apiUrl,
        headers = Map(
          "Content-Type"  -> "application/json",
          "User-Agent"    -> "llm4s-scala/1.0.0",
          "Authorization" -> s"Basic $credentials"
        ),
        body = batchPayload.render(),
        timeout = 30000
      )

      if (response.statusCode == 207 || (response.statusCode >= 200 && response.statusCode < 300)) {
        logger.info(s"[Langfuse] Batch export successful: ${response.statusCode}")
        if (response.statusCode == 207) {
          logger.info(
            s"[Langfuse] Partial success response: ${org.llm4s.util.Redaction.truncateForLog(response.body)}"
          )
        }
      } else {
        logger.error(s"[Langfuse] Batch export failed: ${response.statusCode}")
        logger.error(s"[Langfuse] Response body: ${org.llm4s.util.Redaction.truncateForLog(response.body)}")
        logger.error(s"[Langfuse] Request URL: $apiUrl")
        logger.error(s"[Langfuse] Request payload size: ${batchPayload.render().length} bytes")
      }
    } catch {
      case _: InterruptedException =>
        // Restore interrupt flag for proper thread shutdown and timeout semantics
        restoreInterrupt()
        logger.warn("[Langfuse] Batch export was interrupted.")
      case NonFatal(e) =>
        // Catch all non-fatal exceptions (network errors, etc.)
        // Fatal errors (OutOfMemoryError, StackOverflowError, etc.) will propagate
        logger.error(s"[Langfuse] Batch export failed with exception: ${e.getMessage}", e)
        logger.error(s"[Langfuse] Request URL: $apiUrl")
    }
  }
}
