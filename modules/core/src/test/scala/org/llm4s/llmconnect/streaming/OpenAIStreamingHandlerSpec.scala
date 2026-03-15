package org.llm4s.llmconnect.streaming

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class OpenAIStreamingHandlerSpec extends AnyFlatSpec with Matchers {

  "OpenAIStreamingHandler" should "treat empty tool-call arguments as empty object" in {
    val handler = new OpenAIStreamingHandler()

    val json = ujson.Obj(
      "id" -> "chatcmpl-1",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "delta" -> ujson.Obj(
            "tool_calls" -> ujson.Arr(
              ujson.Obj(
                "id"   -> "call-1",
                "type" -> "function",
                "function" -> ujson.Obj(
                  "name"      -> "test",
                  "arguments" -> ""
                )
              )
            )
          )
        )
      )
    )

    val chunk = s"data: ${ujson.write(json)}\n\n"

    val result = handler.processChunk(chunk)

    val streamed = result.getOrElse(fail("Expected Right but got Left"))
    streamed match {
      case Some(chunk) =>
        chunk.toolCall match {
          case Some(tc) => tc.arguments shouldBe ujson.Obj()
          case None     => fail("Expected toolCall to be defined")
        }
      case None => fail("Expected Some(StreamedChunk) but got None")
    }
  }
}
