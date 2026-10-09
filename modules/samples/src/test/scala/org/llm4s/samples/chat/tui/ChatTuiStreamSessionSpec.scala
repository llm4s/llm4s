package org.llm4s.samples.chat.tui

import org.llm4s.llmconnect.model.{ StreamedChunk, ToolCall }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ChatTuiStreamSessionSpec extends AnyFunSuite with Matchers {
  private def chunk(id: String, name: String, args: ujson.Value): StreamedChunk =
    StreamedChunk(id = "response", content = None, toolCall = Some(ToolCall(id, name, args)))

  test("parse a complete raw argument document before tool approval") {
    val session = new ChatTuiModel.StreamSession
    session.addChunks(Vector(chunk("call1", "read_file", ujson.Str("{\"path\":\"hello.txt\"}"))))
    session.toolCalls.head.arguments shouldBe ujson.Obj("path" -> "hello.txt")
  }

  test("join fragments across pump ticks and final drain, retaining call order") {
    val session = new ChatTuiModel.StreamSession
    session.addChunks(Vector(chunk("call1", "read_file", ujson.Str("{\"path\":"))))
    session.addChunks(Vector(chunk("call2", "read_file", ujson.Obj("path" -> "other.txt"))))
    session.addChunks(Vector(chunk("call1", "", ujson.Str("\"hello.txt\"}"))))
    session.toolCalls.map(_.id) shouldBe Vector("call1", "call2")
    session.toolCalls.head.arguments shouldBe ujson.Obj("path" -> "hello.txt")
    session.reset()
    session.toolCalls shouldBe empty
    session.addChunks(Vector(chunk("call1", "read_file", ujson.Str("{\"path\":\"new.txt\"}"))))
    session.toolCalls.head.arguments shouldBe ujson.Obj("path" -> "new.txt")
  }
}
