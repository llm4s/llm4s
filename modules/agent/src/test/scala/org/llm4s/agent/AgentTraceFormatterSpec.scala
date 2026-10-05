package org.llm4s.agent

import org.llm4s.llmconnect.model.{ AssistantMessage, ToolCall, ToolMessage, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** Unit tests for [[AgentTraceFormatter]] — all tests are pure (no LLM calls). */
class AgentTraceFormatterSpec extends AnyFlatSpec with Matchers {

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private def minimalThread(query: String = "hello"): AgentThread =
    AgentThread(threadId = "t", messages = Seq(UserMessage(query)), status = ThreadStatus.Completed)

  private def threadWithMessages(msgs: org.llm4s.llmconnect.model.Message*): AgentThread =
    AgentThread(threadId = "t", messages = msgs.toSeq, status = ThreadStatus.Completed)

  // ── formatThreadAsMarkdown ─────────────────────────────────────────────────────

  "AgentTraceFormatter.formatThreadAsMarkdown" should "contain the Agent Execution Trace header" in {
    val md = AgentTraceFormatter.formatThreadAsMarkdown(minimalThread())
    md should include("# Agent Execution Trace")
  }

  it should "include the initial query" in {
    val md = AgentTraceFormatter.formatThreadAsMarkdown(minimalThread("what is 2+2?"))
    md should include("what is 2+2?")
  }

  it should "include the agent status" in {
    val md = AgentTraceFormatter.formatThreadAsMarkdown(minimalThread())
    md should include("Completed")
  }

  it should "render a User message under Conversation Flow" in {
    val md = AgentTraceFormatter.formatThreadAsMarkdown(minimalThread("user question"))
    md should include("## Conversation Flow")
    md should include("User Message")
    md should include("user question")
  }

  it should "render an Assistant message with text content" in {
    val state = threadWithMessages(
      UserMessage("q"),
      AssistantMessage("the answer", Seq.empty)
    )
    val md = AgentTraceFormatter.formatThreadAsMarkdown(state)
    md should include("Assistant Message")
    md should include("the answer")
  }

  it should "render tool call arguments in a fenced JSON code block" in {
    val toolCall = ToolCall(
      id = "call-1",
      name = "weather",
      arguments = ujson.Obj("city" -> ujson.Str("London"))
    )
    val state = threadWithMessages(
      UserMessage("q"),
      AssistantMessage("", Seq(toolCall))
    )
    val md = AgentTraceFormatter.formatThreadAsMarkdown(state)
    md should include("```json")
    md should include("weather")
  }

  it should "render Tool response messages" in {
    val toolMsg = ToolMessage("{\"temp\": 20}", "call-1")
    val state   = threadWithMessages(UserMessage("q"), toolMsg)
    val md      = AgentTraceFormatter.formatThreadAsMarkdown(state)
    md should include("Tool Response")
    md should include("call-1")
    md should include("temp")
  }

  it should "have no Execution Logs section: a thread has no log" in {
    val md = AgentTraceFormatter.formatThreadAsMarkdown(minimalThread())
    (md should not).include("## Execution Logs")
  }

  it should "name the thread's status, including the error of a failed one" in {
    val md = AgentTraceFormatter.formatThreadAsMarkdown(minimalThread().withStatus(ThreadStatus.Failed("limit hit")))
    md should include("Failed")
    md should include("limit hit")
  }

  it should "leave the system message out of the conversation section" in {
    val thread = minimalThread().withSystemMessage(org.llm4s.llmconnect.model.SystemMessage("SECRET-INSTRUCTIONS"))
    (AgentTraceFormatter.formatThreadAsMarkdown(thread) should not).include("SECRET-INSTRUCTIONS")
  }

  // ── writeTraceLog ─────────────────────────────────────────────────────────────

  "AgentTraceFormatter.writeTraceLog" should "write the formatted thread to a file" in {
    val tmpFile = Files.createTempFile("agent-trace-", ".md")
    try {
      AgentTraceFormatter.writeTraceLog(minimalThread("written query"), tmpFile.toString)
      val contents = new String(Files.readAllBytes(tmpFile))
      contents should include("written query")
      contents should include("# Agent Execution Trace")
    } finally Files.deleteIfExists(tmpFile)
  }

  it should "silently swallow write failures without throwing an exception" in {
    // Writing to an invalid path should not throw
    noException should be thrownBy {
      AgentTraceFormatter.writeTraceLog(minimalThread(), "/nonexistent/path/trace.md")
    }
  }
}
