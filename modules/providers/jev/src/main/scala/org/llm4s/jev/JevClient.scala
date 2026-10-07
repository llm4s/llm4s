package org.llm4s.jev

import org.llm4s.error.{ LLMError, ProcessingError }
import org.llm4s.http.{ HttpHeaders, Llm4sHttpClient }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

/**
 * A client for TypeSafe's Jev decision model (https://docs.typesafe.ai/api.md).
 *
 * Jev is not a chat model. It takes a `state` and named, typed [[JevQuestion]]s and answers each with a typed
 * [[JevAnswer]]: the probability of a yes, the selected option with a distribution, a score along ordered levels.
 * So this client is not an `LLMClient` and returns no `Completion`; it has no streaming and no conversation.
 *
 * {{{
 * val decision = for
 *   config   <- JevConfigLoader.default()
 *   client   <- JevClient(config)
 *   response <- client.evaluate(JevRequest("Charged twice; please refund", Map("urgent" -> JevQuestion.noul("Is this urgent?"))))
 *   urgent   <- response.noul("urgent")
 * yield urgent.probability
 * }}}
 *
 * `evaluate` is blocking and returns every failure as a [[org.llm4s.types.Result]]: an invalid request is a
 * `ValidationError` before anything is sent, a rejected key an `AuthenticationError`, a rate limit a `RateLimitError`
 * carrying the delay the server asked for, an outage a `ServiceError`, a transport failure a `NetworkError` or
 * `TimeoutError`, a response that does not match the API a `ProcessingError`, and an interrupt a `CancelledError`.
 * Transient failures are retried as the config's [[JevRetryPolicy]] says. The API key is never printed, logged or
 * put into an error.
 *
 * '''No idempotency key.''' TypeSafe's documentation describes no idempotency key or other de-duplication
 * mechanism, so this client does not invent one: each attempt of a retried request is a new, billable call. A
 * header the caller attaches with [[JevRequest#withHeader]] is sent unchanged on every attempt.
 *
 * The client is thread-safe. [[close]] releases the HTTP connections it owns.
 */
final class JevClient private (
  config: JevConfig,
  http: Llm4sHttpClient,
  ownsHttp: Boolean,
  retry: JevRetry
) extends AutoCloseable {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Asks `request`'s questions about its state.
   *
   * @return the answers, or the error described on [[JevClient]]
   */
  def evaluate(request: JevRequest): Result[JevResponse] =
    for {
      _ <- request.validate
      body    = ujson.write(request.toJson(config.model))
      headers = requestHeaders(request)
      _ = logger.debug(
        "Jev evaluate: {} question(s), model {}",
        request.questions.size,
        request.model.getOrElse(config.model)
      )
      response <- retry.run(left => attempt(headers, body, request, config.timeout.min(left)))
    } yield response

  private def requestHeaders(request: JevRequest): Map[String, String] =
    JevHeaders.merge(config.headers, request.headers) ++ Map(
      "Authorization" -> s"Bearer ${config.apiKey}",
      "Content-Type"  -> "application/json",
      "Accept"        -> "application/json"
    )

  /**
   * One HTTP attempt: sent, classified by status, parsed. `timeout` is the configured per-attempt timeout capped at
   * what is left of the retry budget, so a retry cannot run the call past the budget.
   */
  private def attempt(
    headers: Map[String, String],
    body: String,
    request: JevRequest,
    timeout: FiniteDuration
  ): Result[JevResponse] =
    http.post(config.evaluateUrl, headers, body, timeout).flatMap { response =>
      if (response.statusCode >= 200 && response.statusCode < 300) {
        if (response.body.length > JevClient.MaxResponseChars)
          Left(
            ProcessingError("jev-response", s"Jev's response is larger than ${JevClient.MaxResponseChars} characters")
          )
        else
          JevResponse
            .parse(response.body, HttpHeaders.first(response.headers, JevClient.RequestIdHeader))
            .flatMap(checkAnswers(_, request))
            .left
            .map(redacted)
      } else Left(JevErrors.fromResponse(response, config.apiKey))
    }

  /**
   * Every question was answered, each with an answer of its own type within what it asked: a Choice among the
   * options requested, a Score among the levels requested. A response that breaks this is not the API's.
   */
  private def checkAnswers(response: JevResponse, request: JevRequest): Result[JevResponse] = {
    val missing = request.questions.keySet.diff(response.answers.keySet)
    if (missing.nonEmpty)
      Left(
        ProcessingError(
          "jev-response",
          s"Jev's response has no answer for question(s): ${missing.toSeq.sorted.mkString(", ")}"
        )
      )
    else
      request.questions.toSeq
        .sortBy(_._1)
        .iterator
        .flatMap { case (id, question) => mismatch(question, response.answers(id)).map(id -> _) }
        .nextOption()
        .fold[Result[JevResponse]](Right(response)) { case (id, problem) =>
          Left(ProcessingError("jev-response", s"Jev's answer to question $id does not match the question: $problem"))
        }
  }

  /**
   * `error` with the API key masked. A 2xx response's errors quote values the server chose (an answer's type, a
   * choice, a question id), so a server that echoed the key into one would otherwise carry it into the error.
   */
  private def redacted(error: LLMError): LLMError = error match {
    case processing: ProcessingError if config.apiKey.nonEmpty && processing.message.contains(config.apiKey) =>
      ProcessingError(
        processing.operation,
        processing.message
          .stripPrefix(s"Processing failed during ${processing.operation}: ")
          .replace(config.apiKey, "***"),
        processing.cause
      )
    case other => other
  }

  /** Why `answer` cannot be the answer to `question`, if it cannot. */
  private def mismatch(question: JevQuestion, answer: JevAnswer): Option[String] = (question, answer) match {
    case (_: JevQuestion.Noul, _: NoulAnswer) => None
    case (JevQuestion.Choice(_, options), ChoiceAnswer(choice, probabilities, _)) =>
      val asked = options.map(_._1).toSet
      if (!asked.contains(choice)) Some(s"'$choice' is not one of the options asked")
      else
        probabilities.keys.toSeq.sorted
          .find(option => !asked.contains(option))
          .map(option => s"it gives a probability for '$option', which is not one of the options asked")
          .orElse(
            asked.toSeq.sorted
              .find(option => !probabilities.contains(option))
              .map(option => s"it gives no probability for '$option', one of the options asked")
          )
    case (JevQuestion.Score(_, levels), ScoreAnswer(_, answered, _)) =>
      answered
        .find(_.index >= levels.size)
        .map(level => s"it has level ${level.index}, but the question has ${levels.size} levels")
        .orElse(
          levels.indices
            .find(index => !answered.exists(_.index == index))
            .map(index => s"it has no level $index, one of the ${levels.size} levels asked")
        )
    case (_, _) =>
      val expected = question match {
        case _: JevQuestion.Noul   => "noul"
        case _: JevQuestion.Choice => "choice"
        case _: JevQuestion.Score  => "score"
      }
      Some(s"expected a $expected answer")
  }

  /** Releases the HTTP connections this client owns; a client built over an HTTP client you passed leaves it open. */
  override def close(): Unit = if (ownsHttp) http.close()
}

object JevClient {

  /** The response header carrying the API's request id. */
  private[jev] val RequestIdHeader: String = "x-typesafe-request-id"

  /**
   * The longest response body accepted. Answers are small (a few numbers per question), so this is generous; a body
   * beyond it is refused before it is parsed. The shared HTTP client has already read the body by then.
   */
  private[jev] val MaxResponseChars: Int = 16 * 1024 * 1024

  /**
   * A client for `config`, which is validated first. The client reads no configuration itself: load `config` at the
   * application edge with [[org.llm4s.config.JevConfigLoader]], or build it in code.
   */
  def apply(config: JevConfig): Result[JevClient] =
    config.validate.map(valid => new JevClient(valid, Llm4sHttpClient.create(), true, new JevRetry(valid.retry)))

  /** For tests: a client over `http` with the clock, sleep and random source of `retry`. */
  private[jev] def withHttp(config: JevConfig, http: Llm4sHttpClient, retry: JevRetry): Result[JevClient] =
    config.validate.map(valid => new JevClient(valid, http, false, retry))
}
