package org.llm4s.agent

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph.{ GraphError, RunBudgets, RunConfig, ThreadId }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelContext, ModelRequest, ModelResponse }
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

/**
 * `RunBudgets.maxTokens` (design 4.43): before each model call the loop counts the run's tokens - the thread's
 * usage since the run was admitted, plus the current task's - and at the budget ends the turn `TokenLimitReached`
 * without calling the model. Every scripted completion here uses 30 tokens (10 in, 20 out).
 */
class AgentTokenBudgetSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private val thread = ThreadId("token-budget")

  private def limited(tokens: Long): RunConfig = RunConfig().withBudgets(RunBudgets.default.withMaxTokens(tokens))

  private def tokens(usage: UsageSummary): Long = usage.inputTokens + usage.outputTokens

  private val echo = SpecTools.tool("echo")((a, _) => ToolOutcome.Success(ToolContent.Json(ujson.Str(a.text))))

  "A token budget" should "end the turn TokenLimitReached before the next model call, without calling the model" in {
    val client = ScriptedLLMClient.of(calling(call("c1", "echo")), CompletionFixture.simple("never sent"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    val result = agent.run(thread, "go", limited(30)).value

    result.status shouldBe AgentStatus.TokenLimitReached
    result.answer shouldBe None
    client.callCount shouldBe 1
    tokens(result.usage) shouldBe 30L
    // the batch the last call asked for still ran and is in the history: the turn ends at the next model call
    result.messages.collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("c1")
  }

  it should "let a run overshoot by the call in flight, which is never interrupted" in {
    val client = ScriptedLLMClient.of(calling(call("c1", "echo")), CompletionFixture.simple("never sent"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    val result = agent.run(thread, "go", limited(1)).value

    result.status shouldBe AgentStatus.TokenLimitReached
    client.callCount shouldBe 1
    tokens(result.usage) shouldBe 30L // 29 over the budget of 1: the one call admitted under it
  }

  it should "complete a turn that answers within the budget" in {
    val client = ScriptedLLMClient.of(calling(call("c1", "echo")), CompletionFixture.simple("done"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    agent.run(thread, "go", limited(31)).value.answer shouldBe Some("done")
    client.callCount shouldBe 2
  }

  it should "count only the run's own tokens, not the thread's earlier turns" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("first"), CompletionFixture.simple("second"))
    val agent  = plain(client)

    agent.run(thread, "one").value.answer shouldBe Some("first")
    val second = agent.run(thread, "two", limited(30)).value

    second.answer shouldBe Some("second")
    tokens(second.usage) shouldBe 60L // the thread's usage still accumulates over both turns
  }

  it should "be a completed turn: the next turn, with its own budget, runs normally" in {
    val client = ScriptedLLMClient.of(calling(call("c1", "echo")), CompletionFixture.simple("next turn"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    val stopped = agent.run(thread, "go", limited(30)).value
    stopped.status shouldBe AgentStatus.TokenLimitReached

    agent.continueConversation(stopped, "again").value.answer shouldBe Some("next turn")
  }

  it should "hold in a recovered run, counted from the recover's admission, and the status survives it" in {
    val broken = new AtomicBoolean(true)
    val flaky = SpecTools.tool("flaky") { (_, _) =>
      if (broken.get) ToolOutcome.Fatal(ValidationError("flaky", "backend down"))
      else ToolOutcome.Success(ToolContent.Json(ujson.Str("fixed")))
    }
    val client = ScriptedLLMClient.of(
      calling(call("c1", "flaky")),
      calling(call("c2", "echo")),
      CompletionFixture.simple("never sent")
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo, flaky)))

    // the first run's one call (30 tokens) is under its budget; its tool then fails the run
    cause(agent.run(thread, "go", limited(31)).error) shouldBe a[GraphError.ToolFailed]
    client.callCount shouldBe 1

    // the recover's budget counts from its admission: the earlier 30 tokens are not this run's, so it calls once
    broken.set(false)
    val recovered = agent.recover(thread, limited(30)).value
    recovered.status shouldBe AgentStatus.TokenLimitReached
    client.callCount shouldBe 2
    tokens(recovered.usage) shouldBe 60L
    recovered.messages.collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("c1", "c2")
  }

  it should "not limit a run without one" in {
    val client = ScriptedLLMClient.of(
      calling(call("c1", "echo")),
      calling(call("c2", "echo")),
      CompletionFixture.simple("unlimited")
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    agent.run(thread, "go").value.answer shouldBe Some("unlimited")
  }

  it should "check every accounted call a middleware makes within one step, not only the step" in {
    // a summariser over three chunks, each call 30 tokens; it ignores a failed call, as a best-effort one would
    val summariser = ScriptedLLMClient.of(
      CompletionFixture.withUsage("s1", 10, 20),
      CompletionFixture.withUsage("s2", 10, 20),
      CompletionFixture.withUsage("s3", 10, 20)
    )
    val summarising = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("summariser")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] =
        val client = context.accounted(summariser)
        (1 to 3).foreach(i => client.complete(Conversation(Seq(UserMessage(s"chunk $i")))): Unit)
        next(request)
    }
    val model = ScriptedLLMClient.of(CompletionFixture.simple("never sent"))
    val agent = built(Agent.builder("assistant", model).withMiddleware(summarising))

    val result = agent.run(thread, "go", limited(50)).value

    // the first two calls are admitted under the budget (0, then 30); the third, at 60, is refused, and so is the step
    result.status shouldBe AgentStatus.TokenLimitReached
    summariser.callCount shouldBe 2
    model.callCount shouldBe 0
    tokens(result.usage) shouldBe 60L
  }

  it should "charge an estimate for a completion that reports no usage, so the budget still advances" in {
    val client = ScriptedLLMClient.of(
      calling(call("c1", "echo")).withUsage(None),
      calling(call("c2", "echo")).withUsage(None),
      CompletionFixture.simple("never sent").withUsage(None)
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    val result = agent.run(thread, "go", limited(1)).value

    result.status shouldBe AgentStatus.TokenLimitReached
    client.callCount shouldBe 1
    result.usage.inputTokens should be > 0L
    result.usage.outputTokens should be > 0L
  }

  it should "admit concurrent accounted calls one at a time, so a parallel fan-out overshoots by one call at most" in {
    val made = new java.util.concurrent.atomic.AtomicInteger(0)
    // each call takes a moment, so unserialized calls would all be admitted before any recorded its 30 tokens
    val slow = new org.llm4s.llmconnect.LLMClient {
      def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        made.incrementAndGet(): Unit
        Thread.sleep(50)
        Right(CompletionFixture.withUsage("s", 10, 20))
      def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      def getContextWindow(): Int     = 1000
      def getReserveCompletion(): Int = 100
    }
    val fanOut = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("fan-out")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] =
        val client  = context.accounted(slow)
        val threads = (1 to 4).map(i => new Thread(() => client.complete(Conversation(Seq(UserMessage(s"$i")))): Unit))
        threads.foreach(_.start())
        threads.foreach(_.join())
        next(request)
    }
    val model = ScriptedLLMClient.of(CompletionFixture.simple("never sent"))
    val agent = built(Agent.builder("assistant", model).withMiddleware(fanOut))

    val result = agent.run(thread, "go", limited(50)).value

    result.status shouldBe AgentStatus.TokenLimitReached
    made.get shouldBe 2
    model.callCount shouldBe 0
    tokens(result.usage) shouldBe 60L
  }

  it should "estimate a usage-less tool-only answer from its whole message, its tool call's arguments included" in {
    // no text: the answer is one tool call with large arguments, which the estimate must charge as output
    val big    = ToolCall("c1", "echo", ujson.Obj("text" -> ("lorem ipsum dolor sit amet " * 200)))
    val client = ScriptedLLMClient.of(calling(big).withUsage(None), CompletionFixture.simple("never sent"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    val result = agent.run(thread, "go", limited(500)).value

    result.status shouldBe AgentStatus.TokenLimitReached
    client.callCount shouldBe 1
    result.usage.outputTokens should be > 500L
  }

  it should "estimate a usage-less call's input from the whole request, its tool definitions included" in {
    import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec }
    // a tool whose definition alone is about 2000 tokens, sent on every call
    val schema = SpecTools.spec("big")
    val big = AgentTool(AgentToolSpec[SpecTools.Text]("big", "word " * 2000, schema.schema))((a, _) =>
      ToolOutcome.Success(ToolContent.Json(ujson.Str(a.text)))
    )
    val client =
      ScriptedLLMClient.of(calling(call("c1", "big")).withUsage(None), CompletionFixture.simple("never sent"))
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(big)))

    val result = agent.run(thread, "go", limited(1000)).value

    result.status shouldBe AgentStatus.TokenLimitReached
    client.callCount shouldBe 1
    result.usage.inputTokens should be > 1000L
  }

  it should "count a response schema set through the completion options in a usage-less call's input" in {
    // about 2000 tokens of schema, sent with the request though the agent declares no output format
    val schema  = ujson.Obj("type" -> "object", "description" -> ("word " * 2000))
    val options = CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(schema))
    val client  = ScriptedLLMClient.of(CompletionFixture.simple("done").withUsage(None))
    val agent   = built(Agent.builder("assistant", client).withCompletionOptions(options))

    agent.run(thread, "go", limited(5000)).value.usage.inputTokens should be > 1000L
  }

  it should "never fail a call it estimates, whatever a custom client leaves null" in {
    val malformed = CompletionFixture
      .simple("ok")
      .withMessage(AssistantMessage(Some("ok"), null))
      .withUsage(None)
    val judge   = ScriptedLLMClient.of(malformed, malformed.withMessage(null))
    val outcome = new java.util.concurrent.atomic.AtomicReference[Option[Boolean]](None)
    val judging = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("judge")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] =
        val client = context.accounted(judge)
        // a null tool-call list, then a null message
        val both = (1 to 2).map(_ => client.complete(Conversation(Seq(UserMessage("judge")))).isRight)
        outcome.set(Some(both.forall(identity)))
        next(request)
    }
    val agent =
      built(Agent.builder("assistant", ScriptedLLMClient.of(CompletionFixture.simple("done"))).withMiddleware(judging))

    val result = agent.run(thread, "go", limited(100000)).value

    outcome.get shouldBe Some(true)
    result.answer shouldBe Some("done")
    result.usage.requestCount shouldBe 3

    // a null message list with calls only on the completion: the calls' arguments are still charged
    val big     = ToolCall("c1", "echo", ujson.Obj("text" -> ("lorem ipsum dolor sit amet " * 200)))
    val topOnly = malformed.withToolCalls(List(big))
    ModelContext.estimated(topOnly, Seq(UserMessage("go")), Nil).completionTokens should be > 500
    // null option fields contribute nothing to the definitions, never an exception
    ModelContext.definitions(null, null) shouldBe Nil
  }
}
