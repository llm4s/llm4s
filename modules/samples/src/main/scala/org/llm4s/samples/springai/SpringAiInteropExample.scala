package org.llm4s.samples.springai

import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.springai.{ Llm4sChatModel, SpringAiLlmClient }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.model.{ ChatModel, ChatResponse, Generation }
import org.springframework.ai.chat.prompt.Prompt

/**
 * Spring AI interop in both directions (#2278), with no API key: the llm4s model is `llm4s-testkit`'s scripted client,
 * and the Spring AI model a few lines below. Swap either for a real one - `Llm4s.createDefaultClient()`, or the
 * `ChatModel` bean of a Spring AI starter - and nothing else changes.
 *
 * {{{
 * sbt "samples/runMain org.llm4s.samples.springai.SpringAiInteropExample"
 * }}}
 */
object SpringAiInteropExample extends App {

  // 1. An llm4s client as a Spring AI ChatModel, called through Spring AI's ChatClient.
  val llm4sClient = ScriptedLLMClient.sequence(Reply.text("Paris is the capital of France."))
  val chatClient  = ChatClient.create(new Llm4sChatModel(llm4sClient))
  val answer =
    chatClient.prompt().system("Answer in one sentence.").user("What is the capital of France?").call().content()
  println(s"ChatClient over llm4s: $answer")

  // 2. A Spring AI ChatModel as an llm4s LLMClient: here one that echoes the question.
  val springModel: ChatModel = new ChatModel {
    override def call(prompt: Prompt): ChatResponse =
      new ChatResponse(
        java.util.List.of(new Generation(new AssistantMessage(s"You asked: ${prompt.getUserMessage.getText}"))),
        ChatResponseMetadata.builder().id("echo-1").model("echo-model").build()
      )
  }
  val client = new SpringAiLlmClient(springModel)
  client.complete(
    Conversation(Seq(SystemMessage("Be brief."), UserMessage("What is llm4s?"))),
    CompletionOptions()
  ) match {
    case Right(completion) => println(s"llm4s over Spring AI (${completion.model}): ${completion.content}")
    case Left(error)       => println(s"Failed: ${error.formatted}")
  }
}
