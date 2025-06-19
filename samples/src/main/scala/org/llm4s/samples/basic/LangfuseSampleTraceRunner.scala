package org.llm4s.samples.basic

import org.llm4s.trace.Tracing
import org.llm4s.llmconnect.model.{AssistantMessage, ToolMessage, ToolCall, UserMessage, SystemMessage, Conversation}
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.agent.{AgentState, AgentStatus}

object LangfuseSampleTraceRunner {
  def main(args: Array[String]): Unit = {
    exportSampleTrace()
  }

  def exportSampleTrace(): Unit = {
    // Create a fake AgentState with a user query, assistant reply, and tool call
    val toolCall = ToolCall("tool-1", "search", ujson.Obj("query" -> "Scala Langfuse integration"))
    val assistantMsg = AssistantMessage("Let me search for that...", Seq(toolCall))
    val toolMsg = ToolMessage("tool-1", "{\"result\":\"Here is what I found...\"}")
    val userMsg = UserMessage("How do I integrate Scala with Langfuse?")
    val sysMsg = SystemMessage("You are a helpful assistant.")
    val conversation = Conversation(Seq(sysMsg, userMsg, assistantMsg, toolMsg))
    val fakeState = AgentState(
      conversation = conversation,
      tools = new ToolRegistry(Seq()),
      userQuery = userMsg.content,
      status = AgentStatus.Complete,
      logs = Seq("[assistant] tools: 1 tool calls requested (search)", "[tool] search (100ms): {\"result\":\"Here is what I found...\"}")
    )
    val tracer = Tracing.create()
    tracer.traceAgentState(fakeState)
  }
}