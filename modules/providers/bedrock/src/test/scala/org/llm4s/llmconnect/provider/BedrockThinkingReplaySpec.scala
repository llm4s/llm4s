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

  /** The request body Converse receives for `messages`. */
  private def sentMessages(messages: Message*): Seq[ujson.Value] = {
    val seen = new ConcurrentLinkedQueue[ujson.Value]()
    withServer("/") { ex =>
      seen.add(ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
      sendJsonResponse(ex, 200, converseResponse("ok"))
    } { url =>
      val client = new BedrockClient(config(url))
      client.complete(Conversation(messages), CompletionOptions()).isRight shouldBe true
      client.close()
    }
    seen.peek()("messages").arr.toSeq
  }

  private def weather = ToolCall("tc-1", "get_weather", ujson.Obj("city" -> "Paris"))

  /** `message` as the client returns it in answer to `history`: its sealed thinking bound to it. */
  private def answering(history: Message*)(message: AssistantMessage): AssistantMessage =
    ThinkingReplay.bind(ReplayOrigin("bedrock", Model), message, history, CompletionOptions())

  "tool results" should {
    "not pair with a call that a user message separates them from: the call is left out, the result sent as text" in {
      val messages = sentMessages(
        UserMessage("Weather?"),
        AssistantMessage(Some("Checking."), Seq(weather)),
        UserMessage("Still there?"),
        ToolMessage("sunny", "tc-1")
      )
      val rendered = messages.map(_.render()).mkString
      (rendered should not).include("toolUse")
      (rendered should not).include("toolResult")
      messages.map(_("role").str) shouldBe Seq("user", "assistant", "user")
      messages(2)("content").arr.map(_("text").str) shouldBe Seq("Still there?", "[Tool result for tc-1]: sunny")
    }

    "come first in their user turn, before an unpaired result's text" in {
      val time = ToolCall("tc-2", "get_time", ujson.Obj())
      val messages = sentMessages(
        UserMessage("Weather, then time?"),
        AssistantMessage(None, Seq(weather)),
        ToolMessage("sunny", "tc-1"),
        AssistantMessage(None, Seq(time)),
        ToolMessage("late duplicate", "tc-1"),
        ToolMessage("noon", "tc-2")
      )
      messages.map(_("role").str) shouldBe Seq("user", "assistant", "user", "assistant", "user")
      val last = messages(4)("content").arr
      last.map(_.obj.keySet.head) shouldBe Seq("toolResult", "text")
      last(0)("toolResult")("toolUseId").str shouldBe "tc-2"
      last(1)("text").str shouldBe "[Tool result for tc-1]: late duplicate"
    }
  }

  "a signed tool-call turn" should {
    val time = ToolCall("tc-2", "get_time", ujson.Obj())
    val sealedThinking =
      Seq(ThinkingBlock.Text("Weather and time.", Some("sig-pair")), ThinkingBlock.Redacted(redacted))

    "be unsealed, with only its paired call, when one of its calls is unpaired" in {
      val messages = sentMessages(
        UserMessage("Weather and time?"),
        AssistantMessage(Some("Checking."), Seq(weather, time)).withThinking(sealedThinking),
        ToolMessage("sunny", "tc-1")
      )
      val turn = messages(1)
      turn("role").str shouldBe "assistant"
      turn("content").arr.map(_.obj.keySet.head) shouldBe Seq("text", "toolUse")
      turn("content")(1)("toolUse")("toolUseId").str shouldBe "tc-1"
      (turn.render() should not).include("reasoningContent")
    }

    "keep its signed and redacted reasoning when every call is paired" in {
      val ask = UserMessage("Weather and time?")
      val messages = sentMessages(
        ask,
        answering(ask)(AssistantMessage(None, Seq(weather, time)).withThinking(sealedThinking)),
        ToolMessage("sunny", "tc-1"),
        ToolMessage("noon", "tc-2")
      )
      val blocks = messages(1)("content").arr
      blocks.map(_.obj.keySet.head) shouldBe Seq("reasoningContent", "reasoningContent", "toolUse", "toolUse")
      blocks(0)("reasoningContent")("reasoningText")("signature").str shouldBe "sig-pair"
      blocks(1)("reasoningContent")("redactedContent").str shouldBe redacted
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

  // Bedrock documents the reasoning signature as a hash of all the messages in the conversation, and
  // Anthropic validates a block against everything before it: a change anywhere earlier unseals it
  "sealed reasoning" should {
    val sealedThinking = Seq(ThinkingBlock.Text("Weather.", Some("sig-h")), ThinkingBlock.Redacted(redacted))

    "not go back after an earlier part of the history was pruned" in {
      val history = Seq(UserMessage("Hello"), AssistantMessage("Hi."), UserMessage("Weather?"))
      val turn    = answering(history*)(AssistantMessage(None, Seq(weather)).withThinking(sealedThinking))

      val full = sentMessages((history :+ turn :+ ToolMessage("sunny", "tc-1"))*)
      full.map(_.render()).mkString should include("sig-h")

      // ContextPruning dropped the oldest exchange but kept the signed turn
      val pruned   = sentMessages(UserMessage("Weather?"), turn, ToolMessage("sunny", "tc-1"))
      val rendered = pruned.map(_.render()).mkString
      (rendered should not).include("reasoningContent")
      pruned(1)("content").arr.map(_.obj.keySet.head) shouldBe Seq("toolUse")
    }

    "not go back after the system prompt changed" in {
      val turn = answering(SystemMessage("Be brief."), UserMessage("Weather?"))(
        AssistantMessage(None, Seq(weather)).withThinking(sealedThinking)
      )
      val rendered =
        sentMessages(SystemMessage("Be terse."), UserMessage("Weather?"), turn, ToolMessage("sunny", "tc-1"))
          .map(_.render())
          .mkString
      (rendered should not).include("sig-h")
    }

    "not go back when no client bound it" in {
      val handBuilt = AssistantMessage(None, Seq(weather)).withThinking(sealedThinking)
      val rendered =
        sentMessages(UserMessage("Weather?"), handBuilt, ToolMessage("sunny", "tc-1")).map(_.render()).mkString
      (rendered should not).include("sig-h")
    }

    "be bound by the client that receives it, so a pruned follow-up does not send it" in {
      val seen = new ConcurrentLinkedQueue[ujson.Value]()
      withServer("/") { ex =>
        seen.add(ujson.read(new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
        sendJsonResponse(ex, 200, if (seen.size == 1) reasoningReply else converseResponse("Sunny."))
      } { url =>
        val client  = new BedrockClient(config(url))
        val history = Seq(UserMessage("Hello"), AssistantMessage("Hi."), UserMessage("Weather?"))
        val first   = client.complete(Conversation(history), CompletionOptions()).toOption.value
        first.message.thinkingBinding shouldBe defined
        val answer = ToolMessage("sunny", "tc-1")
        client.complete(Conversation(history :+ first.message :+ answer), CompletionOptions()).isRight shouldBe true
        client.complete(Conversation(history.drop(2) :+ first.message :+ answer), CompletionOptions()).isRight shouldBe
          true
        client.close()

        val requests = seen.asScala.toList
        requests(1).render() should include("sig-1")
        (requests(2).render() should not).include("sig-1")
        (requests(2).render() should not).include(redacted)
      }
    }
  }
}
