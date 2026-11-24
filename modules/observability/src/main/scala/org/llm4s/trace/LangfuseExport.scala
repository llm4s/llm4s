package org.llm4s.trace

import org.llm4s.error.{ ExecutionError, UnknownError }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import org.slf4j.Logger

import scala.concurrent.duration.*
import scala.util.Try

/**
 * One POST to Langfuse - a trace export or a batch of scores - and what its response means. Shared by
 * [[LangfuseTracing]] and [[DefaultLangfuseBatchSender]], so both report a failure, and a partial rejection, alike.
 *
 * Nothing is retried, as before #2883: a 4xx or 5xx is logged, with its body redacted, and returned as a `Left`.
 * Nothing throws.
 */
private[trace] object LangfuseExport {

  /** What is posted: where, how many items it carries, and how to read a 2xx response that may reject some. */
  final case class Post(
    url: String,
    body: String,
    items: Int,
    rejections: (Int, String) => Either[String, Option[String]]
  )

  def traces(apiUrl: String, request: ujson.Obj): Post = {
    def field(v: ujson.Value, key: String): Seq[ujson.Value] =
      v.objOpt.flatMap(_.get(key)).flatMap(_.arrOpt).toSeq.flatten
    val spans = for {
      resource <- field(request, "resourceSpans")
      scope    <- field(resource, "scopeSpans")
      span     <- field(scope, "spans")
    } yield span
    Post(LangfuseOtlp.tracesUrl(apiUrl), request.render(), spans.size, (_, body) => otlpRejection(body, spans.size))
  }

  def scores(apiUrl: String, scores: Seq[ujson.Obj]): Post =
    Post(
      LangfuseOtlp.scoresUrl(apiUrl),
      ujson.Arr.from(scores).render(),
      scores.size,
      (status, body) => if (status == 207) scoreRejection(body, scores.size) else Right(None)
    )

  def send(
    post: Post,
    publicKey: Secret,
    secretKey: Secret,
    httpClient: Llm4sHttpClient,
    restoreInterrupt: () => Unit,
    logger: Logger
  ): Result[Unit] =
    if (publicKey.reveal.isEmpty || secretKey.reveal.isEmpty) {
      logger.warn("[Langfuse] Public or secret key not set. Skipping export.")
      logger.warn(
        "[Langfuse] Expected llm4s.tracing.langfuse.publicKey and secretKey (LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY)"
      )
      Right(())
    } else {
      logger.debug(s"[Langfuse] Posting ${post.items} item(s) to ${post.url}")
      val credentials = Secret.basic(publicKey.reveal, secretKey.reveal).reveal
      httpClient.post(
        url = post.url,
        headers = Map(
          "Content-Type"  -> "application/json",
          "User-Agent"    -> "llm4s-scala/1.0.0",
          "Authorization" -> s"Basic $credentials",
          LangfuseOtlp.IngestionVersionHeader
        ),
        body = post.body,
        timeout = 30.seconds
      ) match {
        case Left(error: ExecutionError) =>
          // The HTTP client reports an interrupted request as an ExecutionError, having restored
          // the interrupt flag; restoreInterrupt keeps the hook callers rely on.
          restoreInterrupt()
          logger.warn("[Langfuse] Export was interrupted.")
          Left(error)
        case Left(error) =>
          logger.error(s"[Langfuse] Export failed: ${error.message}")
          logger.error(s"[Langfuse] Request URL: ${post.url}")
          Left(error)
        case Right(response) if response.statusCode >= 200 && response.statusCode < 300 =>
          post.rejections(response.statusCode, response.body) match {
            case Right(Some(summary)) =>
              logger.error(s"[Langfuse] $summary")
              Left(UnknownError(summary, new RuntimeException(summary)))
            case Right(None) =>
              logger.info(s"[Langfuse] Export successful: ${response.statusCode}, ${post.items} item(s)")
              Right(())
            case Left(reason) =>
              // Cannot tell what was accepted; do not fail tracing on an unexpected shape.
              logger.warn(s"[Langfuse] Export returned ${response.statusCode} with an $reason; treating it as accepted")
              Right(())
          }
        case Right(response) =>
          logger.error(s"[Langfuse] Export failed: ${response.statusCode}")
          logger.error(s"[Langfuse] Response body: ${Redaction.safeBody(response.body)}")
          logger.error(s"[Langfuse] Request URL: ${post.url}")
          val failure = new RuntimeException(s"Langfuse export failed: ${response.statusCode}")
          Left(UnknownError(failure.getMessage, failure))
      }
    }

  /**
   * An OTLP export response's partial success (`{"partialSuccess": {"rejectedSpans": n, "errorMessage": ...}}`): a
   * summary when it says spans were rejected. An empty body or `{}` is full success.
   */
  def otlpRejection(body: String, batchSize: Int): Either[String, Option[String]] =
    if (body.trim.isEmpty) Right(None)
    else
      readObject(body).map { root =>
        val partial  = root.get("partialSuccess").flatMap(_.objOpt)
        val rejected = partial.flatMap(_.get("rejectedSpans")).flatMap(count).getOrElse(0L)
        Option.when(rejected > 0) {
          val message = partial.flatMap(_.get("errorMessage")).flatMap(_.strOpt).getOrElse("no message")
          s"Langfuse rejected $rejected of $batchSize spans: ${Redaction.safeBody(message)}"
        }
      }

  /**
   * A 207 from `POST /api/public/scores` (`{"accepted": n, "rejected": n, "errors": [{"message": ...}]}`): a summary
   * when it says scores were rejected.
   */
  def scoreRejection(body: String, batchSize: Int): Either[String, Option[String]] =
    readObject(body).map { root =>
      val errors   = root.get("errors").flatMap(_.arrOpt).toSeq.flatten
      val rejected = root.get("rejected").flatMap(count).getOrElse(errors.size.toLong)
      Option.when(rejected > 0) {
        val messages =
          errors.map(e => e.objOpt.flatMap(_.get("message")).fold("no message")(v => v.strOpt.getOrElse(v.render())))
        s"Langfuse rejected $rejected of $batchSize scores: ${Redaction.safeBody(messages.mkString(", "))}"
      }
    }

  private def readObject(body: String): Either[String, collection.mutable.Map[String, ujson.Value]] =
    Try(ujson.read(body)).toEither.left
      .map(e => s"unreadable body: ${e.getMessage}")
      .flatMap(_.objOpt.toRight("body that is not a JSON object"))

  // OTLP JSON writes an int64 as a decimal string; accept a number too
  private def count(v: ujson.Value): Option[Long] =
    v.numOpt.map(_.toLong).orElse(v.strOpt.flatMap(_.toLongOption))
}
