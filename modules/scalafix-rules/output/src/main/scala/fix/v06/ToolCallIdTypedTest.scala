package fix.v06

import org.llm4s.agent.events.{ ToolCallStarted, ToolExecuted }
import org.llm4s.agent.graph.ThreadId
import org.llm4s.llmconnect.model.{ Message, ToolCall, ToolMessage }
import org.llm4s.toolapi.ToolCallInfo
import org.llm4s.llmconnect.model.ToolCallId
import org.llm4s.toolapi.IdempotencyKey

object ToolCallIdTypedTest {
  val call = ToolCall(ToolCallId("c1"), "weather", ujson.Obj("city" -> "Paris"))

  val named = ToolCall(id = ToolCallId("c2"), name = "weather", arguments = ujson.Obj())

  def fromString(id: String): ToolCall = ToolCall(ToolCallId(id), "weather", ujson.Obj())

  val result = ToolMessage("sunny", ToolCallId("c1"))

  // an id passed on from its call is typed already
  val answered = ToolMessage("sunny", call.id)

  val renamed = call.withId(ToolCallId(s"${call.id.asString}-again"))

  val checked = Message.tool("sunny", ToolCallId("c1"))

  val info = ToolCallInfo(ToolCallId("c1"), IdempotencyKey("key-1"))

  def matches(message: ToolMessage): Boolean = message.toolCallId.asString == "c1"

  def byId(calls: Seq[ToolCall]): Map[String, ToolCall] = calls.map(c => c.id.asString -> c).toMap

  def header(info: ToolCallInfo): String = info.idempotencyKey.asString

  val started = ToolCallStarted(ToolCallId("c1"), "weather", ujson.Obj())

  def length(event: ToolCallStarted): Int = event.toolCallId.asString.length

  def key(event: ToolExecuted): Option[String] = event.idempotencyKey.map(_.asString)

  // the agent's own newtypes are core's now
  def typed(call: ToolCall): ToolCallId = call.id

  def keyText(key: IdempotencyKey): String = key.asString

  val thread = ThreadId("t")
}
