package org.llm4s.llmconnect.provider

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.metrics.MockMetricsCollector

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

    val config = OllamaConfig(
      model = "llama3.1",
      baseUrl = "http://localhost:11434",
      contextWindow = 4096,
      reserveCompletion = 512
    )

    val client = new OllamaClient(config)

    // Access internal method via reflection (test-only)
    // Use getDeclaredMethod with exact parameter types for cross-platform compatibility
    val method = client.getClass.getDeclaredMethod(
      "createRequestBody",
      classOf[Conversation],
      classOf[CompletionOptions],
      java.lang.Boolean.TYPE
    )

    method.setAccessible(true)

    val body = method
      .invoke(
        client,
        conversation,
        CompletionOptions(),
        Boolean.box(false)
      )
      .asInstanceOf[ujson.Obj]

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
