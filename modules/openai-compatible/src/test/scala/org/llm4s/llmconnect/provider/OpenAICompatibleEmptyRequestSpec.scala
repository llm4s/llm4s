package org.llm4s.llmconnect.provider

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.config.{ DeepSeekConfig, MistralConfig, OpenAIConfig, ZaiConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.{ LLMClient, ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

/**
 * The empty-conversation check runs on the messages that will actually be sent.
 *
 * PR #1210 review: the check looked at the conversation, but Mistral's dialect then drops
 * assistant turns with neither text nor tool calls. A conversation made only of such turns
 * passed, went out as `"messages": []` and made a network call that could only fail; the old
 * Mistral client returned a local `ValidationError`. Dialects that send empty assistant turns
 * are unaffected: for them the conversation is the request.
 */
class OpenAICompatibleEmptyRequestSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val onlyEmptyAssistantTurns =
    Conversation(Seq(AssistantMessage(None, Seq.empty), AssistantMessage(Some(""), Seq.empty)))

  /** Runs `call` against a local server, returning its result, the requests the server saw and the exchanges logged. */
  private def against[A](
    path: String,
    reply: com.sun.net.httpserver.HttpExchange => Unit
  )(call: (String, ProviderExchangeLogging) => A): (A, Int, Seq[ProviderExchange]) = {
    val hits     = new AtomicInteger
    val recorded = ListBuffer.empty[ProviderExchange]
    val logging = ProviderExchangeLogging.enabled(
      new ProviderExchangeSink:
        override def record(exchange: ProviderExchange): Unit = recorded += exchange
    )
    var result: Option[A] = None
    withServer(path) { exchange =>
      hits.incrementAndGet()
      reply(exchange)
    }(baseUrl => result = Some(call(baseUrl, logging)))
    (result.get, hits.get, recorded.toSeq)
  }

  private def mistral(baseUrl: String, logging: ProviderExchangeLogging): LLMClient =
    new MistralClient(MistralConfig("k", "mistral-small-latest", baseUrl, 128000, 4096), exchangeLogging = logging)

  "a Mistral complete" should "fail locally when every message would be dropped, without a request" in {
    val (result, hits, recorded) =
      against("/v1/chat/completions", sendJsonResponse(_, 200, openAICompletion("never"))) { (url, logging) =>
        mistral(url, logging).complete(onlyEmptyAssistantTurns, CompletionOptions())
      }

    result.left.value shouldBe a[ValidationError]
    result.left.value.message should include("Mistral requires at least one message")
    hits shouldBe 0
    recorded should have size 1
    recorded.head.errorMessage shouldBe defined
  }

  "a Mistral streamComplete" should "fail locally when every message would be dropped, without a request" in {
    var chunks = 0
    val (result, hits, recorded) =
      against("/v1/chat/completions", sendSseResponse(_, openAISseBody(Seq("never")))) { (url, logging) =>
        mistral(url, logging).streamComplete(onlyEmptyAssistantTurns, CompletionOptions(), _ => chunks += 1)
      }

    result.left.value shouldBe a[ValidationError]
    result.left.value.message should include("Mistral requires at least one message")
    hits shouldBe 0
    chunks shouldBe 0
    recorded should have size 1
  }

  "a Mistral request" should "still go out when an empty assistant turn sits beside a real message" in {
    val (result, hits, _) =
      against("/v1/chat/completions", sendJsonResponse(_, 200, openAICompletion("ok"))) { (url, logging) =>
        mistral(url, logging).complete(
          Conversation(AssistantMessage(None, Seq.empty) +: Seq(UserMessage("hi"))),
          CompletionOptions()
        )
      }

    result.map(_.content) shouldBe Right("ok")
    hits shouldBe 1
  }

  "the dialects that send empty assistant turns" should "still send a conversation made only of them" in {
    // The standard behaviour, unchanged by the fix: DeepSeek, Z.ai, OpenRouter and the generic
    // provider send such a turn (as {"role":"assistant"}), so the conversation is not empty.
    val clients: Seq[(String, (String, ProviderExchangeLogging) => LLMClient)] = Seq(
      "deepseek" -> ((url, logging) =>
        new DeepSeekClient(DeepSeekConfig("k", "deepseek-chat", url, 128000, 8192), exchangeLogging = logging)
      ),
      "zai" -> ((url, logging) =>
        new ZaiClient(ZaiConfig("k", "GLM-4.7", url, 128000, 4096), exchangeLogging = logging)
      ),
      "openrouter" -> ((url, logging) =>
        new OpenRouterClient(
          OpenAIConfig("k", "openai/gpt-4o-mini", None, url, 128000, 4096),
          exchangeLogging = logging
        )
      )
    )
    clients.foreach { (name, build) =>
      withClue(name) {
        val (result, hits, _) =
          against("/chat/completions", sendJsonResponse(_, 200, openAICompletion("ok"))) { (url, logging) =>
            build(url, logging).complete(onlyEmptyAssistantTurns, CompletionOptions())
          }
        result.map(_.content) shouldBe Right("ok")
        hits shouldBe 1
      }
    }
  }

  "any dialect" should "fail an empty conversation locally, on both paths" in {
    val (results, hits, _) =
      against("/chat/completions", sendJsonResponse(_, 200, openAICompletion("never"))) { (url, logging) =>
        val c = new DeepSeekClient(DeepSeekConfig("k", "deepseek-chat", url, 128000, 8192), exchangeLogging = logging)
        (
          c.complete(Conversation(Seq.empty), CompletionOptions()),
          c.streamComplete(Conversation(Seq.empty), CompletionOptions(), _ => ())
        )
      }

    results._1.left.value shouldBe a[ValidationError]
    results._2.left.value shouldBe a[ValidationError]
    hits shouldBe 0
  }
}
