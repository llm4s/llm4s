package org.llm4s.agent

import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, ToolCall, ToolMessage, UserMessage }
import org.llm4s.toolapi.ToolRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** Unit tests for [[AgentTraceFormatter]] — all tests are pure (no LLM calls). */
class AgentTraceFormatterSpec extends AnyFlatSpec with Matchers {

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private def minimalState(query: String = "hello"): AgentState =
    AgentState(
      conversation = Conversation(Seq(UserMessage(query))),
      tools = ToolRegistry.empty,
      initialQuery = Some(query),
      status = AgentStatus.Complete
    )

  private def stateWithMessages(msgs: org.llm4s.llmconnect.model.Message*): AgentState =
    AgentState(
      conversation = Conversation(msgs.toSeq),
      tools = ToolRegistry.empty,
      initialQuery = Some("test"),
      status = AgentStatus.Complete
    )

  private def stateWithLogs(logs: String*): AgentState =
    AgentState(
      conversation = Conversation(Seq(UserMessage("q"))),
      tools = ToolRegistry.empty,
      initialQuery = Some("q"),
      logs = logs.toSeq,
      status = AgentStatus.Complete
    )

  // ── formatStateAsMarkdown ─────────────────────────────────────────────────────

  "AgentTraceFormatter.formatStateAsMarkdown" should "contain the Agent Execution Trace header" in {
    val md = AgentTraceFormatter.formatStateAsMarkdown(minimalState())
    md should include("# Agent Execution Trace")
  }

  it should "include the initial query" in {
    val md = AgentTraceFormatter.formatStateAsMarkdown(minimalState("what is 2+2?"))
    md should include("what is 2+2?")
  }

  it should "include the agent status" in {
    val md = AgentTraceFormatter.formatStateAsMarkdown(minimalState())
    md should include("Complete")
  }

  it should "render a User message under Conversation Flow" in {
    val md = AgentTraceFormatter.formatStateAsMarkdown(minimalState("user question"))
    md should include("## Conversation Flow")
    md should include("User Message")
    md should include("user question")
  }

  it should "render an Assistant message with text content" in {
    val state = stateWithMessages(
      UserMessage("q"),
      AssistantMessage("the answer", Seq.empty)
    )
    val md = AgentTraceFormatter.formatStateAsMarkdown(state)
    md should include("Assistant Message")
    md should include("the answer")
  }

  it should "render tool call arguments in a fenced JSON code block" in {
    val toolCall = ToolCall(
      id = "call-1",
      name = "weather",
      arguments = ujson.Obj("city" -> ujson.Str("London"))
    )
    val state = stateWithMessages(
      UserMessage("q"),
      AssistantMessage("", Seq(toolCall))
    )
    val md = AgentTraceFormatter.formatStateAsMarkdown(state)
    md should include("```json")
    md should include("weather")
  }

  it should "render Tool response messages" in {
    val toolMsg = ToolMessage("{\"temp\": 20}", "call-1")
    val state   = stateWithMessages(UserMessage("q"), toolMsg)
    val md      = AgentTraceFormatter.formatStateAsMarkdown(state)
    md should include("Tool Response")
    md should include("call-1")
    md should include("temp")
  }

  it should "omit the Execution Logs section when state.logs is empty" in {
    val state = minimalState()
    val md    = AgentTraceFormatter.formatStateAsMarkdown(state)
    (md should not).include("## Execution Logs")
  }

  it should "include the Execution Logs section when logs are present" in {
    val state = stateWithLogs("[assistant] thinking about it", "[tool] calc(5ms): 42")
    val md    = AgentTraceFormatter.formatStateAsMarkdown(state)
    md should include("## Execution Logs")
    md should include("thinking about it")
  }

  it should "format assistant log entries with bold label" in {
    val state = stateWithLogs("[assistant] my response")
    val md    = AgentTraceFormatter.formatStateAsMarkdown(state)
    md should include("**Assistant:**")
  }

  it should "format tool log entries with bold label" in {
    val state = stateWithLogs("[tool] calc(5ms): result")
    val md    = AgentTraceFormatter.formatStateAsMarkdown(state)
    md should include("**Tool Output:**")
  }

  // ── writeTraceLog ─────────────────────────────────────────────────────────────

  "AgentTraceFormatter.writeTraceLog" should "write the formatted state to a file" in {
    val tmpFile = Files.createTempFile("agent-trace-", ".md")
    try {
      AgentTraceFormatter.writeTraceLog(minimalState("written query"), tmpFile.toString)
      val contents = new String(Files.readAllBytes(tmpFile))
      contents should include("written query")
      contents should include("# Agent Execution Trace")
    } finally
      Files.deleteIfExists(tmpFile)
  }

  it should "silently swallow write failures without throwing an exception" in {
    // Writing to an invalid path should not throw
    noException should be thrownBy {
      AgentTraceFormatter.writeTraceLog(minimalState(), "/nonexistent/path/trace.md")
    }
  }
}
