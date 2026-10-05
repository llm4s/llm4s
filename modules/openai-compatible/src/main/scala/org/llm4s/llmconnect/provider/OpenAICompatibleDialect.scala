package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model.{ CompletionOptions, ResponseFormat, ResponseFormatMapper, ToolCall }

import scala.util.Try

/**
 * The places where an OpenAI-compatible provider departs from the plain
 * `/chat/completions` wire format.
 *
 * [[OpenAICompatibleClient]] holds everything the providers share - request
 * building, the HTTP round trip, SSE streaming, tool-call fan-out, error
 * mapping, exchange logging - written once. A dialect answers the handful of
 * questions on which they differ, and every answer defaults to the standard
 * format, so a provider overrides only where it departs from it:
 *
 *  - '''Request:''' extra [[headers]]; the role system messages go under
 *    ([[systemRole]]); how message text is encoded ([[encodeContent]]); whether an assistant turn with no text still sends
 *    `content` ([[alwaysSendAssistantContent]]) or is sent at all
 *    ([[sendEmptyAssistantTurns]]); the tool-call ids the provider
 *    accepts ([[encodeToolCallId]]); the `response_format` shape
 *    ([[encodeResponseFormat]]); whether a stream asks for usage
 *    ([[streamUsageOption]]); and any reasoning fields ([[addReasoning]]).
 *  - '''Response:''' how `content` is read back ([[decodeContent]]); where the
 *    model's thinking is ([[thinking]]); where its reasoning-token count is
 *    ([[reasoningTokens]]); and how a non-streaming `tool_calls` array is
 *    parsed ([[parseToolCalls]]).
 *
 * The generic `openai-compatible` provider uses [[OpenAICompatibleDialect.standard]],
 * which overrides only headers and streamed usage, both from config. DeepSeek, Z.ai, OpenRouter, Mistral and Cohere
 * each override two to six members. A new OpenAI-compatible provider whose
 * differences fit these members is a dialect and a descriptor, not a client;
 * one whose differences do not should extend this trait rather than fork
 * [[OpenAICompatibleClient]] ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
@Stable
trait OpenAICompatibleDialect:

  /**
   * Headers sent on every request, after `Content-Type` and - when the
   * provider has an API key - `Authorization: Bearer <key>`.
   */
  def headers: Seq[(String, String)] = Seq.empty

  /**
   * The role a [[org.llm4s.llmconnect.model.SystemMessage]] is sent under. Standard:
   * `"system"`. Cohere's Compatibility API documents system instructions under `"developer"`.
   */
  def systemRole: String = "system"

  /** Encodes the text of a user, system, assistant or tool message. Standard: a JSON string. */
  def encodeContent(text: String): ujson.Value = ujson.Str(text)

  /**
   * Whether an assistant message with no text still carries a `content` field
   * (`""` for empty text, `null` for none). Standard: `false` - the field is
   * omitted, which is what most providers expect alongside `tool_calls`.
   */
  def alwaysSendAssistantContent: Boolean = false

  /**
   * Whether an assistant turn with neither text nor tool calls is sent at all. Standard:
   * `true` - it goes out as `{"role": "assistant"}` (with `content` if
   * [[alwaysSendAssistantContent]]). A provider that rejects a message carrying neither
   * `content` nor `tool_calls`, as Mistral does, answers `false` and the turn is left out.
   */
  def sendEmptyAssistantTurns: Boolean = true

  /**
   * The id sent for a tool call - on an assistant turn's `tool_calls` and on the `tool`
   * message answering it. Standard: the id as the model produced it.
   *
   * A provider that accepts only its own id format overrides this. It must map the same id to
   * the same result every time, since the call and its answer are encoded separately and must
   * still match; ids that came from another provider in the same conversation go through it too.
   */
  def encodeToolCallId(id: String): String = id

  /**
   * The `response_format` sent for `CompletionOptions.responseFormat`, or `None` to send none.
   * Standard: OpenAI's shapes - `{"type": "json_object"}`, and `{"type": "json_schema",
   * "json_schema": {"name", "strict", "schema"}}` for a schema.
   */
  def encodeResponseFormat(format: ResponseFormat): Option[ujson.Value] =
    ResponseFormatMapper.toOpenAIResponseFormat(format)

  /**
   * Whether a streaming request asks for token usage with
   * `"stream_options": {"include_usage": true}`. Standard: `true`.
   *
   * OpenAI streams usage only when asked this way, and servers that follow it - vLLM, Ollama's
   * `/v1`, Perplexity Router - stream none otherwise. A provider that rejects the field (Mistral
   * answers an unknown field with a 422), does not document it, or always reports usage without
   * it answers `false`. Usage is read from whichever event carries it either way, including a
   * final event with empty `choices`.
   */
  def streamUsageOption: Boolean = true

  /**
   * Adds provider-specific reasoning fields to a request body, given the
   * configured model and the caller's options. Standard: adds nothing, so
   * `CompletionOptions.reasoning` is ignored.
   */
  def addReasoning(body: ujson.Obj, model: String, options: CompletionOptions): Unit = ()

  /**
   * Reads the text of a reply's `content` value - on a completion's `message`
   * or a stream's `delta`. Standard: a JSON string, anything else is no text.
   */
  def decodeContent(content: ujson.Value): Option[String] = content.strOpt

  /**
   * Reads the model's thinking from a JSON object: a completion's `message`
   * (then, if that has none, its enclosing `choice`), or a stream's `delta`.
   * Standard: none.
   */
  def thinking(obj: ujson.Value): Option[String] = None

  /** Reads the reasoning-token count from a `usage` object. Standard: none. */
  def reasoningTokens(usage: ujson.Value): Option[Int] = None

  /**
   * Parses the `tool_calls` array of a non-streaming reply.
   *
   * Standard: [[OpenAICompatibleDialect.lenientToolCalls]], which fills in
   * missing fields and turns unparseable arguments into `{}`. Streamed tool
   * calls always go through `StreamingToolArgumentParser`, which keeps
   * unparseable arguments as a raw string.
   */
  def parseToolCalls(toolCalls: ujson.Value): Seq[ToolCall] = OpenAICompatibleDialect.lenientToolCalls(toolCalls)

object OpenAICompatibleDialect:

  /** The plain OpenAI chat-completions format, with no extra headers. */
  val Standard: OpenAICompatibleDialect = standard()

  /**
   * The plain OpenAI chat-completions format, sending `extraHeaders` on every
   * request and asking for streamed usage when `streamUsage` is set. This is the
   * whole of the generic `openai-compatible` provider's dialect: both come from
   * config.
   */
  def standard(extraHeaders: Seq[(String, String)] = Seq.empty, streamUsage: Boolean = true): OpenAICompatibleDialect =
    new OpenAICompatibleDialect:
      override val headers: Seq[(String, String)] = extraHeaders
      override val streamUsageOption: Boolean     = streamUsage

  /**
   * Parses a `tool_calls` array without failing: a missing `id` or `name`
   * becomes `""`, and missing or unparseable `arguments` become `{}`.
   */
  def lenientToolCalls(toolCalls: ujson.Value): Seq[ToolCall] =
    toolCalls.arrOpt.toSeq.flatten.map { call =>
      val function = call.obj.get("function").flatMap(_.objOpt)
      val argsStr  = function.flatMap(_.get("arguments")).flatMap(_.strOpt).getOrElse("{}")
      ToolCall(
        id = call.obj.get("id").flatMap(_.strOpt).getOrElse(""),
        name = function.flatMap(_.get("name")).flatMap(_.strOpt).getOrElse(""),
        arguments = Try(ujson.read(argsStr)).getOrElse(ujson.Obj())
      )
    }

  /** Reads the first of `fields` present on `obj` as a string. */
  private[provider] def firstString(obj: ujson.Value, fields: String*): Option[String] =
    obj.objOpt.flatMap(o => fields.iterator.flatMap(f => o.get(f).flatMap(_.strOpt)).nextOption())
