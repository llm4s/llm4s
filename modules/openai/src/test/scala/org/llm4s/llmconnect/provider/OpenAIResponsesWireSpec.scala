package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIApi, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }

/** `OpenAIClient` on the Responses API over the real `openai-java` transport, against a local HTTP server. */
final class OpenAIResponsesWireSpec extends AnyFlatSpec with Matchers with EitherValues:
  import OpenAIResponsesFixtures.{ reply, message => outputMessage }

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  final private case class Seen(method: String, path: String, authorization: Option[String], body: ujson.Value)

  private def capture(into: AtomicReference[Seen], exchange: HttpExchange): Unit =
    val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
    into.set(
      Seen(
        exchange.getRequestMethod,
        exchange.getRequestURI.getPath,
        Option(exchange.getRequestHeaders.getFirst("Authorization")),
        ujson.read(body)
      )
    )

  private val hello = Conversation(Seq(UserMessage("hello")))

  private def configFor(baseUrl: String, model: String): OpenAIConfig =
    OpenAIConfig.fromValues(ModelName(model), Secret("sk-test"), BaseUrl(s"$baseUrl/v1")).value

  private def responsesClient(baseUrl: String, model: String = "gpt-4o"): OpenAIClient =
    OpenAIClient(configFor(baseUrl, model).withApi(OpenAIApi.Responses)).value

  "OpenAIClient on the Responses API" should "post to <baseUrl>/responses with store false and read the reply" in {
    val seen = new AtomicReference[Seen]()
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(exchange, 200, reply(outputMessage("hi")))
    } { baseUrl =>
      val client = responsesClient(baseUrl)
      val result = client.complete(hello, CompletionOptions(maxTokens = Some(64)))
      client.close()

      val completion = result.value
      completion.content shouldBe "hi"
      completion.created shouldBe Instant.ofEpochSecond(1700000000L)
      completion.usage.map(_.promptTokens) shouldBe Some(9)
      completion.usage.map(_.completionTokens) shouldBe Some(4) // 6 output less 2 reasoning
      completion.usage.flatMap(_.thinkingTokens) shouldBe Some(2)
      completion.usage.map(_.totalTokens) shouldBe Some(15)

      val request = seen.get()
      request.method shouldBe "POST"
      request.path shouldBe "/v1/responses"
      request.authorization shouldBe Some("Bearer sk-test")
      request.body("model").str shouldBe "gpt-4o"
      request.body("store").bool shouldBe false
      request.body("max_output_tokens").num shouldBe 64
      request.body("input")(0)("role").str shouldBe "user"
      request.body("input")(0)("content").str shouldBe "hello"
      request.body.obj.keySet should contain noneOf ("messages", "max_tokens", "previous_response_id", "include")
    }
  }

  it should "post to /chat/completions when api is unset or chat-completions" in {
    List(None, Some(OpenAIApi.ChatCompletions)).foreach { api =>
      val seen = new AtomicReference[Seen]()
      LocalProviderTestServer.withServer("/") { exchange =>
        capture(seen, exchange)
        LocalProviderTestServer.sendJsonResponse(
          exchange,
          200,
          LocalProviderTestServer.openAICompletion("hi", "gpt-4o")
        )
      } { baseUrl =>
        val client = OpenAIClient(configFor(baseUrl, "gpt-4o").withApi(api)).value
        val result = client.complete(hello, CompletionOptions())
        client.close()

        result.value.content shouldBe "hi"
        seen.get().path shouldBe "/v1/chat/completions"
      }
    }
  }

  it should "report a 404 from a gateway without /responses as the provider's service error" in {
    val requests = new AtomicInteger(0)
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes(): Unit
      requests.incrementAndGet(): Unit
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        404,
        """{"error":{"message":"Not found","type":"invalid_request_error"}}"""
      )
    } { baseUrl =>
      val client = responsesClient(baseUrl)
      val error  = client.complete(hello, CompletionOptions()).left.value
      client.close()

      error shouldBe a[ServiceError]
      error.asInstanceOf[ServiceError].status shouldBe 404
      requests.get() shouldBe 1
    }
  }
