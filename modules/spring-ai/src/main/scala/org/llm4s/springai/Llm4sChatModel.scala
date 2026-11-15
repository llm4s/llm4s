package org.llm4s.springai

import org.llm4s.annotation.Experimental
import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.springai.JavaClients
import org.llm4s.llmconnect.LLMClient
import org.springframework.ai.chat.model.{ ChatModel, ChatResponse, StreamingChatModel }
import org.springframework.ai.chat.prompt.{ ChatOptions, Prompt }
import org.springframework.ai.model.tool.ToolCallingChatOptions
import reactor.core.publisher.Flux

import java.util.Objects
import scala.util.{ Failure, Success, Try }

/**
 * An llm4s [[org.llm4s.llmconnect.LLMClient LLMClient]] as a Spring AI `ChatModel` and `StreamingChatModel` (#2278), so
 * that a Spring AI application's `ChatClient`, advisors and evaluators run over any llm4s provider, a `ReliableClient`
 * or a traced client, with no change to the code that calls them.
 *
 * {{{
 * ChatModel model = Llm4sChatModel.of(Llm4s.createDefaultClient().get());
 * String answer = ChatClient.create(model).prompt().user("What is 2+2?").call().content();
 * }}}
 *
 * A `Prompt`'s system, user, assistant (with its tool calls) and tool-response messages become the llm4s conversation;
 * its options' temperature, top-p, max tokens, presence and frequency penalties and tool definitions are sent. The model
 * is the llm4s client's own, so a model named in the options is not sent; the response's metadata names the model that
 * answered. Stop sequences and top-k, which no llm4s client sends, are refused rather than dropped. The reply's tool
 * calls come back as the `AssistantMessage`'s, with finish reason `TOOL_CALLS`, for Spring AI's tool-calling advisor to
 * run; this model never runs a tool itself.
 *
 * `call` throws an [[org.llm4s.javaapi.LlmException LlmException]] carrying the llm4s error; `stream` signals it as the
 * `Flux`'s error. `stream` emits a response per streamed piece of text, then one with no text that carries the tool calls,
 * finish reason, model and usage. Cancelling the subscription interrupts the call.
 */
@Experimental
final class Llm4sChatModel(client: LLMClient) extends ChatModel with StreamingChatModel {

  Objects.requireNonNull(client, "client must not be null")

  override def call(prompt: Prompt): ChatResponse =
    (for {
      conversation <- SpringAiMapping.conversation(prompt)
      options      <- SpringAiMapping.options(prompt.getOptions)
      completion   <- client.complete(conversation, options)
    } yield SpringAiMapping.chatResponse(completion)).fold(e => throw SpringAiMapping.failure(e), identity)

  override def stream(prompt: Prompt): Flux[ChatResponse] =
    (for {
      conversation <- SpringAiMapping.conversation(prompt)
      options      <- SpringAiMapping.options(prompt.getOptions)
    } yield (conversation, options)) match {
      case Left(error) => Flux.error(SpringAiMapping.failure(error))
      case Right((conversation, options)) =>
        Flux.create[ChatResponse] { sink =>
          val worker = Thread.ofVirtual().unstarted { () =>
            // A client that throws rather than returning a Left must still end the Flux, or its subscriber waits forever.
            Try(
              client.streamComplete(
                conversation,
                options,
                chunk =>
                  chunk.content.filter(_.nonEmpty).foreach(text => sink.next(SpringAiMapping.textChunk(chunk.id, text)))
              )
            ) match {
              case Success(Right(completion)) =>
                sink.next(SpringAiMapping.lastChunk(completion))
                sink.complete()
              case Success(Left(error)) => sink.error(SpringAiMapping.failure(error))
              case Failure(thrown)      => sink.error(thrown)
            }
          }
          // Registered before the call starts, so a subscriber that cancels at once still interrupts it.
          sink.onCancel(() => worker.interrupt())
          worker.start()
        }
    }

  /**
   * No defaults of its own: the llm4s client's configuration applies to whatever a prompt leaves unset. Tool-calling
   * options, so that a `ChatClient` sends the tools it is given and its tool-calling advisor runs them.
   */
  override def getOptions: ChatOptions = ToolCallingChatOptions.builder().build()
}

@Experimental
object Llm4sChatModel {

  /** The model over a Java facade client, such as the `JLlmClient` bean the llm4s Spring Boot starter registers. */
  def of(client: JLlmClient): Llm4sChatModel =
    new Llm4sChatModel(JavaClients.underlying(Objects.requireNonNull(client, "client must not be null")))
}
