package org.llm4s.javaapi

import org.llm4s.agent.AgentStatus
import org.llm4s.error.APIError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.testutil.MockLLMClients
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

class JavaApiIntegrationSpec extends AnyFlatSpec with Matchers {

  // ── Shared mock helpers (core test mocks) ──

  private def successClient(answer: String): LLMClient = new MockLLMClients.SimpleMock(answer)

  private def failingClient(message: String): LLMClient = new MockLLMClients.FailingMock(message)

  private def multiResponseClient(responses: Seq[String]): LLMClient = new MockLLMClients.MultiResponseMock(responses)

  private def toolCallingClient(toolName: String, toolArgs: ujson.Value, finalResponse: String): LLMClient =
    new LLMClient {
      private var callCount = 0
      override def complete(conv: Conversation, opts: CompletionOptions): Result[Completion] = {
        callCount += 1
        if (callCount == 1) {
          val tc  = ToolCall("call-1", toolName, toolArgs)
          val msg = AssistantMessage(contentOpt = None, toolCalls = Seq(tc))
          Right(Completion("id", 0L, "", "test-model", msg))
        } else {
          val msg = AssistantMessage(contentOpt = Some(finalResponse))
          Right(Completion("id", 0L, finalResponse, "test-model", msg))
        }
      }
      override def streamComplete(
        conv: Conversation,
        opts: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conv, opts)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
    }

  case class EchoResult(echoed: String)
  implicit val echoResultRW: ReadWriter[EchoResult] = macroRW[EchoResult]

  private def echoToolRegistry(): ToolRegistry = {
    val schema = Schema
      .`object`[Map[String, Any]]("Echo parameters")
      .withProperty(Schema.property("input", Schema.string("The input to echo")))
    val tool = ToolBuilder[Map[String, Any], EchoResult]("echo_tool", "Echoes the input", schema)
      .withHandler(ext => ext.getString("input").map(s => EchoResult(s"echoed: $s")))
      .buildSafe()
    tool match {
      case Right(t) => new ToolRegistry(Seq(t))
      case Left(e)  => fail(s"Failed to build echo tool: $e")
    }
  }

  // ── 1. Happy-path: full Java bridge pipeline ──

  "Llm4s.createAgent → JAgent.run" should "complete successfully via the full bridge" in {
    val client = new JLlmClient(successClient("42"))
    val agent  = Llm4s.createAgent(client)
    val result = agent.run("What is 6*7?")
    result.isSuccess shouldBe true
    result.get().status shouldBe AgentStatus.Complete
  }

  it should "surface the LLM response in the final conversation" in {
    val client = new JLlmClient(successClient("Paris"))
    val agent  = Llm4s.createAgent(client)
    val result = agent.run("Capital of France?")
    result.isSuccess shouldBe true
    result.get().conversation.messages.last.content shouldBe "Paris"
  }

  // ── 2. LlmResult<String> mapping from Right and Left Either values ──

  "LlmResult<String> from JLlmClient.complete(String)" should "map Right to isSuccess" in {
    val client = new JLlmClient(successClient("hello"))
    val result = client.complete("ping")
    result.isSuccess shouldBe true
    result.get() shouldBe "hello"
  }

  it should "map Left to isFailure" in {
    val client = new JLlmClient(failingClient("network down"))
    val result = client.complete("ping")
    result.isFailure shouldBe true
    result.getError() shouldBe a[LlmException]
  }

  // ── 3. Error path: LLMError → LlmException propagation ──

  "JAgent.run" should "return a failed LlmResult without throwing when the LLM errors" in {
    val client = new JLlmClient(failingClient("simulated error"))
    val agent  = Llm4s.createAgent(client)
    val result = agent.run("hello")
    result.isFailure shouldBe true
  }

  it should "throw LlmException with the error message when get() is called on a failure" in {
    val client = new JLlmClient(failingClient("oops"))
    val agent  = Llm4s.createAgent(client)
    val result = agent.run("hello")
    val ex     = intercept[LlmException](result.get())
    ex.getMessage should include("oops")
  }

  it should "wrap APIError in LlmException" in {
    val underlying = new LLMClient {
      override def complete(conv: Conversation, opts: CompletionOptions): Result[Completion] =
        Left(APIError("test-provider", "rate limited"))
      override def streamComplete(
        conv: Conversation,
        opts: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conv, opts)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
    }
    val client = new JLlmClient(underlying)
    val agent  = Llm4s.createAgent(client)
    val result = agent.run("hello")
    result.isFailure shouldBe true
    val ex = intercept[LlmException](result.get())
    ex.error shouldBe a[APIError]
  }

  // ── 4. ConversationBuilder multi-turn flow end-to-end ──

  "ConversationBuilder → JLlmClient.complete(Conversation)" should "succeed for a single-turn query" in {
    val client = new JLlmClient(successClient("pong"))
    val conv   = ConversationBuilder.create().system("Be concise.").user("ping").build()
    val result = client.complete(conv)
    result.isSuccess shouldBe true
    result.get() shouldBe "pong"
  }

  it should "cycle through responses across multi-turn calls" in {
    val client = new JLlmClient(multiResponseClient(Seq("First", "Second")))
    val conv1  = ConversationBuilder.create().user("Turn 1").build()
    val conv2  = ConversationBuilder.create().user("Turn 1").assistant("First").user("Turn 2").build()
    client.complete(conv1).get() shouldBe "First"
    client.complete(conv2).get() shouldBe "Second"
  }

  it should "propagate errors through the full conversation path" in {
    val client = new JLlmClient(failingClient("bad"))
    val conv   = ConversationBuilder.create().user("hello").build()
    client.complete(conv).isFailure shouldBe true
  }

  "The conversation sent to the provider" should "carry the built roles, contents and order unchanged" in {
    val seen = new java.util.concurrent.atomic.AtomicReference[Conversation]()
    val capturing = new LLMClient {
      override def complete(conv: Conversation, opts: CompletionOptions): Result[Completion] = {
        seen.set(conv)
        Right(Completion("id", 0L, "ok", "m", AssistantMessage("ok")))
      }
      override def streamComplete(
        conv: Conversation,
        opts: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conv, opts)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
    }
    val client = new JLlmClient(capturing)
    client.complete(ConversationBuilder.create().system("S").user("U").assistant("A").user("U2").build()).get()
    seen.get().messages.map(m => m.role.name -> m.content) shouldBe
      Seq("system" -> "S", "user" -> "U", "assistant" -> "A", "user" -> "U2")

    client.complete("plain").get()
    seen.get().messages.map(m => m.role.name -> m.content) shouldBe Seq("user" -> "plain")
  }

  // ── 5. Tool-calling: mock returns a tool call then a final response ──

  "JAgent.run with tool-calling mock" should "invoke the registered tool and reach Complete status" in {
    val tools  = echoToolRegistry()
    val mock   = toolCallingClient("echo_tool", ujson.Obj("input" -> ujson.Str("world")), "Done!")
    val client = new JLlmClient(mock)
    val agent  = Llm4s.createAgent(client)
    val result = agent.run("Echo 'world'", tools)
    result.isSuccess shouldBe true
    result.get().status shouldBe AgentStatus.Complete
  }

  it should "actually execute the tool and feed its output back into the conversation" in {
    val mock        = toolCallingClient("echo_tool", ujson.Obj("input" -> ujson.Str("world")), "Done!")
    val agent       = Llm4s.createAgent(new JLlmClient(mock))
    val result      = agent.run("Echo 'world'", echoToolRegistry())
    val toolOutputs = result.get().conversation.messages.collect { case t: ToolMessage => t.content }
    toolOutputs should have size 1
    toolOutputs.head should include("echoed: world")
  }

  it should "not execute any tool when the single-argument run (empty registry) is used" in {
    val mock        = toolCallingClient("echo_tool", ujson.Obj("input" -> ujson.Str("world")), "Done!")
    val agent       = Llm4s.createAgent(new JLlmClient(mock))
    val result      = agent.run("Echo 'world'")
    val toolOutputs = result.get().conversation.messages.collect { case t: ToolMessage => t.content }
    toolOutputs.exists(_.contains("echoed: world")) shouldBe false
  }

  it should "include the final assistant response in the conversation after tool use" in {
    val tools  = echoToolRegistry()
    val mock   = toolCallingClient("echo_tool", ujson.Obj("input" -> ujson.Str("test")), "All done")
    val client = new JLlmClient(mock)
    val agent  = Llm4s.createAgent(client)
    val result = agent.run("Echo 'test'", tools)
    result.isSuccess shouldBe true
    val lastAssistantText = result
      .get()
      .conversation
      .messages
      .collect { case m: AssistantMessage if m.toolCalls.isEmpty => m.content }
      .lastOption
    lastAssistantText shouldBe Some("All done")
  }
}
