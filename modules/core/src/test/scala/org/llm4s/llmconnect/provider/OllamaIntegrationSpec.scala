package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.OllamaRequired
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * Integration tests that verify full round-trip against a running Ollama instance.
 *
 * All tests are tagged [[OllamaRequired]] and excluded from default `sbt test`.
 * Run with: `sbt testOllama`
 *
 * Each test uses `assume(ollamaAvailable)` to skip gracefully when Ollama is not
 * running. Uses a small model (`qwen2.5:0.5b`) to minimise resource usage.
 *
 * Non-determinism strategy: never assert on specific content text. Only assert
 * structural properties: isRight, nonEmpty, > 0, correct types.
 */
class OllamaIntegrationSpec extends AnyFlatSpec with Matchers {

  private val testModel = "qwen2.5:0.5b"
  private val baseUrl   = "http://localhost:11434"

  private val config = OllamaConfig(
    model = testModel,
    baseUrl = baseUrl,
    contextWindow = 8192,
    reserveCompletion = 4096
  )

  /** Check if Ollama is reachable and the test model is available. */
  private lazy val ollamaAvailable: Boolean =
    Try {
      val uri        = java.net.URI.create(s"$baseUrl/api/tags")
      val connection = uri.toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
      connection.setConnectTimeout(3000)
      connection.setReadTimeout(3000)
      connection.setRequestMethod("GET")
      val code = connection.getResponseCode
      if (code == 200) {
        val source = scala.io.Source.fromInputStream(connection.getInputStream)
        try source.mkString.contains(testModel)
        finally source.close()
      } else false
    }.getOrElse(false)

  private def conversation(msg: String): Conversation = Conversation(Seq(UserMessage(msg)))

  /** Runs a test block with a fresh OllamaClient, closing it afterwards. */
  private def withClient[T](f: OllamaClient => T): T = {
    val client = new OllamaClient(config)
    try f(client)
    finally client.close()
  }

  // ==========================================================================
  // Basic round-trip
  // ==========================================================================

  "OllamaClient" should "complete a basic request" taggedAs OllamaRequired in {
    assume(ollamaAvailable, s"Ollama not available with model $testModel")

    withClient { client =>
      val result = client.complete(conversation("Say hi in one word"), CompletionOptions())

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content should not be empty
    }
  }

  // ==========================================================================
  // Streaming round-trip
  // ==========================================================================

  it should "stream a response with chunks" taggedAs OllamaRequired in {
    assume(ollamaAvailable, s"Ollama not available with model $testModel")

    withClient { client =>
      val chunks = scala.collection.mutable.ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(
        conversation("Say hello in one word"),
        CompletionOptions(),
        c => chunks += c
      )

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content should not be empty
      chunks should not be empty
    }
  }

  // ==========================================================================
  // Multi-turn conversation
  // ==========================================================================

  it should "handle a multi-turn conversation" taggedAs OllamaRequired in {
    assume(ollamaAvailable, s"Ollama not available with model $testModel")

    withClient { client =>
      val multiTurnConv = Conversation(
        Seq(
          SystemMessage("You are a helpful assistant. Be very brief."),
          UserMessage("What is 2+2?")
        )
      )
      val result = client.complete(multiTurnConv, CompletionOptions())

      result.isRight shouldBe true
      result.toOption.get.content should not be empty
    }
  }

  // ==========================================================================
  // Token usage reporting
  // ==========================================================================

  it should "report token usage" taggedAs OllamaRequired in {
    assume(ollamaAvailable, s"Ollama not available with model $testModel")

    withClient { client =>
      val result = client.complete(conversation("Say yes"), CompletionOptions())

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.usage shouldBe defined
      completion.usage.get.promptTokens should be > 0
      completion.usage.get.completionTokens should be > 0
    }
  }

  // ==========================================================================
  // Close lifecycle
  // ==========================================================================

  it should "return ConfigurationError after close" taggedAs OllamaRequired in {
    assume(ollamaAvailable, s"Ollama not available with model $testModel")

    val client = new OllamaClient(config)
    // Verify it works before close
    val beforeClose = client.complete(conversation("hi"), CompletionOptions())
    beforeClose.isRight shouldBe true

    client.close()

    val afterClose = client.complete(conversation("hi"), CompletionOptions())
    afterClose.isLeft shouldBe true
    afterClose.swap.toOption.get shouldBe a[ConfigurationError]
  }
}
