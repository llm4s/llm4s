package org.llm4s.llmconnect.provider

import org.llm4s.config.ProviderModelListers
import org.llm4s.config.ProvidersConfigModel.{ ApiKey, NamedProviderConfig, ProviderId }
import org.llm4s.error.{ ServiceError, ValidationError }
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.llm4s.testutil.SmallStack
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A deeply nested response body is a `Left`, never a thread killed by `StackOverflowError` (#1658).
 *
 * `.obj` on a non-object throws `ujson.Value.InvalidData`, whose message renders the whole value
 * recursively; a 100,000-deep top-level array overflowed the stack on every path that read a reply
 * that way - the error mapper behind `complete` and `streamComplete`, the 2xx reply and stream
 * events, and the model lister. Each call runs on a 256 KiB thread stack, so an overflow is
 * deterministic and fails the test rather than the suite.
 */
class OpenAICompatibleDeepBodySpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val StackBytes = 256L * 1024L
  private val Depth      = 100000
  private val deepArray  = "[" * Depth + "]" * Depth

  private def onSmallStack[A](body: => A): A =
    SmallStack.run(body, stackBytes = StackBytes) match {
      case Right(value) => value
      case Left(thrown) => fail(s"the call died on a small stack with ${thrown.getClass.getName}", thrown)
    }

  private def client(baseUrl: String) =
    new OpenAICompatibleClient(
      OpenAICompatibleClient.settings(OpenAICompatibleConfig("m", baseUrl, None)),
      OpenAICompatibleDialect.Standard
    )

  private def listModels(body: String) = {
    val provider = ProviderId("deepseek")
    val config   = NamedProviderConfig(provider, ModelName("m"), baseUrl = None, apiKey = Some(ApiKey("k")))
    ProviderModelListers
      .openAICompatible(provider, "https://api.example.test")
      .listModels(config, MockHttpClient(HttpResponse(200, body, Map.empty)))
  }

  private val conversation = Conversation(Seq(UserMessage("hi")))

  private def respondingWith(status: Int, body: String, contentType: String = "application/json")(
    test: String => Any
  ): Unit =
    withServer("/chat/completions") { exchange =>
      if (contentType == "application/json") sendJsonResponse(exchange, status, body)
      else sendSseResponse(exchange, body)
    }(test)

  "OpenAICompatibleClient.complete" should "return a Left for a 400 or 500 whose body is a 100,000-deep array" in {
    Seq(400, 500).foreach { status =>
      respondingWith(status, deepArray) { baseUrl =>
        val result = onSmallStack(client(baseUrl).complete(conversation, CompletionOptions()))
        result.isLeft shouldBe true
        status match {
          case 400 => result.left.toOption.get shouldBe a[ValidationError]
          case _   => result.left.toOption.get shouldBe a[ServiceError]
        }
        result.left.toOption.get.message should include(s"(HTTP $status)")
      }
    }
  }

  it should "return a Left for a 200 whose body is a 100,000-deep array" in {
    respondingWith(200, deepArray) { baseUrl =>
      onSmallStack(client(baseUrl).complete(conversation, CompletionOptions())).isLeft shouldBe true
    }
  }

  "OpenAICompatibleClient.streamComplete" should "return a Left for a 400 or 500 whose body is a 100,000-deep array" in {
    Seq(400, 500).foreach { status =>
      respondingWith(status, deepArray) { baseUrl =>
        val result = onSmallStack(client(baseUrl).streamComplete(conversation, CompletionOptions(), _ => ()))
        result.isLeft shouldBe true
        result.left.toOption.get.message should include(s"(HTTP $status)")
      }
    }
  }

  it should "return a Left for a stream event whose data is a 100,000-deep array" in {
    respondingWith(200, s"data: $deepArray\n\ndata: [DONE]\n\n", contentType = "text/event-stream") { baseUrl =>
      onSmallStack(client(baseUrl).streamComplete(conversation, CompletionOptions(), _ => ())).isLeft shouldBe true
    }
  }

  "ProviderModelListers.openAICompatible" should "return a Left for a 200 whose body is a 100,000-deep array" in {
    onSmallStack(listModels(deepArray)).isLeft shouldBe true
  }

  it should "return a Left for a 200 whose body is a shallow top-level array" in {
    onSmallStack(listModels("[1]")).isLeft shouldBe true
  }

  it should "still list the models of an ordinary body" in {
    val body = """{"data":[{"id":"a"},[1],{"id":"b"}]}"""
    onSmallStack(listModels(body)).map(_.map(_.name.asString)) shouldBe Right(List("a", "b"))
  }
}
