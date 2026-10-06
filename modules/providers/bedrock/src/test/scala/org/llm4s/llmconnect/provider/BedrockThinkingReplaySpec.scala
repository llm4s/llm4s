package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * Reasoning through Converse (#1381): Claude on Bedrock signs its reasoning blocks and needs them
 * back, unchanged, in the assistant turn that made tool calls. The client reads them - streamed or
 * not - onto the returned message and replays the signed and redacted ones.
 */
class BedrockThinkingReplaySpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val redactedBytes = Array[Byte](1, 2, 3, 4)
  private val redacted      = Base64.getEncoder.encodeToString(redactedBytes)

  private val reasoningReply = ujson
    .Obj(
      "output" -> ujson.Obj(
        "message" -> ujson.Obj(
          "role" -> "assistant",
          "content" -> ujson.Arr(
            ujson.Obj(
              "reasoningContent" -> ujson.Obj(
                "reasoningText" -> ujson.Obj("text" -> "The user wants Paris weather.", "signature" -> "sig-1")
              )
            ),
            ujson.Obj("reasoningContent" -> ujson.Obj("redactedContent" -> redacted)),
            ujson.Obj(
              "toolUse" -> ujson.Obj(
                "toolUseId" -> "tc-1",
                "name"      -> "get_weather",
                "input"     -> ujson.Obj("city" -> "Paris")
              )
            )
          )
        )
      ),
      "stopReason" -> "tool_use",
      "usage"      -> ujson.Obj("inputTokens" -> 10, "outputTokens" -> 8, "totalTokens" -> 18)
    )
    .render()

  "a Converse response" should {
    "put its reasoning blocks on the message, with signature and redacted content" in {
      withServer("/")(ex => sendJsonResponse(ex, 200, reasoningReply)) { url =>
        val client = new BedrockClient(config(url))
        val c      = client.complete(Conversation(Seq(UserMessage("Weather?"))), CompletionOptions()).toOption.value
        client.close()
        c.message.thinking shouldBe Seq(
          ThinkingBlock.Text("The user wants Paris weather.", Some("sig-1")),
          ThinkingBlock.Redacted(redacted)
        )
        c.thinking shouldBe Some("The user wants Paris weather.")
      }
    }
  }

  "the follow-up request" should {
    "send the reasoning back first in the tool-call turn, signed and redacted blocks unchanged" in {
      val seen = new ConcurrentLinkedQueue[ujson.Value]()
      withServer("/") { ex =>
        seen.add(ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
        sendJsonResponse(ex, 200, if (seen.size == 1) reasoningReply else converseResponse("Sunny."))
      } { url =>
        val client = new BedrockClient(config(url))
        val ask    = UserMessage("Weather?")
        val first  = client.complete(Conversation(Seq(ask)), CompletionOptions()).toOption.value
        val again  = Conversation(Seq(ask, first.message, ToolMessage("sunny", "tc-1")))
        client.complete(again, CompletionOptions()).isRight shouldBe true
        client.close()

        val turn = seen.asScala.toList(1)("messages")(1)
        turn("role").str shouldBe "assistant"
        val blocks = turn("content").arr
        blocks(0)("reasoningContent")("reasoningText")("text").str shouldBe "The user wants Paris weather."
        blocks(0)("reasoningContent")("reasoningText")("signature").str shouldBe "sig-1"
        blocks(1)("reasoningContent")("redactedContent").str shouldBe redacted
        blocks(2).obj.contains("toolUse") shouldBe true
      }
    }

    "leave out unsigned reasoning from another provider" in {
      val seen = new ConcurrentLinkedQueue[ujson.Value]()
      withServer("/") { ex =>
        seen.add(ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
        sendJsonResponse(ex, 200, converseResponse("ok"))
      } { url =>
        val client = new BedrockClient(config(url))
        val conv = Conversation(
          Seq(UserMessage("hi"), AssistantMessage("Hello.").withThinking("unsigned"), UserMessage("again"))
        )
        client.complete(conv, CompletionOptions()).isRight shouldBe true
        client.close()
        seen.peek()("messages")(1)("content").arr.map(_.obj.keySet.head) shouldBe Seq("text")
      }
    }
  }

  "a ConverseStream response" should {
    "assemble each reasoning block with its signature on the message" in {
      def delta(index: Int, inner: ujson.Obj) =
        eventFrame("contentBlockDelta", ujson.Obj("contentBlockIndex" -> index, "delta" -> inner).render())
      val frames = Seq(
        eventFrame("messageStart", """{"role":"assistant"}"""),
        delta(0, ujson.Obj("reasoningContent" -> ujson.Obj("text" -> "think "))),
        delta(0, ujson.Obj("reasoningContent" -> ujson.Obj("text" -> "more"))),
        delta(0, ujson.Obj("reasoningContent" -> ujson.Obj("signature" -> "sig-s"))),
        eventFrame("contentBlockStop", """{"contentBlockIndex":0}"""),
        delta(1, ujson.Obj("reasoningContent" -> ujson.Obj("redactedContent" -> redacted))),
        eventFrame("contentBlockStop", """{"contentBlockIndex":1}"""),
        delta(2, ujson.Obj("text" -> "answer")),
        eventFrame("contentBlockStop", """{"contentBlockIndex":2}"""),
        eventFrame("messageStop", """{"stopReason":"end_turn"}""")
      )
      withServer("/")(sendEventStream(_, frames)) { url =>
        val client = new BedrockClient(config(url))
        val c = client
          .streamComplete(Conversation(Seq(UserMessage("Hello"))), CompletionOptions(), _ => ())
          .toOption
          .value
        client.close()
        c.message.thinking shouldBe Seq(
          ThinkingBlock.Text("think more", Some("sig-s")),
          ThinkingBlock.Redacted(redacted)
        )
        c.thinking shouldBe Some("think more")
        c.content shouldBe "answer"
      }
    }
  }
}
