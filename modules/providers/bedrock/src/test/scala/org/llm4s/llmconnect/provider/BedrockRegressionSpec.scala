package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.BedrockCredentials
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer

/**
 * Regressions for defects found in review: Converse needs alternating roles (parallel tool results
 * and consecutive same-role messages were sent as separate turns), `topP` was dropped, and a
 * stream that ended without `messageStop` was returned as a clean completion.
 */
class BedrockRegressionSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def text(s: String) = ujson.Obj("text" -> s)

  private def requestBody(conv: Conversation, options: CompletionOptions = CompletionOptions()): ujson.Value = {
    val seen = new AtomicReference[ujson.Value](ujson.Null)
    withServer("/") { ex =>
      seen.set(ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
      sendJsonResponse(ex, 200, converseResponse("ok"))
    } { url =>
      val client = new BedrockClient(config(url))
      client.complete(conv, options).isRight shouldBe true
      client.close()
    }
    seen.get
  }

  "Converse message mapping" should {

    "send two parallel tool results, and the follow-up user text, as one user turn" in {
      val args = ujson.Obj("city" -> "Paris", "days" -> 3)
      val conv = Conversation(
        Seq(
          SystemMessage("You are terse."),
          UserMessage("Weather and time?"),
          AssistantMessage(
            Some("Checking."),
            Seq(ToolCall("tc-1", "get_weather", args), ToolCall("tc-2", "get_time", ujson.Obj()))
          ),
          ToolMessage(toolCallId = "tc-1", content = """{"temp":22}"""),
          ToolMessage(toolCallId = "tc-2", content = "noon"),
          UserMessage("Thanks!")
        )
      )

      val body = requestBody(conv)

      body("system") shouldBe ujson.Arr(text("You are terse."))
      body("messages") shouldBe ujson.Arr(
        ujson.Obj("role" -> "user", "content" -> ujson.Arr(text("Weather and time?"))),
        ujson.Obj(
          "role" -> "assistant",
          "content" -> ujson.Arr(
            text("Checking."),
            ujson.Obj("toolUse" -> ujson.Obj("toolUseId" -> "tc-1", "name" -> "get_weather", "input" -> args)),
            ujson.Obj("toolUse" -> ujson.Obj("toolUseId" -> "tc-2", "name" -> "get_time", "input" -> ujson.Obj()))
          )
        ),
        ujson.Obj(
          "role" -> "user",
          "content" -> ujson.Arr(
            ujson.Obj(
              "toolResult" -> ujson.Obj("toolUseId" -> "tc-1", "content" -> ujson.Arr(text("""{"temp":22}""")))
            ),
            ujson.Obj("toolResult" -> ujson.Obj("toolUseId" -> "tc-2", "content" -> ujson.Arr(text("noon")))),
            text("Thanks!")
          )
        )
      )
    }

    "merge consecutive user messages, and consecutive assistant messages, into single turns" in {
      val conv = Conversation(
        Seq(
          UserMessage("one"),
          UserMessage("two"),
          AssistantMessage("a1"),
          AssistantMessage("a2"),
          UserMessage("three")
        )
      )
      val messages = requestBody(conv)("messages").arr
      messages.map(_("role").str).toSeq shouldBe Seq("user", "assistant", "user")
      messages(0)("content") shouldBe ujson.Arr(text("one"), text("two"))
      messages(1)("content") shouldBe ujson.Arr(text("a1"), text("a2"))
    }

    "close the turns correctly when an empty assistant message sits between two user messages" in {
      val conv     = Conversation(Seq(UserMessage("one"), AssistantMessage(None, Nil), UserMessage("two")))
      val messages = requestBody(conv)("messages").arr
      messages.map(_("role").str).toSeq shouldBe Seq("user")
      messages(0)("content") shouldBe ujson.Arr(text("one"), text("two"))
    }
  }

  "Converse inference configuration" should {

    "send top-p when it is set, and omit it at the default" in {
      val conv = Conversation(Seq(UserMessage("hi")))

      val tuned = requestBody(conv, CompletionOptions(temperature = 0.25, topP = 0.5, maxTokens = Some(321)))
      tuned("inferenceConfig")("maxTokens").num shouldBe 321.0
      tuned("inferenceConfig")("temperature").num shouldBe 0.25
      tuned("inferenceConfig")("topP").num shouldBe 0.5

      val defaults = requestBody(conv, CompletionOptions())
      defaults("inferenceConfig").obj.contains("topP") shouldBe false
      defaults("inferenceConfig").obj.contains("maxTokens") shouldBe false
    }
  }

  "ConverseStream" should {

    "report a stream that ends before messageStop as an error, not a complete answer" in {
      val frames = Seq(
        eventFrame(
          "contentBlockDelta",
          """{"contentBlockIndex":0,"delta":{"text":"trunc"}}"""
        )
      )
      withServer("/")(sendEventStream(_, frames)) { url =>
        val client = new BedrockClient(config(url))
        val chunks = ListBuffer.empty[StreamedChunk]
        val result = client.streamComplete(Conversation(Seq(UserMessage("Hello"))), CompletionOptions(), chunks += _)
        client.close()
        chunks.flatMap(_.content).toList shouldBe List("trunc")
        result.left.toOption.value.message should include("messageStop")
      }
    }
  }

  "credential handling" should {

    "report a missing credential as an authentication problem, not a retryable network fault" in {
      val called = new java.util.concurrent.atomic.AtomicBoolean(false)
      withServer("/") { ex =>
        called.set(true); sendJsonResponse(ex, 200, converseResponse("never"))
      } { url =>
        val client = new BedrockClient(config(url).copy(credentials = None, profile = Some("llm4s-no-such-profile")))
        val conv   = Conversation(Seq(UserMessage("Hello")))
        val sync   = client.complete(conv, CompletionOptions()).left.toOption.value
        val stream = client.streamComplete(conv, CompletionOptions(), _ => ()).left.toOption.value
        client.close()
        sync shouldBe a[org.llm4s.error.AuthenticationError]
        stream shouldBe a[org.llm4s.error.AuthenticationError]
      }
      called.get() shouldBe false
    }

    "treat a blank session token (an empty AWS_SESSION_TOKEN) as absent, so the request still signs" in {
      val token = new AtomicReference[Option[String]](Some("unset"))
      withServer("/") { ex =>
        token.set(Option(ex.getRequestHeaders.getFirst("x-amz-security-token")))
        sendJsonResponse(ex, 200, converseResponse("ok"))
      } { url =>
        val cfg    = config(url).copy(credentials = Some(BedrockCredentials("AKID", "secret", Some(""))))
        val client = BedrockClient(cfg).toOption.value
        client.complete(Conversation(Seq(UserMessage("Hello"))), CompletionOptions()).isRight shouldBe true
        client.close()
      }
      token.get shouldBe None
    }
  }
}
