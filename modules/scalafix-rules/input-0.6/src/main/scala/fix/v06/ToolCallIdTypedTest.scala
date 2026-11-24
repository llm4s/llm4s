/*
rule = ToolCallIdTyped
*/
package fix.v06

import org.llm4s.agent.events.{ ToolCallStarted, ToolExecuted }
import org.llm4s.agent.graph.{ IdempotencyKey, ThreadId, ToolCallId }
import org.llm4s.llmconnect.model.{ Message, ToolCall, ToolMessage }
import org.llm4s.toolapi.ToolCallInfo

object ToolCallIdTypedTest {
  val call = ToolCall("c1", "weather", ujson.Obj("city" -> "Paris"))

  val named = ToolCall(id = "c2", name = "weather", arguments = ujson.Obj())

  def fromString(id: String): ToolCall = ToolCall(id, "weather", ujson.Obj())

  val result = ToolMessage("sunny", "c1")

  // an id passed on from its call is typed already
  val answered = ToolMessage("sunny", call.id)

  val renamed = call.withId(s"${call.id}-again")

  val checked = Message.tool("sunny", "c1")

  val info = ToolCallInfo("c1", "key-1")

  def matches(message: ToolMessage): Boolean = message.toolCallId == "c1"

  def byId(calls: Seq[ToolCall]): Map[String, ToolCall] = calls.map(c => c.id -> c).toMap

  def header(info: ToolCallInfo): String = info.idempotencyKey

  val started = ToolCallStarted("c1", "weather", ujson.Obj())

  def length(event: ToolCallStarted): Int = event.toolCallId.length

  def key(event: ToolExecuted): Option[String] = event.idempotencyKey

  // the agent's own newtypes are core's now
  def typed(call: ToolCall): ToolCallId = ToolCallId(call.id)

  def keyText(key: IdempotencyKey): String = key.value

  val thread = ThreadId("t")
}
