package org.llm4s.springai

import org.llm4s.annotation.Experimental
import org.llm4s.core.safety.DefaultErrorMapper
import org.llm4s.error.LLMError
import org.llm4s.javaapi.LlmException
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  TokenUsage,
  ToolCall
}
import org.llm4s.types.Result
import org.springframework.ai.chat.model.{ ChatModel, ChatResponse }

import java.time.Instant
import java.util.{ Objects, UUID }
import scala.util.Try

/**
 * A Spring AI `ChatModel` as an llm4s [[org.llm4s.llmconnect.LLMClient LLMClient]] (#2278), so that llm4s agents,
 * handoffs and RAG run over the model beans a Spring AI application already configured. From Java,
 * `Llm4s.wrapClient(new SpringAiLlmClient(chatModel))` gives the facade's `JLlmClient`.
 *
 * The conversation's system, user, assistant (with its tool calls) and tool messages become the `Prompt`; the options'
 * temperature, top-p, max tokens, presence and frequency penalties and tools are sent as `ToolCallingChatOptions`. The
 * Spring AI model returns its tool calls, which the llm4s agent runs. The response's id, model, text, tool calls and
 * usage become the [[org.llm4s.llmconnect.model.Completion Completion]]. An exception the model throws, or a `Flux`
 * error while streaming, is a `Left`; an [[org.llm4s.javaapi.LlmException LlmException]] gives back the error it carries.
 *
 * @param contextWindow     the model's context window in tokens, which llm4s's context management reads
 * @param reserveCompletion the tokens kept free for the reply
 */
@Experimental
final class SpringAiLlmClient(chatModel: ChatModel, contextWindow: Int, reserveCompletion: Int) extends LLMClient {

  Objects.requireNonNull(chatModel, "chatModel must not be null")

  /** A client that assumes a 128k-token context window and keeps 4096 tokens for the reply. */
  def this(chatModel: ChatModel) = this(chatModel, 128000, 4096)

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    SpringAiLlmClient
      .attempt(chatModel.call(SpringAiMapping.prompt(conversation, options)))
      .flatMap(SpringAiMapping.completion)

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val acc = new SpringAiLlmClient.Accumulator
    SpringAiLlmClient
      .attempt {
        chatModel
          .stream(SpringAiMapping.prompt(conversation, options))
          .doOnNext(response => acc.add(response).fold(e => throw SpringAiMapping.failure(e), _.foreach(onChunk)))
          .blockLast()
      }
      .map(_ => acc.completion)
  }

  override def getContextWindow(): Int     = contextWindow
  override def getReserveCompletion(): Int = reserveCompletion
}

@Experimental
object SpringAiLlmClient {

  /** The call's result; an llm4s error that crossed Spring AI as an [[LlmException]] comes back as itself. */
  private def attempt[A](call: => A): Result[A] =
    Try(call).toEither.left.map {
      case e: LlmException => e.error
      case t               => DefaultErrorMapper(t): LLMError
    }

  /** A streamed reply put together: the text in order, each tool call once, and the last id, model and usage seen. */
  final private class Accumulator {
    private val text                      = new StringBuilder
    private var calls: Vector[ToolCall]   = Vector.empty
    private var id: Option[String]        = None
    private var model: Option[String]     = None
    private var usage: Option[TokenUsage] = None

    /**
     * The chunks of one streamed response. A response with no generation, as some models send last with the usage
     * alone, adds only its metadata.
     */
    def add(response: ChatResponse): Result[Seq[StreamedChunk]] = {
      val metadata = Option(response.getMetadata)
      id = metadata.flatMap(m => Option(m.getId)).filter(_.nonEmpty).orElse(id)
      model = metadata.flatMap(m => Option(m.getModel)).filter(_.nonEmpty).orElse(model)
      usage = metadata.flatMap(m => Option(m.getUsage)).flatMap(SpringAiMapping.tokenUsage).orElse(usage)
      Option(response.getResult) match {
        case None => Right(Nil)
        case Some(generation) =>
          SpringAiMapping.toolCalls(generation.getOutput).map { received =>
            val piece  = Option(generation.getOutput.getText).getOrElse("")
            val finish = Option(generation.getMetadata).flatMap(m => Option(m.getFinishReason)).filter(_.nonEmpty)
            val fresh  = received.filterNot(c => calls.exists(_.id == c.id))
            text.append(piece)
            calls = calls ++ fresh
            val chunkId = id.getOrElse("")
            val textChunk = Option.when(piece.nonEmpty || finish.nonEmpty)(
              StreamedChunk(chunkId, Option(piece).filter(_.nonEmpty), finishReason = finish)
            )
            textChunk.toSeq ++ fresh.map(c => StreamedChunk(chunkId, None, toolCall = Some(c)))
          }
      }
    }

    def completion: Completion = {
      val content = text.toString
      Completion(
        id = id.getOrElse(UUID.randomUUID().toString),
        created = Instant.now(),
        content = content,
        model = model.getOrElse(""),
        message = AssistantMessage(contentOpt = Option(content).filter(_.nonEmpty), toolCalls = calls),
        toolCalls = calls.toList,
        usage = usage
      )
    }
  }
}
