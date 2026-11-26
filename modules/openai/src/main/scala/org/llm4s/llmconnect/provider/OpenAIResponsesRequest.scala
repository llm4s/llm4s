package org.llm4s.llmconnect.provider

import com.openai.core.ObjectMappers
import com.openai.models.Reasoning
import com.openai.models.responses.{
  ResponseIncludable,
  EasyInputMessage,
  FunctionTool,
  ResponseCreateParams,
  ResponseFunctionToolCall,
  ResponseInputContent,
  ResponseInputFile,
  ResponseInputImage,
  ResponseInputItem,
  ResponseInputText,
  ResponseTextConfig
}
import org.llm4s.llmconnect.config.OpenAIReasoningSummary
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  CompletionOptions,
  ContentPart,
  Conversation,
  Message,
  RequestAdjustment,
  RequestOption,
  ResponseFormat,
  SystemMessage,
  ToolMessage,
  UserMessage
}
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.syntax.toLLMError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * A conversation and its options as OpenAI's Responses API request (`POST /responses`).
 *
 * Every request sends `store: false`, and no `previous_response_id`, and `include` only to ask a reasoning model for its encrypted reasoning: the conversation travels whole in
 * `input`, and OpenAI keeps nothing. System messages are input messages in their own position, never `instructions`.
 */
private[provider] object OpenAIResponsesRequest:

  /**
   * What the client decides before a request is built.
   *
   * @param model the model id
   * @param provider the provider, whose own uploaded files a document part may name
   * @param effort the `reasoning.effort` to send, from `OpenAIReasoning.plan`
   * @param serviceTier the `service_tier` to send
   * @param omitsSampling whether `temperature` and `top_p` are left out (a reasoning model that restricts sampling)
   * @param reasons whether the request goes to a reasoning model: it then asks for `reasoning.encrypted_content`
   * @param summary the `reasoning.summary` to ask for, sent only when `reasons`
   * @param origin the provider and model whose sealed reasoning this request may carry back
   * @param keepReasoning whether the thread keeps reasoning: when false the request neither asks for the encrypted
   *                      reasoning nor sends back what an earlier turn stored
   */
  final case class Settings(
    model: String,
    provider: ProviderId,
    effort: Option[com.openai.models.ReasoningEffort],
    serviceTier: Option[ResponseCreateParams.ServiceTier],
    omitsSampling: Boolean,
    reasons: Boolean,
    summary: Option[OpenAIReasoningSummary],
    origin: ReplayOrigin,
    keepReasoning: Boolean
  )

  /** OpenAI rejects a `max_output_tokens` below this. */
  val MinOutputTokens = 16

  /**
   * What this request leaves out or changes beyond what the model rules report: a penalty the caller set (this API
   * has none; a reasoning model's are already reported with its other sampling), and a token limit raised to
   * [[MinOutputTokens]].
   */
  def adjustments(options: CompletionOptions, omitsSampling: Boolean): Seq[RequestAdjustment] =
    val penalties =
      if omitsSampling then Nil
      else
        List(
          RequestOption.PresencePenalty  -> options.presencePenalty.isDefined,
          RequestOption.FrequencyPenalty -> options.frequencyPenalty.isDefined
        ).collect { case (option, true) =>
          RequestAdjustment.Dropped(option, "the Responses API has no such parameter")
        }
    val tokens = options.maxTokens.filter(_ < MinOutputTokens).map { asked =>
      RequestAdjustment.Adjusted(
        RequestOption.MaxTokens,
        asked.toString,
        MinOutputTokens.toString,
        s"the Responses API rejects a max_output_tokens below $MinOutputTokens"
      )
    }
    penalties ++ tokens

  def build(settings: Settings, conversation: Conversation, options: CompletionOptions): Result[ResponseCreateParams] =
    PartEncoding
      .check(settings.provider, conversation.messages, userPart(settings), PartEncoding.textOnly)
      .flatMap(_ => items(settings, ThinkingReplay.replayableOrigin(settings.origin, conversation.messages)))
      .flatMap { items =>
        Try {
          val builder = ResponseCreateParams
            .builder()
            .model(settings.model)
            .inputOfResponse(items.asJava)
            .store(false)
          options.maxTokens.foreach(n => builder.maxOutputTokens(math.max(n, MinOutputTokens).toLong))
          if !settings.omitsSampling then
            options.temperature.foreach(t => builder.temperature(t))
            options.topP.foreach(p => builder.topP(p))
          val summary = if settings.reasons then settings.summary else None
          if settings.effort.isDefined || summary.isDefined then
            val reasoning = Reasoning.builder()
            settings.effort.foreach(e => reasoning.effort(e))
            summary.foreach(s => reasoning.summary(reasoningSummary(s)))
            builder.reasoning(reasoning.build())
          if settings.reasons && settings.keepReasoning then
            builder.addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
          settings.serviceTier.foreach(t => builder.serviceTier(t))
          options.tools.map(_.toolDefinition(strict = true)).foreach(t => builder.addTool(functionTool(t)))
          options.responseFormat.foreach(f => builder.text(textConfig(f)))
          builder.build()
        }.toEither.left.map(_.toLLMError)
      }

  private def reasoningSummary(summary: OpenAIReasoningSummary): Reasoning.Summary =
    summary match
      case OpenAIReasoningSummary.Auto     => Reasoning.Summary.AUTO
      case OpenAIReasoningSummary.Concise  => Reasoning.Summary.CONCISE
      case OpenAIReasoningSummary.Detailed => Reasoning.Summary.DETAILED

  private def items(settings: Settings, messages: Seq[Message]): Result[Vector[ResponseInputItem]] =
    // the SDK builders throw on malformed content (a null text): inside the Result
    Try(messages.toVector.flatMap(inputItems(settings, _))).toEither.left.map(_.toLLMError)

  private def inputItems(settings: Settings, message: Message): Vector[ResponseInputItem] =
    message match
      case SystemMessage(content) =>
        Vector(easy(EasyInputMessage.Role.SYSTEM, _.content(content)))
      case user: UserMessage =>
        user.parts match
          case Seq(ContentPart.Text(text)) => Vector(easy(EasyInputMessage.Role.USER, _.content(text)))
          case parts =>
            val mapped = parts.map(userPart(settings))
            Vector(easy(EasyInputMessage.Role.USER, _.contentOfResponseInputMessageContentList(mapped.asJava)))
      case assistant: AssistantMessage => assistantItems(settings, assistant)
      case tool: ToolMessage =>
        Vector(
          ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput
              .builder()
              .callId(tool.toolCallId.asString)
              .output(tool.content)
              .build()
          )
        )

  /**
   * An assistant turn's items, in order: the reasoning items with no anchor, its text, then each tool call preceded by
   * the reasoning items stored with that call's id. A stored item whose call the message no longer has is not sent.
   */
  private def assistantItems(settings: Settings, message: AssistantMessage): Vector[ResponseInputItem] =
    val reasoning  = if settings.keepReasoning then OpenAIReasoningItems.stored(message.thinking) else Seq.empty
    val unanchored = reasoning.filter(_.before.isEmpty).map(s => ResponseInputItem.ofReasoning(s.item))
    val said =
      message.text.filter(_.nonEmpty).map(t => easy(EasyInputMessage.Role.ASSISTANT, _.content(t))).toVector
    val calls = message.toolCalls.flatMap { call =>
      val anchored =
        reasoning.filter(_.before.contains(call.id.asString)).map(s => ResponseInputItem.ofReasoning(s.item))
      anchored :+ ResponseInputItem.ofFunctionCall(
        ResponseFunctionToolCall
          .builder()
          .callId(call.id.asString)
          .name(call.name)
          .arguments(call.arguments.render())
          .build()
      )
    }
    unanchored.toVector ++ said ++ calls

  /** Text, an image by URL or inline, a PDF inline or uploaded to this provider; anything else is refused up front. */
  private def userPart(settings: Settings): PartialFunction[ContentPart, ResponseInputContent] =
    case ContentPart.Text(text) =>
      ResponseInputContent.ofInputText(ResponseInputText.builder().text(text).build())
    case ContentPart.Image(MediaSource.Url(uri), _) if PartEncoding.isWebUrl(uri) =>
      image(uri.toString)
    case ContentPart.Image(inline: MediaSource.Inline, mediaType) =>
      image(PartEncoding.dataUrl(inline, mediaType))
    case ContentPart.Document(inline: MediaSource.Inline, MediaType.Pdf) =>
      file(_.filename("document.pdf").fileData(PartEncoding.dataUrl(inline, MediaType.Pdf)))
    case ContentPart.Document(source @ MediaSource.ProviderFile(_, id), MediaType.Pdf)
        if PartEncoding.isFileOf(settings.provider, source) =>
      file(_.fileId(id))

  private def image(url: String): ResponseInputContent =
    ResponseInputContent.ofInputImage(
      ResponseInputImage.builder().detail(ResponseInputImage.Detail.AUTO).imageUrl(url).build()
    )

  private def file(set: ResponseInputFile.Builder => ResponseInputFile.Builder): ResponseInputContent =
    ResponseInputContent.ofInputFile(set(ResponseInputFile.builder()).build())

  // the flat shape: the Chat Completions definition's `function` object, with its `type` beside it
  private def functionTool(chatDefinition: ujson.Value): FunctionTool =
    val flat = ujson.Obj("type" -> "function")
    chatDefinition("function").obj.foreach((k, v) => flat(k) = v)
    ObjectMappers.jsonMapper().readValue(ujson.write(flat), classOf[FunctionTool])

  private def textConfig(format: ResponseFormat): ResponseTextConfig =
    val json = format match
      case ResponseFormat.Json => ujson.Obj("type" -> "json_object")
      case js: ResponseFormat.JsonSchema =>
        ujson.Obj(
          "type"   -> "json_schema",
          "name"   -> js.name,
          "schema" -> ujson.read(js.schema.render()),
          "strict" -> js.strict
        )
    ObjectMappers.jsonMapper().readValue(ujson.write(ujson.Obj("format" -> json)), classOf[ResponseTextConfig])

  private def easy(role: EasyInputMessage.Role, set: EasyInputMessage.Builder => EasyInputMessage.Builder) =
    ResponseInputItem.ofEasyInputMessage(set(EasyInputMessage.builder().role(role)).build())
