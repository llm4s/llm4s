package org.llm4s.llmconnect.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write, ReadWriter }

/**
 * A tool call's id is a [[ToolCallId]] (#2815), stored as the plain JSON string it was when it was a `String`: these
 * documents were written by the last build with `String` ids, and read and write back byte-identical.
 */
class ToolCallIdWireFormatSpec extends AnyFlatSpec with Matchers {

  private def exactly[A: ReadWriter](json: String) = write(read[A](json)) shouldBe json

  private val call      = """{"id":"c1","name":"echo","arguments":{"text":"hi"}}"""
  private val assistant = s"""{"type":"assistant","content":"calling","toolCalls":[$call]}"""
  private val tool      = """{"type":"tool","toolCallId":"c1","content":"hi"}"""

  "A ToolCall" should "keep its JSON" in {
    exactly[ToolCall](call)
  }

  "An assistant message with tool calls" should "keep its JSON, as a Message and as an AssistantMessage" in {
    exactly[Message](assistant)
    exactly[AssistantMessage]("""{"content":"calling","toolCalls":[""" + call + "]}")
  }

  "A tool message" should "keep its JSON, as a Message and as a ToolMessage" in {
    exactly[Message](tool)
    exactly[ToolMessage]("""{"toolCallId":"c1","content":"hi"}""")
  }

  "A conversation" should "keep its JSON" in {
    exactly[Vector[Message]](s"[$assistant,$tool]")
  }
}
