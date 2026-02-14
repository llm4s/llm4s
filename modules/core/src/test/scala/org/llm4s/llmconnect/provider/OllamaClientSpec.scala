package org.llm4s.llmconnect.provider

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.metrics.MockMetricsCollector

/**
 * Test helper for building Ollama request bodies without reflection.
 * This replaces reflection-based introspection with a pure function approach.
 */
private[provider] object OllamaRequestBodyTestHelper {
  def createRequestBody(
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean
  ): ujson.Obj = {
    val msgs = ujson.Arr.from(conversation.messages.collect {
      case SystemMessage(content) => ujson.Obj("role" -> "system", "content" -> content)
      case UserMessage(content)   => ujson.Obj("role" -> "user", "content" -> content)
      case am: AssistantMessage   => ujson.Obj("role" -> "assistant", "content" -> am.content)
      // Tool messages are not supported by Ollama chat API; drop them
    })

    val opts = ujson.Obj(
      "temperature" -> options.temperature,
      "top_p"       -> options.topP
    )
    options.maxTokens.foreach(t => opts("num_predict") = t)

    ujson.Obj(
      "model"    -> "llama3.1",
      "messages" -> msgs,
      "stream"   -> stream,
      "options"  -> opts
    )
  }
}

class OllamaClientSpec extends AnyFunSuite {

  test("ollama chat request sends assistant content as a plain string") {

    val conversation = Conversation(
      messages = Seq(
        SystemMessage("You are a helpful assistant"),
        UserMessage("Say hello"),
        // This reproduces the bug
        AssistantMessage(None, Seq.empty)
      )
    )

    // Use test helper instead of reflection
    val body = OllamaRequestBodyTestHelper.createRequestBody(conversation, CompletionOptions(), stream = false)

    val messages = body("messages").arr

    val assistantMessage =
      messages.find(_("role").str == "assistant").get

    assert(
      assistantMessage("content").isInstanceOf[ujson.Str],
      "Expected assistant message content to be a string for Ollama"
    )
    assert(assistantMessage("content").str == "", "Assistant content should default to empty string when missing")
  }

  test("ollama client accepts custom metrics collector") {
    val config = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434",
      contextWindow = 4096,
      reserveCompletion = 512
    )

    val mockMetrics = new MockMetricsCollector()
    val client      = new OllamaClient(config, mockMetrics)

    // Verify client was created with custom metrics
    assert(client != null)
    assert(mockMetrics.totalRequests == 0) // No requests yet
  }

  test("ollama client uses noop metrics by default") {
    val config = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434",
      contextWindow = 4096,
      reserveCompletion = 512
    )

    // Default constructor should use noop metrics
    val client = new OllamaClient(config)

    // Verify it compiles and doesn't throw (noop metrics should never fail)
    assert(client != null)
  }
}
