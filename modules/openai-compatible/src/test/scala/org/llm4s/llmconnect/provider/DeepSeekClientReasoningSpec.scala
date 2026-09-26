package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ReasoningEffort, StreamedChunk, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * DeepSeek's dialect: `deepseek-reasoner` returns its chain of thought as
 * `reasoning_content`, which the old `DeepSeekClient` dropped. It is now `Completion.thinking`,
 * and streamed as thinking deltas (#1132).
 */
class DeepSeekClientReasoningSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def localConfig(baseUrl: String): DeepSeekConfig =
    DeepSeekConfig(
      apiKey = "test-key",
      model = "deepseek-reasoner",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 8192
    )

  private val conversation = Conversation(Seq(UserMessage("why?")))

  "DeepSeekClient.complete" should "read reasoning_content as thinking, and its reasoning tokens" in
    withServer("/chat/completions") { exchange =>
      exchange.getRequestHeaders.getFirst("User-Agent") shouldBe "llm4s/1.0"
      exchange.getRequestHeaders.getFirst("Authorization") shouldBe "Bearer test-key"
      sendJsonResponse(
        exchange,
        200,
        """{"id":"r1","created":1,"model":"deepseek-reasoner",
          |"choices":[{"index":0,"message":{"role":"assistant","content":"Because.","reasoning_content":"Let me think."},"finish_reason":"stop"}],
          |"usage":{"prompt_tokens":5,"completion_tokens":20,"total_tokens":25,"completion_tokens_details":{"reasoning_tokens":12}}}""".stripMargin
      )
    } { baseUrl =>
      val completion = new DeepSeekClient(localConfig(baseUrl)).complete(conversation, CompletionOptions()).value
      completion.content shouldBe "Because."
      completion.thinking shouldBe Some("Let me think.")
      completion.usage.flatMap(_.thinkingTokens) shouldBe Some(12)
    }

  it should "leave thinking empty for deepseek-chat, which sends no reasoning_content" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(exchange, 200, openAICompletion("plain", "deepseek-chat"))
    } { baseUrl =>
      val completion = new DeepSeekClient(localConfig(baseUrl)).complete(conversation, CompletionOptions()).value
      completion.thinking shouldBe None
      completion.usage.flatMap(_.thinkingTokens) shouldBe None
    }

  it should "send no reasoning parameters, since the model name selects reasoning" in {
    val body = new DeepSeekClient(localConfig("http://localhost:1"))
      .createRequestBody(conversation, CompletionOptions().withReasoning(ReasoningEffort.High))
    body.obj.keySet should contain noneOf ("thinking", "reasoning_effort")
  }

  "DeepSeekClient.streamComplete" should "stream reasoning_content as thinking deltas" in
    withServer("/chat/completions") { exchange =>
      val events = Seq(
        """{"id":"s","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"Let me "}}]}""",
        """{"id":"s","choices":[{"index":0,"delta":{"reasoning_content":"think."}}]}""",
        """{"id":"s","choices":[{"index":0,"delta":{"content":"Because."},"finish_reason":"stop"}]}"""
      )
      sendSseResponse(exchange, events.map(e => s"data: $e\n\n").mkString + "data: [DONE]\n\n")
    } { baseUrl =>
      val chunks = ListBuffer.empty[StreamedChunk]
      val completion =
        new DeepSeekClient(localConfig(baseUrl)).streamComplete(conversation, CompletionOptions(), chunks += _).value

      chunks.flatMap(_.thinkingDelta) shouldBe Seq("Let me ", "think.")
      completion.thinking shouldBe Some("Let me think.")
      completion.content shouldBe "Because."
    }
}
