package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.model._
import org.scalamock.scalatest.MockFactory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._

class ProviderTimeoutBehaviourSpec extends AnyFunSuite with Matchers with MockFactory {

  private val geminiSuccessBody =
    """{"candidates":[{"content":{"parts":[{"text":"ok"}],"role":"model"}}],
      | "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}""".stripMargin

  private val ollamaSuccessBody = """{"message":{"content":"ok"},"prompt_eval_count":1,"eval_count":1}"""
  private val voyageSuccessBody = """{"data":[{"embedding":[0.1,0.2,0.3]},{"embedding":[0.4,0.5,0.6]}]}"""

  private def sseLine(text: String): String =
    s"""data: {"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}""" + "\n"

  private def ollamaStreamLines(texts: String*): String =
    texts.map(t => s"""{"message":{"content":"$t"},"done":false}\n""").mkString +
      """{"message":{"content":""},"done":true,"prompt_eval_count":1,"eval_count":1}""" + "\n"

  private def conversation(text: String) = Conversation(messages = Seq(UserMessage(text)))
  private def httpOk(body: String)       = HttpResponse(200, body, Map.empty)
  private def streamOk(body: String)     =
    StreamingHttpResponse(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))

  test("GeminiClient.complete() uses custom requestTimeout") {
    val customTimeout = 45.seconds
    val config = GeminiConfig(
      apiKey = "test-key",
      model = "gemini-2.0-flash",
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
      contextWindow = 1048576,
      reserveCompletion = 8192,
      requestTimeout = customTimeout,
    )

    var capturedTimeout = -1
    val mockHttp        = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).onCall { (_: String, _: Map[String, String], _: String, t: Int) =>
      capturedTimeout = t
      httpOk(geminiSuccessBody)
    }

    new GeminiClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
      .complete(conversation("Hi"), CompletionOptions())

    capturedTimeout shouldBe customTimeout.toMillis.toInt
  }

  test("GeminiClient.complete() defaults to 2 minutes") {
    val config = GeminiConfig(
      apiKey = "test-key",
      model = "gemini-2.0-flash",
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
      contextWindow = 1048576,
      reserveCompletion = 8192,
    )

    var capturedTimeout = -1
    val mockHttp        = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).onCall { (_: String, _: Map[String, String], _: String, t: Int) =>
      capturedTimeout = t
      httpOk(geminiSuccessBody)
    }

    new GeminiClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
      .complete(conversation("Hi"), CompletionOptions())

    capturedTimeout shouldBe 120000
  }

  test("GeminiClient.streamComplete() uses custom streamTimeout") {
    val customTimeout = 7.minutes
    val config = GeminiConfig(
      apiKey = "test-key",
      model = "gemini-2.0-flash",
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
      contextWindow = 1048576,
      reserveCompletion = 8192,
      streamTimeout = customTimeout,
    )

    var capturedTimeout = -1
    val mockHttp        = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).onCall { (_: String, _: Map[String, String], _: String, t: Int) =>
      capturedTimeout = t
      streamOk(sseLine("ok"))
    }

    new GeminiClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
      .streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    capturedTimeout shouldBe customTimeout.toMillis.toInt
  }

  test("GeminiClient.streamComplete() defaults to 10 minutes") {
    val config = GeminiConfig(
      apiKey = "test-key",
      model = "gemini-2.0-flash",
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
      contextWindow = 1048576,
      reserveCompletion = 8192,
    )

    var capturedTimeout = -1
    val mockHttp        = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).onCall { (_: String, _: Map[String, String], _: String, t: Int) =>
      capturedTimeout = t
      streamOk(sseLine("ok"))
    }

    new GeminiClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
      .streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    capturedTimeout shouldBe 600000
  }

  test("OllamaClient.complete() uses custom requestTimeout") {
    val customTimeout = 30.seconds
    val config = OllamaConfig(
      model = "llama3",
      baseUrl = "http://localhost:11434",
      contextWindow = 8192,
      reserveCompletion = 4096,
      requestTimeout = customTimeout,
    )

    var capturedTimeout = -1
    val mockHttp        = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).onCall { (_: String, _: Map[String, String], _: String, t: Int) =>
      capturedTimeout = t
      httpOk(ollamaSuccessBody)
    }

    new OllamaClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
      .complete(conversation("Hi"), CompletionOptions())

    capturedTimeout shouldBe customTimeout.toMillis.toInt
  }

  test("OllamaClient.complete() defaults to 2 minutes") {
    val config = OllamaConfig(
      model = "llama3",
      baseUrl = "http://localhost:11434",
      contextWindow = 8192,
      reserveCompletion = 4096,
    )

    var capturedTimeout = -1
    val mockHttp        = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).onCall { (_: String, _: Map[String, String], _: String, t: Int) =>
      capturedTimeout = t
      httpOk(ollamaSuccessBody)
    }

    new OllamaClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
      .complete(conversation("Hi"), CompletionOptions())

    capturedTimeout shouldBe 120000
  }

  test("OllamaClient.streamComplete() uses custom streamTimeout") {
    val customTimeout = 8.minutes
    val config = OllamaConfig(
      model = "llama3",
      baseUrl = "http://localhost:11434",
      contextWindow = 8192,
      reserveCompletion = 4096,
      streamTimeout = customTimeout,
    )

    var capturedTimeout = -1
    val mockHttp        = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).onCall { (_: String, _: Map[String, String], _: String, t: Int) =>
      capturedTimeout = t
      streamOk(ollamaStreamLines("hi"))
    }

    new OllamaClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
      .streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    capturedTimeout shouldBe customTimeout.toMillis.toInt
  }

  test("OllamaClient.streamComplete() defaults to 10 minutes") {
    val config = OllamaConfig(
      model = "llama3",
      baseUrl = "http://localhost:11434",
      contextWindow = 8192,
      reserveCompletion = 4096,
    )

    var capturedTimeout = -1
    val mockHttp        = stub[Llm4sHttpClient]
    (mockHttp.postStream _).when(*, *, *, *).onCall { (_: String, _: Map[String, String], _: String, t: Int) =>
      capturedTimeout = t
      streamOk(ollamaStreamLines("hi"))
    }

    new OllamaClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
      .streamComplete(conversation("Hi"), CompletionOptions(), _ => ())

    capturedTimeout shouldBe 600000
  }

  test("VoyageAIEmbeddingProvider uses custom requestTimeout") {
    val customTimeout = 90.seconds
    val cfg = EmbeddingProviderConfig(
      baseUrl = "https://api.voyageai.com",
      model = "voyage-3",
      apiKey = "test-key",
      requestTimeout = customTimeout,
    )
    val modelCfg = org.llm4s.llmconnect.config.EmbeddingModelConfig("voyage-3", 1024)
    val req      = EmbeddingRequest(Seq("hello"), modelCfg)

    var capturedTimeout = -1
    val mockHttp        = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).onCall { (_: String, _: Map[String, String], _: String, t: Int) =>
      capturedTimeout = t
      httpOk(voyageSuccessBody)
    }

    VoyageAIEmbeddingProvider.forTest(cfg, mockHttp).embed(req)

    capturedTimeout shouldBe customTimeout.toMillis.toInt
  }

  test("VoyageAIEmbeddingProvider defaults to 2 minutes") {
    val cfg = EmbeddingProviderConfig(
      baseUrl = "https://api.voyageai.com",
      model = "voyage-3",
      apiKey = "test-key",
    )
    val modelCfg = org.llm4s.llmconnect.config.EmbeddingModelConfig("voyage-3", 1024)
    val req      = EmbeddingRequest(Seq("hello"), modelCfg)

    var capturedTimeout = -1
    val mockHttp        = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).onCall { (_: String, _: Map[String, String], _: String, t: Int) =>
      capturedTimeout = t
      httpOk(voyageSuccessBody)
    }

    VoyageAIEmbeddingProvider.forTest(cfg, mockHttp).embed(req)

    capturedTimeout shouldBe 120000
  }
}
