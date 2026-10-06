package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.model.{ AssistantMessage, SystemMessage, ToolCall, ToolMessage, UserMessage }
import org.llm4s.trace.{ ConsoleTracing, TraceEvent, Tracing }

object LangfuseSampleTraceRunner {
  def main(args: Array[String]): Unit =
    exportSampleTrace()

  def exportSampleTrace(): Unit = {
    // Create a fake agent state event with a user query, assistant reply, and tool call
    val toolCall     = ToolCall("tool-1", "search", ujson.Obj("query" -> "Scala Langfuse integration"))
    val assistantMsg = AssistantMessage("Let me search for that...", Seq(toolCall))
    val toolMsg      = ToolMessage("tool-1", "{\"result\":\"Here is what I found...\"}")
    val userMsg      = UserMessage("How do I integrate Scala with Langfuse?")
    val sysMsg       = SystemMessage("You are a helpful assistant.")
    val messages     = Seq(sysMsg, userMsg, assistantMsg, toolMsg)
    val fakeState = TraceEvent.AgentStateUpdated(
      status = "Complete",
      messageCount = messages.size,
      logCount = 2,
      messages = messages
    )
    val tracer = Llm4sConfig
      .tracing()
      .map(Tracing.create)
      .fold(_ => new ConsoleTracing(), identity)
    tracer.traceEvent(fakeState)
  }
}
