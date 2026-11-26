package org.llm4s.llmconnect.provider

import com.openai.models.responses.ResponseCreateParams
import org.llm4s.error.{ LLMError, ServiceError, ValidationError }

import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

/**
 * OpenAI refusing a reasoning item a thread stored: the error says so, and what to do. The hint is added only to a
 * request that carried a `reasoning` input item, and only to an error whose code or message names encrypted content;
 * any other error is the one the client reports without it. It reaches every way the rejection arrives: an HTTP 400,
 * a 200 reply whose status is `failed`, and a stream's `error` event or `response.failed`.
 */
private[provider] object OpenAIReasoningRejection:

  private val Code = "invalid_encrypted_content"

  /** What follows OpenAI's own message. */
  val Hint: String =
    " OpenAI rejected reasoning stored on this thread. " +
      "Likely causes: it was produced with a different API key or organisation than the one now in use, " +
      "the model alias now points at another snapshot, or the thread is old. " +
      "To continue, set keepReasoning = false on the provider section (or withKeepReasoning(false) on the OpenAIConfig, " +
      ".keepReasoning(false) on the Java builder, llm4s.keep-reasoning=false in Spring), " +
      "or continue the thread from an edited turn."

  /** Whether the built request sends at least one `reasoning` input item. */
  def carriesReasoning(params: ResponseCreateParams): Boolean =
    Try(
      params
        .input()
        .toScala
        .exists(input => input.isResponse && input.asResponse().asScala.exists(_.isReasoning))
    ).getOrElse(false)

  /** Whether an `error` object (`{"code":..,"message":..}`) is OpenAI refusing an encrypted reasoning item. */
  def rejects(error: ujson.Value): Boolean =
    error.objOpt.exists { fields =>
      def text(key: String): Option[String] = fields.get(key).flatMap(_.strOpt)
      text("code").contains(Code) ||
      text("message").exists(_.toLowerCase(Locale.ROOT).contains("encrypted content"))
    }

  /** `error` with the hint after its message, as the type it is; an error of another type is returned as it is. */
  def explain(error: LLMError): LLMError = error match
    case e: ValidationError if e.violations.nonEmpty =>
      val explained = ValidationError(e.field, e.violations.dropRight(1) :+ (e.violations.last + Hint))
      val status    = e.httpStatus.fold(explained)(explained.withHttpStatus)
      e.requestId.fold(status)(status.withRequestId)
    case e: ServiceError =>
      val details = e.message.stripPrefix(s"Service error from ${e.provider}: ").stripSuffix(s" (HTTP ${e.status})")
      ServiceError.rewrapped(e, e.provider, details + Hint)
    case other => other
