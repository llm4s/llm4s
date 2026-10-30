package org.llm4s.agent

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.AgentFixture.built
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ AssistantMessage, ToolMessage }
import org.llm4s.llmconnect.provider.OpenAIClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.concurrent.{ ConcurrentLinkedQueue, CopyOnWriteArrayList }
import scala.jdk.CollectionConverters.*

/**
 * A streaming agent with tools, through the real `OpenAIClient` over SSE from a local server (#843): the model
 * streams some text, then a tool call split across deltas; the agent runs the tool, streams the second turn and
 * completes. The report was that the run stalled after the tool call; this pins down that it finishes, with every
 * event of both turns delivered. No network, no API key.
 */
class AgentOpenAIStreamingToolsSpec extends AnyFlatSpec with TestTimeLimit with Matchers:

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val model = "gpt-4o"

  private def chunk(delta: String, finish: Option[String] = None, usage: Boolean = false): String =
    val finishReason = finish.fold("null")(f => s"\"$f\"")
    val usagePart =
      if usage then ""","usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}""" else ""
    s"""data: {"id":"chatcmpl-1","created":0,"model":"$model","choices":[{"index":0,"delta":$delta,"finish_reason":$finishReason}]$usagePart}"""

  private def fragment(arguments: String): String =
    s"""{"tool_calls":[{"index":0,"function":{"arguments":${ujson.Str(arguments).render()}}}]}"""

  /** First turn: some text, then a `lookup` call whose arguments arrive in three fragments. */
  private val toolTurn = Seq(
    chunk("""{"role":"assistant","content":"Let me check. "}"""),
    chunk(
      """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":""}}]}"""
    ),
    chunk(fragment("""{"text":""")),
    chunk(fragment("\"17*23\"")),
    chunk(fragment("}")),
    chunk("{}", finish = Some("tool_calls"), usage = true),
    "data: [DONE]"
  ).mkString("", "\n\n", "\n\n")

  /** Second turn: the answer, streamed in pieces. */
  private val answerTurn = LocalProviderTestServer.openAISseBody(Seq("17 * 23", " = ", "391."), model)

  private def client(baseUrl: String): OpenAIClient =
    OpenAIConfig.fromValues(model, "sk-test", None, baseUrl).flatMap(OpenAIClient(_)) match
      case Right(c)    => c
      case Left(error) => fail(s"could not build an OpenAIClient: ${error.message}")

  "A streaming agent on OpenAI" should "run a streamed tool call and complete with the streamed answer (#843)" in {
    streamsBothTurns(LocalProviderTestServer.sendSseResponse)
  }

  it should "finish each turn at [DONE] even when the server keeps the connection open" in {
    // A proxy or gateway may hold the response open after the last event: `[DONE]` ends the turn, not EOF.
    streamsBothTurns((exchange, body) => LocalProviderTestServer.streamThenHold(exchange, body))
  }

  /** Runs the two-turn scenario with each SSE body sent by `respond`, and checks the run completed in full. */
  private def streamsBothTurns(respond: (com.sun.net.httpserver.HttpExchange, String) => Unit): Unit =
    val bodies = new ConcurrentLinkedQueue[ujson.Value]()
    val ran    = new ConcurrentLinkedQueue[String]()
    val events = new CopyOnWriteArrayList[StreamEvent]()
    val lookup = SpecTools.tool("lookup") { (args, _) =>
      ran.add(args.text)
      ToolOutcome.Success(ToolContent.Json(ujson.Num(391)))
    }

    LocalProviderTestServer.withServer("/") { exchange =>
      bodies.add(ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
      respond(exchange, if bodies.size == 1 then toolTurn else answerTurn)
    } { baseUrl =>
      val c = client(baseUrl)
      val agent = built(
        Agent
          .builder("assistant", c)
          .withTools(SpecTools.set(lookup))
          .withSystemPrompt("Use the tools to answer.")
          .withStreaming()
      )
      val result =
        agent
          .stream(ThreadId(java.util.UUID.randomUUID().toString), "What is 17 * 23?")(e => events.add(e): Unit)
          .flatMap(_.await())
      c.close()

      val done = result.fold(e => fail(s"run failed: ${e.message}"), identity)
      done.status shouldBe AgentStatus.Completed("17 * 23 = 391.")
      ran.asScala.toVector shouldBe Vector("17*23")

      // Both turns were streamed, and the second carried the tool's result for the streamed call.
      bodies.asScala.toVector.map(_("stream").bool) shouldBe Vector(true, true)
      val followUp = bodies.asScala.toVector(1)("messages").arr
      followUp.exists(m => m("role").str == "tool" && m("tool_call_id").str == "call_1") shouldBe true
      done.messages.collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("call_1")
      done.messages.collect { case a: AssistantMessage if a.toolCalls.nonEmpty => a.toolCalls.map(_.id) } shouldBe
        Vector(Seq("call_1"))

      val seen = events.asScala.toVector
      seen.collect { case AgentEvents.TextDelta(d) => d.text }.mkString shouldBe "Let me check. 17 * 23 = 391."
      seen.collect { case AgentEvents.ToolCallStarted(t) => (t.toolCallId, t.tool, t.arguments) } shouldBe
        Vector(("call_1", "lookup", ujson.Obj("text" -> "17*23")))
      seen.collect { case AgentEvents.ToolExecuted(t) => t.tool } shouldBe Vector("lookup")
      seen.collect { case StreamEvent.Durable(r) if r.event == RunEvent.RunCompleted => r } should have size 1
    }
