package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.Text
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, ApprovalMiddleware, MiddlewareId, ToolCallRequest }
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContext, ToolOutcome }
import org.llm4s.agent.graph.toolloop.ToolResultRule
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ AnthropicConfig, ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.{ AnthropicClient, OpenAIClient }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.{ LocalProviderTestServer, ToolMessageFormat, ToolResultContract }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, TimeUnit }
import scala.jdk.CollectionConverters._

/**
 * The provider contract suite of design §6 (Stage 2): the tool loop, run through the real OpenAI and
 * Anthropic clients against a local server, sends exactly one result per tool call in both formats
 * after every outcome of a call - success, a tool error, a throw, an unknown tool, invalid
 * arguments, denial, rejection, an edited or approved call, an answered question, a refused handoff
 * batch, a handoff, provider ids reused across turns, and a batch cut short by cancellation, a
 * `Fatal` or a crash and finished by `recover`. Every request the server receives is checked with
 * `ToolResultContract.violations`, and must carry every call the model issued so far, each with
 * its result: a client that dropped an unanswered call would hide a dangling one.
 *
 * The suite also checks idempotency keys: one per call, the same in every run of the call, and a
 * new one for a call of another model request even when the provider reuses its id.
 */
class AgentToolResultContractSpec extends AnyFlatSpec with Matchers {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  /** What the scripted provider answers a request with. */
  private enum Reply:
    case Says(text: String)
    case Calls(calls: ToolCall*)

  /** A wire format: how to script its responses and build its client. */
  sealed abstract private class Wire(val name: String, val format: ToolMessageFormat, val path: String) {
    def reply(r: Reply): String
    def client(baseUrl: String): LLMClient
  }

  private object OpenAIWire extends Wire("OpenAI", ToolMessageFormat.OpenAIChat, "/") {
    def reply(r: Reply): String = r match {
      case Reply.Says(text) => LocalProviderTestServer.openAICompletion(text, "gpt-4o")
      case Reply.Calls(calls*) =>
        val toolCalls = calls.map { c =>
          ujson.Obj(
            "id"       -> c.id,
            "type"     -> "function",
            "function" -> ujson.Obj("name" -> c.name, "arguments" -> c.arguments.render())
          )
        }
        ujson
          .Obj(
            "id"      -> "chatcmpl-calls",
            "object"  -> "chat.completion",
            "created" -> 1700000000,
            "model"   -> "gpt-4o",
            "choices" -> ujson.Arr(
              ujson.Obj(
                "index"         -> 0,
                "message"       -> ujson.Obj("role" -> "assistant", "content" -> ujson.Null, "tool_calls" -> toolCalls),
                "finish_reason" -> "tool_calls"
              )
            ),
            "usage" -> ujson.Obj("prompt_tokens" -> 10, "completion_tokens" -> 5, "total_tokens" -> 15)
          )
          .render()
    }
    def client(baseUrl: String): LLMClient =
      OpenAIConfig.fromValues("gpt-4o", "sk-test", None, baseUrl).flatMap(OpenAIClient(_)) match {
        case Right(client) => client
        case Left(error)   => fail(s"could not build an OpenAIClient: ${error.message}")
      }
  }

  private object AnthropicWire extends Wire("Anthropic", ToolMessageFormat.AnthropicMessages, "/v1/messages") {
    private val model = "claude-sonnet-4-5"
    def reply(r: Reply): String = r match {
      case Reply.Says(text) => LocalProviderTestServer.anthropicMessage(text, model)
      case Reply.Calls(calls*) =>
        val uses =
          calls.map(c => ujson.Obj("type" -> "tool_use", "id" -> c.id, "name" -> c.name, "input" -> c.arguments))
        ujson
          .Obj(
            "id"            -> "msg_calls",
            "type"          -> "message",
            "role"          -> "assistant",
            "model"         -> model,
            "content"       -> uses,
            "stop_reason"   -> "tool_use",
            "stop_sequence" -> ujson.Null,
            "usage"         -> ujson.Obj("input_tokens" -> 10, "output_tokens" -> 5)
          )
          .render()
    }
    def client(baseUrl: String): LLMClient =
      new AnthropicClient(
        AnthropicConfig(
          apiKey = "test-key",
          model = model,
          baseUrl = baseUrl,
          contextWindow = 200000,
          reserveCompletion = 4096
        )
      )
  }

  /**
   * Runs `test` with a client of `wire` over a local server that answers request n with
   * `replies(n)`, then checks every request it received against the contract. Request n must carry
   * every call that `replies(0)` to `replies(n - 1)` issued, with one result each.
   */
  private def scripted(wire: Wire, replies: Reply*)(test: (LLMClient, () => Int) => Any): Unit = {
    val bodies = new ConcurrentLinkedQueue[ujson.Value]()
    LocalProviderTestServer.withServer(wire.path) { exchange =>
      val body = ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      bodies.add(body)
      val reply = replies.lift(bodies.size - 1).getOrElse(Reply.Says("unscripted"))
      LocalProviderTestServer.sendJsonResponse(exchange, 200, wire.reply(reply))
    }(baseUrl => test(wire.client(baseUrl), () => bodies.size))

    val issued = replies.map {
      case Reply.Calls(calls*) => calls.size
      case Reply.Says(_)       => 0
    }
    bodies.asScala.toVector.zipWithIndex.foreach { (body, n) =>
      withClue(s"${wire.name} request $n: ") {
        ToolResultContract.violations(wire.format, body) shouldBe empty
        ToolResultContract.toolCallCount(wire.format, body) shouldBe issued.take(n).sum
        ToolResultContract.toolResultCount(wire.format, body) shouldBe issued.take(n).sum
      }
    }
  }

  /** The idempotency key each run of each call saw, in order. */
  final private class Keys {
    private val seen                               = new ConcurrentLinkedQueue[(String, IdempotencyKey)]()
    def record(context: ToolContext): Unit         = seen.add(context.toolCallId.value -> context.idempotencyKey): Unit
    def of(callId: String): Vector[IdempotencyKey] = seen.asScala.toVector.collect { case (`callId`, k) => k }
    def all: Vector[IdempotencyKey]                = seen.asScala.toVector.map(_._2)
  }

  private def tool(name: String, keys: Keys)(run: (Text, ToolContext) => ToolOutcome): AgentTool[Text] =
    SpecTools.tool(name) { (args, context) =>
      keys.record(context)
      run(args, context)
    }

  private def text(t: String): ujson.Value = ujson.Obj("text" -> t)

  private def toolCall(id: String, name: String, args: ujson.Value = text("x")): ToolCall = ToolCall(id, name, args)

  /** The stored conversation itself keeps the rule. */
  private def wellFormed(result: AgentResult): Unit =
    ToolResultRule.violations(result.messages) shouldBe empty

  /** Records which call-tool pending writes reached the store, and can stop storing anything, as a dying process. */
  final private class ProbeCheckpointer(underlying: Checkpointer) extends Checkpointer {
    val toolWrites = new AtomicInteger()
    val dead       = new AtomicBoolean(false)
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
      if (dead.get) Left(ValidationError("store", "the process died"))
      else
        underlying.commit(threadId, commit).map { records =>
          toolWrites.addAndGet(commit.pendingWrites.count(_.nodeId.endsWith("/call-tool"))): Unit
          records
        }
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] =
      underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit] = underlying.deleteThread(threadId)
  }

  private def await(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (!condition && System.nanoTime() < deadline) Thread.sleep(5)
    if (!condition) fail("timed out waiting")
  }

  final case class Confirm(prompt: String) derives ReadWriter
  final case class Answer(ok: Boolean) derives ReadWriter

  private val denyDanger = new AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("deny-danger")
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      if (request.call.name == "danger") ToolOutcome.Error("Denied: danger is not allowed") else next()
  }

  Seq[Wire](OpenAIWire, AnthropicWire).foreach { wire =>
    val name = wire.name

    s"[$name] A parallel batch" should "answer success, error, throw, unknown tool and invalid arguments, one result each" in {
      val keys = new Keys
      val ok   = tool("ok", keys)((_, _) => ToolOutcome.Success(ujson.Str("fine")))
      val err  = tool("err", keys)((_, _) => ToolOutcome.Error("broken"))
      val boom = tool("boom", keys)((_, _) => throw new RuntimeException("boom"))
      scripted(
        wire,
        Reply.Calls(
          toolCall("c1", "ok"),
          toolCall("c2", "err"),
          toolCall("c3", "boom"),
          toolCall("c4", "nope"),
          toolCall("c5", "ok", ujson.Obj())
        ),
        Reply.Says("done")
      ) { (client, requests) =>
        val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(ok, err, boom)))
        val result = agent.run("go").value
        result.answer shouldBe Some("done")
        requests() shouldBe 2
        wellFormed(result)
        result.messages.collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("c1", "c2", "c3", "c4", "c5")
      }
      keys.all.foreach(_.value should fullyMatch regex "[0-9a-f]{64}")
      keys.all.distinct should have size 3
    }

    it should "send nothing while a call waits for approval, then answer denial, approval, rejection and edit" in {
      val keys = new Keys
      val ran  = new ConcurrentLinkedQueue[String]()
      val deploy = tool("deploy", keys) { (args, _) =>
        ran.add(args.text)
        ToolOutcome.Success(ujson.Str("deployed"))
      }
      val danger = tool("danger", keys)((_, _) => ToolOutcome.Success(ujson.Str("never")))
      scripted(
        wire,
        Reply.Calls(
          toolCall("d1", "danger"),
          toolCall("a1", "deploy", text("alpha")),
          toolCall("r1", "deploy", text("rho")),
          toolCall("e1", "deploy", text("epsilon"))
        ),
        Reply.Says("reviewed")
      ) { (client, requests) =>
        val agent = built(
          Agent
            .builder("assistant", client)
            .withTools(SpecTools.set(deploy, danger))
            .withMiddleware(denyDanger, new ApprovalMiddleware(r => Option.when(r.call.name == "deploy")("review")))
        )
        val parked = agent.run("ship it").value
        val approvals = parked.status match {
          case AgentStatus.Suspended(approvals, _) => approvals.map((id, r) => r.call.id -> (id, r)).toMap
          case other                               => fail(s"expected Suspended, got $other")
        }
        approvals.keySet shouldBe Set("a1", "r1", "e1")
        approvals.values.map(_._2.idempotencyKey).toSet should have size 3

        // a partial resume runs the approved call, and the model is still not asked
        val partial = agent.resume(parked.threadId, Map(parked.approve(approvals("a1")._1))).value
        partial.status shouldBe a[AgentStatus.Suspended]
        requests() shouldBe 1

        val done = agent
          .resume(
            parked.threadId,
            Map(
              partial.reject(approvals("r1")._1, "not today"),
              partial.edit(approvals("e1")._1, text("edited"))
            )
          )
          .value
        done.answer shouldBe Some("reviewed")
        requests() shouldBe 2
        wellFormed(done)
        ran.asScala.toVector.sorted shouldBe Vector("alpha", "edited")
        // the approved and the edited call each ran with the key their approval request carried
        keys.of("a1").distinct shouldBe Vector(approvals("a1")._2.idempotencyKey)
        keys.of("e1").distinct shouldBe Vector(approvals("e1")._2.idempotencyKey)
      }
    }

    it should "answer a question, then send every result" in {
      val keys = new Keys
      val asker = new AgentTool.Asking[Text, Confirm, Answer](SpecTools.spec("confirm")) {
        def execute(args: Text, context: ToolContext): ToolOutcome = {
          keys.record(context)
          ask(Confirm(s"really ${args.text}?"))
        }
        def resume(args: Text, question: Confirm, answer: Answer, context: ToolContext): ToolOutcome = {
          keys.record(context)
          ToolOutcome.Success(ujson.Str(s"${question.prompt} ${answer.ok}"))
        }
      }
      val ok = tool("ok", keys)((_, _) => ToolOutcome.Success(ujson.Str("fine")))
      scripted(wire, Reply.Calls(toolCall("q1", "confirm"), toolCall("k1", "ok")), Reply.Says("confirmed")) {
        (client, requests) =>
          val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(asker, ok)))
          val parked = agent.run("ask").value
          val (id, question) = parked.status match {
            case AgentStatus.Suspended(_, questions) => questions.head
            case other                               => fail(s"expected Suspended, got $other")
          }
          requests() shouldBe 1
          val done = agent.resume(parked.threadId, Map(parked.reply(id, Answer(true)))).value
          done.answer shouldBe Some("confirmed")
          wellFormed(done)
          keys.of("q1") shouldBe Vector(question.idempotencyKey, question.idempotencyKey)
      }
    }

    it should "answer every call of a refused handoff batch, then hand off" in {
      val keys    = new Keys
      val lookup  = tool("lookup", keys)((_, _) => ToolOutcome.Success(ujson.Str("found")))
      val refund  = tool("refund", keys)((_, _) => ToolOutcome.Success(ujson.Str("refunded")))
      val handoff = ujson.Obj("reason" -> "refund")
      scripted(
        wire,
        Reply.Calls(toolCall("h1", "handoff_to_billing", handoff), toolCall("l1", "lookup")),
        Reply.Calls(toolCall("h2", "handoff_to_billing", handoff)),
        Reply.Calls(toolCall("f1", "refund")),
        Reply.Says("refunded")
      ) { (client, requests) =>
        val billing = Agent.builder("billing", client).withTools(SpecTools.set(refund))
        val agent = built(
          Agent
            .builder("assistant", client)
            .withTools(SpecTools.set(lookup))
            .withHandoffs(Handoff.to("billing", billing, "refunds"))
        )
        val result = agent.run("refund me").value
        result.answer shouldBe Some("refunded")
        result.activeAgent.value shouldBe "billing"
        requests() shouldBe 4
        wellFormed(result)
      }
      keys.of("l1") shouldBe empty // a refused batch runs nothing
    }

    it should "pair results with calls when the provider reuses call ids, and give each call its own key" in {
      val keys   = new Keys
      val lookup = tool("lookup", keys)((args, _) => ToolOutcome.Success(ujson.Str(s"found ${args.text}")))
      scripted(
        wire,
        Reply.Calls(toolCall("call_0", "lookup", text("one"))),
        Reply.Says("first"),
        Reply.Calls(toolCall("call_0", "lookup", text("two"))),
        Reply.Says("second")
      ) { (client, requests) =>
        val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(lookup)))
        val thread = ThreadId(s"reused-${wire.name}")
        agent.run(thread, "one").value.answer shouldBe Some("first")
        val second = agent.run(thread, "two").value
        second.answer shouldBe Some("second")
        requests() shouldBe 4
        wellFormed(second)
      }
      val reused = keys.of("call_0")
      reused should have size 2
      reused.distinct should have size 2
    }

    it should "send no request with a call cut short by cancellation; recover re-runs only that call, with its key" in {
      val keys    = new Keys
      val fastRan = new AtomicInteger()
      val block   = new AtomicBoolean(true)
      val started = new CountDownLatch(1)
      val fast = tool("fast", keys) { (_, _) =>
        fastRan.incrementAndGet()
        ToolOutcome.Success(ujson.Str("fast"))
      }
      val slow = tool("slow", keys) { (_, _) =>
        if (block.get) {
          started.countDown()
          Thread.sleep(60_000) // interrupted by cancel
        }
        ToolOutcome.Success(ujson.Str("slow"))
      }
      val store = new ProbeCheckpointer(new InMemoryCheckpointer)
      scripted(wire, Reply.Calls(toolCall("f1", "fast"), toolCall("s1", "slow")), Reply.Says("recovered")) {
        (client, requests) =>
          val agent = built(
            Agent.builder("assistant", client).withTools(SpecTools.set(fast, slow)).withRuntime(new GraphRuntime(store))
          )
          val thread = ThreadId(s"cancelled-${wire.name}")
          val run    = agent.start(thread, "go").fold(e => fail(e.message), identity)
          started.await(10, TimeUnit.SECONDS) shouldBe true
          await(store.toolWrites.get >= 1) // the fast sibling's result is durable
          run.cancel()
          cause(run.await().error) shouldBe a[GraphError.Cancelled]
          requests() shouldBe 1

          block.set(false)
          val done = agent.recover(thread).value
          done.answer shouldBe Some("recovered")
          requests() shouldBe 2
          wellFormed(done)
      }
      fastRan.get shouldBe 1
      keys.of("s1") should have size 2
      keys.of("s1").distinct should have size 1
    }

    it should "send no request with a call that failed Fatal; recover re-runs only that call, with its key" in {
      val keys    = new Keys
      val broken  = new AtomicBoolean(true)
      val goodRan = new AtomicInteger()
      val good = tool("good", keys) { (_, _) =>
        goodRan.incrementAndGet()
        ToolOutcome.Success(ujson.Str("good"))
      }
      val flaky = tool("flaky", keys) { (_, _) =>
        if (broken.get) ToolOutcome.Fatal(ValidationError("flaky", "backend down"))
        else ToolOutcome.Success(ujson.Str("fixed"))
      }
      scripted(wire, Reply.Calls(toolCall("g1", "good"), toolCall("x1", "flaky")), Reply.Says("all done")) {
        (client, requests) =>
          val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(good, flaky)))
          val thread = ThreadId(s"fatal-${wire.name}")
          cause(agent.run(thread, "go").error) shouldBe a[GraphError.ToolFailed]
          requests() shouldBe 1
          broken.set(false)
          val done = agent.recover(thread).value
          done.answer shouldBe Some("all done")
          wellFormed(done)
      }
      goodRan.get shouldBe 1
      keys.of("x1") should have size 2
      keys.of("x1").distinct should have size 1
    }

    it should "survive a crash: another runtime recovers, never replaying a completed call" in {
      val keys    = new Keys
      val doneRan = new AtomicInteger()
      val healthy = new InMemoryCheckpointer
      val dying   = new ProbeCheckpointer(healthy)
      val done = tool("done", keys) { (_, _) =>
        doneRan.incrementAndGet()
        ToolOutcome.Success(ujson.Str("done"))
      }
      // the first time, waits for its sibling's result to be stored, then the process "dies"
      val crashed = new AtomicBoolean(false)
      val crasher = tool("crasher", keys) { (_, _) =>
        if (crashed.compareAndSet(false, true)) {
          await(dying.toolWrites.get >= 1)
          dying.dead.set(true)
        }
        ToolOutcome.Success(ujson.Str("crashed once"))
      }
      scripted(wire, Reply.Calls(toolCall("d1", "done"), toolCall("k1", "crasher")), Reply.Says("after the crash")) {
        (client, requests) =>
          def agentOver(store: Checkpointer) =
            built(
              Agent
                .builder("assistant", client)
                .withTools(SpecTools.set(done, crasher))
                .withRuntime(new GraphRuntime(store))
            )
          val thread = ThreadId(s"crash-${wire.name}")
          agentOver(dying).run(thread, "go").isLeft shouldBe true
          requests() shouldBe 1

          val recovered = agentOver(healthy).recover(thread).value
          recovered.answer shouldBe Some("after the crash")
          requests() shouldBe 2
          wellFormed(recovered)
      }
      doneRan.get shouldBe 1
      keys.of("k1") should have size 2
      keys.of("k1").distinct should have size 1
    }
  }
}
