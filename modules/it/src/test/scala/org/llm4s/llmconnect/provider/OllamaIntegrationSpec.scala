package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * Integration tests that verify full round-trip against a running Ollama instance.
 *
 * These tests live in the dedicated integration-test module so default `sbt test`
 * stays fast. Run them with `sbt "it/testOnly org.llm4s.llmconnect.provider.OllamaIntegrationSpec"`
 * or the `sbt testOllama` alias.
 *
 * Each test uses `assume(ollamaAvailable)` to skip gracefully when Ollama is not
 * running. Uses a small model (`qwen2.5:0.5b`) to minimise resource usage.
 *
 * Non-determinism strategy: never assert on specific content text. Only assert
 * structural properties: isRight, nonEmpty, > 0, correct types.
 */
class OllamaIntegrationSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = ModelRegistryService.default().toOption.get

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

  "OllamaClient" should "complete a basic request" in {
    assume(ollamaAvailable, s"Ollama not available with model $testModel")

    withClient { client =>
      val result = client.complete(conversation("Say hi in one word"), CompletionOptions())

      result.isRight shouldBe true
      val completion = result.toOption.get
      completion.content should not be empty
    }
  }

  it should "stream a response with chunks" in {
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

  it should "handle a multi-turn conversation" in {
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

  it should "report token usage" in {
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

  it should "return ConfigurationError after close" in {
    assume(ollamaAvailable, s"Ollama not available with model $testModel")

    val client = new OllamaClient(config)
    val beforeClose = client.complete(conversation("hi"), CompletionOptions())
    beforeClose.isRight shouldBe true

    client.close()

    val afterClose = client.complete(conversation("hi"), CompletionOptions())
    afterClose.isLeft shouldBe true
    afterClose.swap.toOption.get shouldBe a[ConfigurationError]
  }
}
