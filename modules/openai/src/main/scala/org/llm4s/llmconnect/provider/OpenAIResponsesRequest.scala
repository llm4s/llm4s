package org.llm4s.llmconnect.provider

import com.openai.models.Reasoning
import com.openai.models.responses.{
  EasyInputMessage,
  ResponseCreateParams,
  ResponseInputContent,
  ResponseInputItem,
  ResponseInputText
}
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  CompletionOptions,
  ContentPart,
  Conversation,
  Message,
  SystemMessage,
  UserMessage
}
import org.llm4s.syntax.toLLMError
import org.llm4s.types.Result

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * A conversation and its options as OpenAI's Responses API request (`POST /responses`).
 *
 * Every request sends `store: false`, and no `previous_response_id` or `include`: the conversation travels whole in
 * `input`, and OpenAI keeps nothing. System messages are input messages in their own position, never `instructions`.
 */
private[provider] object OpenAIResponsesRequest:

  /**
   * What the client decides before a request is built.
   *
   * @param model the model id
   * @param providerName the provider, for messages
   * @param effort the `reasoning.effort` to send, from `OpenAIReasoning.plan`
   * @param serviceTier the `service_tier` to send
   * @param omitsSampling whether `temperature` and `top_p` are left out (a reasoning model that restricts sampling)
   */
  final case class Settings(
    model: String,
    providerName: String,
    effort: Option[com.openai.models.ReasoningEffort],
    serviceTier: Option[ResponseCreateParams.ServiceTier],
    omitsSampling: Boolean
  )

  def build(settings: Settings, conversation: Conversation, options: CompletionOptions): Result[ResponseCreateParams] =
    conversation.messages
      .foldLeft[Result[Vector[ResponseInputItem]]](Right(Vector.empty)) { (done, message) =>
        for
          items <- done
          item  <- inputItem(settings, message)
        yield items :+ item
      }
      .flatMap { items =>
        Try {
          val builder = ResponseCreateParams
            .builder()
            .model(settings.model)
            .inputOfResponse(items.asJava)
            .store(false)
          options.maxTokens.foreach(n => builder.maxOutputTokens(n.toLong))
          if !settings.omitsSampling then
            options.temperature.foreach(t => builder.temperature(t))
            options.topP.foreach(p => builder.topP(p))
          settings.effort.foreach(e => builder.reasoning(Reasoning.builder().effort(e).build()))
          settings.serviceTier.foreach(t => builder.serviceTier(t))
          builder.build()
        }.toEither.left.map(_.toLLMError)
      }

  private def inputItem(settings: Settings, message: Message): Result[ResponseInputItem] =
    message match
      case SystemMessage(content) =>
        Right(easy(EasyInputMessage.Role.SYSTEM, _.content(content)))
      case user: UserMessage =>
        textParts(settings, user.parts).map {
          case Seq(single) => easy(EasyInputMessage.Role.USER, _.content(single))
          case texts =>
            val parts = texts.map(t => ResponseInputContent.ofInputText(ResponseInputText.builder().text(t).build()))
            easy(EasyInputMessage.Role.USER, _.contentOfResponseInputMessageContentList(parts.asJava))
        }
      case AssistantMessage(Some(text), Seq(), _, _) =>
        Right(easy(EasyInputMessage.Role.ASSISTANT, _.content(text)))
      case other =>
        unsupported(settings, s"a ${other.getClass.getSimpleName}")

  private def textParts(settings: Settings, parts: Seq[ContentPart]): Result[Seq[String]] =
    parts.foldLeft[Result[Vector[String]]](Right(Vector.empty)) {
      case (done, ContentPart.Text(text)) => done.map(_ :+ text)
      case (done, other) => done.flatMap(_ => unsupported(settings, s"a ${other.getClass.getSimpleName} part"))
    }

  private def easy(role: EasyInputMessage.Role, set: EasyInputMessage.Builder => EasyInputMessage.Builder) =
    ResponseInputItem.ofEasyInputMessage(set(EasyInputMessage.builder().role(role)).build())

  // the rest of the conversation mapping arrives with tool calls, images and documents
  private def unsupported[A](settings: Settings, what: String): Result[A] =
    Left(
      ProcessingError(
        s"${settings.providerName}-responses-request",
        s"${settings.providerName} on the Responses API does not yet send $what"
      )
    )
