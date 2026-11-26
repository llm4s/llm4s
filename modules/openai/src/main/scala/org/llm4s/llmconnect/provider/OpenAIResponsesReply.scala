package org.llm4s.llmconnect.provider

import com.openai.core.JsonField
import com.openai.models.responses.{ Response, ResponseStatus, ResponseUsage }
import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, FinishReason, TokenUsage }
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }
import org.llm4s.types.Result

import java.time.Instant
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Try

/**
 * OpenAI's Responses API `Response` as a [[org.llm4s.llmconnect.model.Completion]].
 *
 * Fields are read as leniently as the Chat Completions reply's: one the response omits is absent, not a failure.
 * The estimated cost and the request's adjustments are the client's to add.
 */
private[provider] object OpenAIResponsesReply:

  def completion(response: Response, provider: ProviderId): Result[Completion] =
    val status = known(response._status())
    if status.exists(_.asString() == ResponseStatus.FAILED.asString()) then
      val reason = response.error().toScala.map(_.message()).getOrElse("the response failed")
      Left(ServiceError(502, provider, reason))
    else
      val text    = outputText(response)
      val usage   = known(response._usage()).map(tokenUsage)
      val message = AssistantMessage(text = if text.isEmpty then None else Some(text), toolCalls = Seq.empty)
      val completion = Completion(
        id = known(response._id()).getOrElse(""),
        created = known(response._createdAt())
          .flatMap(s => Try(Instant.ofEpochSecond(s.longValue)).toOption)
          .getOrElse(Instant.EPOCH),
        model = known(response._model()).flatMap(modelOf).map(ModelName(_)).getOrElse(ModelName("")),
        message = message,
        usage = usage,
        finishReason =
          if status.exists(_.asString() == ResponseStatus.COMPLETED.asString()) then FinishReason.Stop
          else FinishReason.Unknown,
        servedTier = known(response._serviceTier()).flatMap(t => OpenAIServiceTier.served(t.asString()))
      )
      Right(completion)

  private def modelOf(model: com.openai.models.ResponsesModel): Option[String] =
    model.string().toScala.orElse(model.chat().toScala.map(_.asString())).orElse(model.only().toScala.map(_.asString()))

  /** The `output_text` parts of the output messages, concatenated. */
  private def outputText(response: Response): String =
    known(response._output())
      .map(_.asScala.toSeq)
      .getOrElse(Seq.empty)
      .filter(_.isMessage)
      .flatMap(item => messageTexts(item.asMessage()))
      .mkString

  private def messageTexts(item: com.openai.models.responses.ResponseOutputMessage): Seq[String] =
    known(item._content())
      .map(_.asScala.toSeq)
      .getOrElse(Seq.empty)
      .filter(_.isOutputText)
      .flatMap(c => known(c.asOutputText()._text()))

  /** OpenAI counts reasoning inside `output_tokens`; `TokenUsage.completionTokens` never includes thinking. */
  private def tokenUsage(u: ResponseUsage): TokenUsage =
    val input     = known(u._inputTokens()).fold(0)(_.intValue)
    val output    = known(u._outputTokens()).fold(0)(_.intValue)
    val reasoning = known(u._outputTokensDetails()).flatMap(d => known(d._reasoningTokens())).map(_.intValue)
    TokenUsage(
      promptTokens = input,
      completionTokens = math.max(0, output - reasoning.fold(0)(math.max(0, _))),
      totalTokens = known(u._totalTokens()).fold(input + output)(_.intValue),
      thinkingTokens = reasoning,
      cachedTokens = known(u._inputTokensDetails()).flatMap(d => known(d._cachedTokens())).map(_.intValue)
    )

  private def known[T](field: JsonField[T]): Option[T] = field.asKnown().toScala
