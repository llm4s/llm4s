package org.llm4s.llmconnect.provider

import com.openai.core.JsonField
import com.openai.models.responses.{
  Response,
  ResponseOutputItem,
  ResponseOutputMessage,
  ResponseStatus,
  ResponseUsage
}
import org.llm4s.error.{ LLMError, ServiceError }
import org.llm4s.llmconnect.model.{ AssistantMessage, Citation, Completion, FinishReason, TokenUsage, ToolCall }
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }
import org.llm4s.types.Result

import java.time.Instant
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Try

/**
 * OpenAI's Responses API `Response` as a [[org.llm4s.llmconnect.model.Completion]].
 *
 * Optional fields are read as leniently as the Chat Completions reply's: one the response omits, or gives in a shape
 * the SDK does not know (`error` as a string, say), is absent, not a failure. A reply too malformed to read at all
 * (a `status` that is not a string) is a `Left` through [[OpenAIClient.mapError]], never a thrown exception.
 * The estimated cost and the request's adjustments are the client's to add.
 */
private[provider] object OpenAIResponsesReply:

  def completion(response: Response, provider: ProviderId, configuredModel: ModelName): Result[Completion] =
    Try(read(response, provider, configuredModel)).toEither.left
      .map(OpenAIClient.mapError(_, provider))
      .flatMap(identity)

  private def read(response: Response, provider: ProviderId, configuredModel: ModelName): Result[Completion] =
    val status = known(response._status()).map(_.asString())
    if status.contains(ResponseStatus.FAILED.asString()) then Left(failure(response, provider))
    else
      val items    = outputItems(response)
      val finish   = finishReason(status, response)
      val messages = items.filter(_.isMessage).map(_.asMessage())
      val text     = messages.flatMap(messageTexts).mkString
      // a filtered reply's calls are not read: the filter fails the reply (`OpenAIClient.filteredReply`)
      val calls =
        if ChatCompletionsFinishReason.isFiltered(finish) then Right(Seq.empty)
        else toolCalls(items, finish)
      calls.flatMap { toolCalls =>
        val message = AssistantMessage(text = if text.isEmpty then None else Some(text), toolCalls = toolCalls)
        ToolCallRules.checked(
          Completion(
            id = known(response._id()).getOrElse(""),
            created = known(response._createdAt())
              .flatMap(s => Try(Instant.ofEpochSecond(s.longValue)).toOption)
              .getOrElse(Instant.EPOCH),
            model = known(response._model()).flatMap(modelOf).map(ModelName(_)).getOrElse(configuredModel),
            message = message,
            usage = known(response._usage()).map(tokenUsage),
            citations = messages.flatMap(citations).toList,
            finishReason = if toolCalls.nonEmpty && finish == FinishReason.Stop then FinishReason.ToolCalls else finish,
            servedTier = known(response._serviceTier()).flatMap(t => OpenAIServiceTier.served(t.asString()))
          )
        )
      }

  /** A `failed` reply's `error`, through the mapping a stream's `error` event takes (a 502 unless it names a status). */
  private def failure(response: Response, provider: ProviderId): LLMError =
    val error = known(response._error())
    val fields = Seq(
      error.flatMap(e => known(e._message())).map("message" -> ujson.Str(_)),
      error.flatMap(e => known(e._code())).map(c => "code" -> ujson.Str(c.asString())),
      error.flatMap(e => known(e._code())).flatMap(c => statusOf(c.asString())).map("status_code" -> ujson.Num(_))
    ).flatten
    OpenAICompatibleClient
      .streamedError(ujson.Obj.from(fields), provider)
      .getOrElse(ServiceError(502, provider, "the response failed"))

  /** The HTTP status of the one `error.code` that names one; `server_error` and every other code stay a 502. */
  private def statusOf(code: String): Option[Int] =
    code match
      case "rate_limit_exceeded" => Some(429)
      case _                     => None

  /** `completed` is `Stop`; `incomplete` is `Length` at the output limit and the content filter's at the filter. */
  private def finishReason(status: Option[String], response: Response): FinishReason =
    status match
      case Some(s) if s == ResponseStatus.COMPLETED.asString() => FinishReason.Stop
      case Some(s) if s == ResponseStatus.INCOMPLETE.asString() =>
        known(response._incompleteDetails())
          .flatMap(d => known(d._reason()))
          .map(_.asString()) match
          case Some("max_output_tokens") => FinishReason.Length
          case Some("content_filter")    => FinishReason.Other(ChatCompletionsFinishReason.ContentFilter)
          case _                         => FinishReason.Unknown
      case _ => FinishReason.Unknown

  /** The `function_call` items as tool calls, in output order, read as Chat Completions' are. */
  private def toolCalls(items: Seq[ResponseOutputItem], finish: FinishReason): Result[Seq[ToolCall]] =
    // an item of a type the SDK does not know is neither: `isFunctionCall` is false, and nothing throws
    items
      .filter(_.isFunctionCall)
      .map(_.asFunctionCall())
      .foldLeft[Result[Vector[ToolCall]]](Right(Vector.empty)) { (done, call) =>
        done.flatMap { calls =>
          OpenAIClient
            .toolCallOf(
              known(call._callId()).getOrElse(""),
              known(call._name()).getOrElse(""),
              known(call._arguments()).getOrElse(""),
              finish
            )
            .map(calls :+ _)
        }
      }

  private def outputItems(response: Response): Seq[ResponseOutputItem] =
    known(response._output()).map(_.asScala.toSeq).getOrElse(Seq.empty)

  private def modelOf(model: com.openai.models.ResponsesModel): Option[String] =
    model.string().toScala.orElse(model.chat().toScala.map(_.asString())).orElse(model.only().toScala.map(_.asString()))

  /** Every `output_text` and `refusal` part of the message, in order: a refusal is the model's answer, not a filter. */
  private def messageTexts(item: ResponseOutputMessage): Seq[String] =
    contents(item).flatMap { c =>
      if c.isOutputText then known(c.asOutputText()._text())
      else if c.isRefusal then known(c.asRefusal()._refusal())
      else None
    }

  /** The `url_citation` annotations of the message's `output_text` parts, as `OpenAIClient` reads Chat Completions'. */
  private def citations(item: ResponseOutputMessage): Seq[Citation] =
    contents(item).filter(_.isOutputText).flatMap { c =>
      known(c.asOutputText()._annotations()).map(_.asScala.toSeq).getOrElse(Seq.empty).flatMap { annotation =>
        if annotation.isUrlCitation then
          val cited = annotation.asUrlCitation()
          OpenAIClient.citationOf(
            known(cited._url()),
            known(cited._title()),
            known(cited._startIndex()),
            known(cited._endIndex())
          )
        else None
      }
    }

  private def contents(item: ResponseOutputMessage): Seq[ResponseOutputMessage.Content] =
    known(item._content()).map(_.asScala.toSeq).getOrElse(Seq.empty)

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
