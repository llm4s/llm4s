package org.llm4s.llmconnect.provider

import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model.*
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The encoding the Gemini API and Vertex AI clients share ([[GeminiWire]], #3124). */
class GeminiWireSpec extends AnyWordSpec with Matchers {

  private val provider = ProviderId("gemini")

  private def encode(messages: Message*): GeminiWire.Encoded =
    GeminiWire.encode(provider, messages, _ => None)

  private def call(id: String, name: String) = ToolCall(ToolCallId(id), name, ujson.Obj("q" -> 1))

  "encode" should {
    "send every system message, in order, as its own systemInstruction part" in {
      val encoded = encode(
        SystemMessage("You are the agent."),
        UserMessage("hi"),
        SystemMessage("Digest of earlier turns.")
      )
      encoded.systemInstruction.map(_("parts").arr.map(_("text").str).toList) shouldBe
        Some(List("You are the agent.", "Digest of earlier turns."))
      encoded.contents.map(_("role").str) shouldBe Vector("user")
    }

    "send no systemInstruction when there is no system message" in {
      encode(UserMessage("hi")).systemInstruction shouldBe None
    }

    "send a parallel batch's results as one user turn, one functionResponse per call, named for it" in {
      val encoded = encode(
        UserMessage("Check."),
        AssistantMessage(None, Seq(call("a", "lookup"), call("b", "fetch"))),
        ToolMessage("one", ToolCallId("a")),
        SystemMessage("between results"),
        ToolMessage("two", ToolCallId("b"))
      )
      encoded.contents.map(_("role").str) shouldBe Vector("user", "model", "user")
      val responses = encoded.contents(2)("parts").arr.map(_("functionResponse"))
      responses.map(_("name").str).toList shouldBe List("lookup", "fetch")
      responses.map(_("response")("result").str).toList shouldBe List("one", "two")
    }

    "name a result for the call of its own turn when a call id is reused across turns" in {
      val encoded = encode(
        UserMessage("1"),
        AssistantMessage(None, Seq(call("call_0", "lookup"))),
        ToolMessage("first", ToolCallId("call_0")),
        UserMessage("2"),
        AssistantMessage(None, Seq(call("call_0", "fetch"))),
        ToolMessage("second", ToolCallId("call_0"))
      )
      encoded.contents.last("parts")(0)("functionResponse")("name").str shouldBe "fetch"
    }

    "send a result with no call before it as text, never under its id as a function name" in {
      val encoded = encode(
        UserMessage("Check."),
        AssistantMessage(Some("Checking."), Seq(call("a", "lookup"))),
        UserMessage("never mind"),
        ToolMessage("late", ToolCallId("a"))
      )
      (ujson.Arr.from(encoded.contents).render() should not).include("functionResponse")
      encoded.contents.last("parts")(0)("text").str shouldBe "[Tool result for a]: late"
    }
  }

  "streamChunk" should {
    "fail on a function call without a name, as complete does, rather than drop the chunk" in {
      val chunk = ujson.read(
        """{"candidates":[{"content":{"parts":[{"text":"Let me"},{"functionCall":{"args":{}}}]}}]}"""
      )
      GeminiWire.streamChunk(provider, chunk, "m", 0, callsBefore = false) should matchPattern {
        case Left(e: ProcessingError) if e.message.contains("malformed tool call") =>
      }
      GeminiWire.malformedReply(chunk) shouldBe defined
    }

    "read a chunk with no candidate, such as a usage-only one, as nothing" in {
      GeminiWire.streamChunk(provider, ujson.read("""{"usageMetadata":{}}"""), "m", 0, false) shouldBe Right(None)
    }

    "read a text chunk" in {
      val chunk = ujson.read("""{"candidates":[{"content":{"parts":[{"text":"Hi"}]},"finishReason":"STOP"}]}""")
      GeminiWire.streamChunk(provider, chunk, "m", 0, false).map(_.map(_._1.text)) shouldBe Right(Some("Hi"))
    }
  }
}
