package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.events.{ AgentEvents, ModelCallCompleted }
import org.llm4s.agent.graph.{
  CheckpointStatus,
  EventRecord,
  GraphError,
  GraphRuntime,
  InMemoryCheckpointer,
  RunEvent,
  ThreadId
}
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  GuardrailMiddleware,
  MiddlewareId,
  ModelContext,
  ModelRequest,
  ModelResponse,
  PromptSection
}
import org.llm4s.agent.graph.tool.{ ToolArgumentValidator, ToolOutcome, ToolSet }
import org.llm4s.agent.graph.toolloop.OutputFormat
import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ NumberSchema, ObjectSchema, PropertyDefinition, SchemaDefinition, StringSchema }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.atomic.AtomicInteger

/** The response format of an agent's answer (#1912, design 4.26): `withOutput`, validation, the retry, `decodeOutput`. */
class AgentOutputSpec extends AnyFlatSpec with Matchers {
  import AgentOutputSpec._

  private def answers(texts: String*): ScriptedLLMClient = ScriptedLLMClient.of(texts.map(CompletionFixture.simple)*)

  private def withFormat(client: ScriptedLLMClient): AgentBuilder =
    Agent.builder("extractor", client).withOutput(invoiceSchema)

  private val valid   = """{"customer":"Acme","total":12.5}"""
  private val invalid = """{"customer":"Acme","total":"lots"}"""

  /** The durable `ModelCallCompleted` events of `threadId`, in order. */
  private def modelCallsCompleted(store: InMemoryCheckpointer, threadId: ThreadId): Vector[ModelCallCompleted] =
    store
      .eventsAfter(threadId, 0L, 1000)
      .fold(e => fail(e.message), identity)
      .collect {
        case EventRecord(_, _, _, _, _, _, _, RunEvent.Custom(AgentEvents.ModelCallCompleted.name, _, payload)) =>
          AgentEvents.ModelCallCompleted.decode(payload).getOrElse(fail(s"undecodable payload $payload"))
      }

  private val format = Some(ResponseFormat.JsonSchema(invoiceSchema.toJsonSchema(strict = true)))

  "withOutput" should "send the schema as the response format on every call that offers no tools, the retry included" in {
    val client = answers(invalid, valid)
    built(withFormat(client)).run("bill Acme").value

    client.calls.map(_._2.responseFormat) shouldBe Vector(format, format)
  }

  // a format on a request that offers tools conflicts with tool use for several providers (Codex, #1924)
  it should "send no response format on a call that offers the agent's tools, so the model can still call them" in {
    val echo   = SpecTools.tool("echo")((a, _) => ToolOutcome.Success(ujson.Str(a.text)))
    val client = new ScriptedLLMClient(Right(calling(call("c1", "echo"))), Right(CompletionFixture.simple(valid)))
    val result = built(withFormat(client).withTools(SpecTools.set(echo))).run("bill Acme").value

    result.decodeOutput[Invoice] shouldBe Right(Invoice("Acme", 12.5))
    client.calls.map(_._2.tools.nonEmpty) shouldBe Vector(true, true)
    client.calls.map(_._2.responseFormat) shouldBe Vector(None, None)
    // the prompt still states the schema on every call
    client.sent.foreach(_.head.content should include("JSON Schema"))
  }

  it should "send no response format on a call that offers only a handoff" in {
    val client = answers(valid)
    built(withFormat(client).withHandoffs(Handoff.to("helper", Agent.builder("helper", client)))).run("bill Acme").value

    client.calls.head._2.tools.map(_.name) shouldBe Seq("handoff_to_helper")
    client.calls.head._2.responseFormat shouldBe None
  }

  it should "send the format when a model wrapper removes the tools from the request" in {
    val echo = SpecTools.tool("echo")((a, _) => ToolOutcome.Success(ujson.Str(a.text)))
    val toolless = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("toolless")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] = next(request.withTools(ToolSet.empty))
    }
    val client = answers(valid)
    built(withFormat(client).withTools(SpecTools.set(echo)).withMiddleware(toolless)).run("bill Acme").value

    client.calls.head._2.responseFormat shouldBe format
  }

  it should "send no response format of the completion options on a tool call either" in {
    val echo   = SpecTools.tool("echo")((a, _) => ToolOutcome.Success(ujson.Str(a.text)))
    val client = new ScriptedLLMClient(Right(calling(call("c1", "echo"))), Right(CompletionFixture.simple(valid)))
    built(
      withFormat(client)
        .withTools(SpecTools.set(echo))
        .withCompletionOptions(CompletionOptions().withResponseFormat(ResponseFormat.Json))
    ).run("bill Acme").value

    client.calls.map(_._2.responseFormat) shouldBe Vector(None, None)
  }

  it should "replace a response format given with the completion options" in {
    val client = answers(valid)
    built(
      Agent
        .builder("extractor", client)
        .withCompletionOptions(CompletionOptions().withResponseFormat(ResponseFormat.Json))
        .withOutput(invoiceSchema)
    ).run("bill Acme").value

    client.calls.head._2.responseFormat shouldBe Some(ResponseFormat.JsonSchema(invoiceSchema.toJsonSchema(true)))
  }

  it should "state the schema in the system prompt, after the agent's own, without storing it" in {
    val client = answers(valid)
    val result = built(withFormat(client).withSystemPrompt("You extract invoices.")).run("bill Acme").value

    val system = client.sent.head.collect { case s: SystemMessage => s.content }
    system should have size 1
    system.head should startWith("You extract invoices.")
    system.head should include(ujson.write(invoiceSchema.toJsonSchema(strict = true)))
    result.messages.collect { case s: SystemMessage => s } shouldBe empty
  }

  it should "state the schema in a system prompt of its own when the agent has none" in {
    val client = answers(valid)
    built(withFormat(client)).run("bill Acme").value

    client.sent.head.head shouldBe a[SystemMessage]
    client.sent.head.head.content should include("JSON Schema")
  }

  "A valid answer" should "complete the turn and decode as the output type" in {
    val result = built(withFormat(answers(valid))).run("bill Acme").value

    result.answer shouldBe Some(valid)
    result.decodeOutput[Invoice] shouldBe Right(Invoice("Acme", 12.5))
  }

  it should "be stored as its JSON alone, without the fence or prose the model put round it" in {
    val result = built(withFormat(answers(s"Here it is:\n```json\n$valid\n```"))).run("bill Acme").value

    result.answer shouldBe Some(valid)
    result.messages.last shouldBe AssistantMessage(valid)
  }

  "An invalid answer" should "be retried once, with the answer and its problems, and the exchange not stored" in {
    val client = answers(invalid, valid)
    val result = built(withFormat(client)).run("bill Acme").value

    result.decodeOutput[Invoice] shouldBe Right(Invoice("Acme", 12.5))
    client.callCount shouldBe 2
    val retry = client.sent(1)
    retry.dropRight(2) shouldBe client.sent(0)
    retry(retry.size - 2) shouldBe AssistantMessage(invalid)
    retry.last shouldBe a[UserMessage]
    retry.last.content should include("$.total")
    result.messages shouldBe Vector(UserMessage("bill Acme"), AssistantMessage(valid))
  }

  it should "count both model calls, each with its own ModelCallCompleted" in {
    val store = new InMemoryCheckpointer()
    val client = ScriptedLLMClient.of(
      CompletionFixture.withUsage(invalid, prompt = 5, completion = 7),
      CompletionFixture.withUsage(valid, prompt = 11, completion = 13)
    )
    val thread = ThreadId("counted")
    val result = built(withFormat(client).withRuntime(new GraphRuntime(store))).run(thread, "bill Acme").value

    result.usage.requestCount shouldBe 2
    (result.usage.inputTokens, result.usage.outputTokens) shouldBe (16L, 20L)
    modelCallsCompleted(store, thread).map(c => c.attempts -> c.usage.map(_.totalTokens)) shouldBe
      Vector(1 -> Some(12), 2 -> Some(24))
  }

  it should "count a blank answer as invalid, and retry it" in {
    val result = built(withFormat(answers("  ", valid))).run("bill Acme").value
    result.decodeOutput[Invoice] shouldBe Right(Invoice("Acme", 12.5))
  }

  it should "include an answer that matches the schema but does not read as the output type" in {
    val client  = answers("""{"customer":"Acme"}""", valid)
    val partial = Agent.builder("extractor", client).withOutput(partialSchema)
    built(partial).run("bill Acme").value.decodeOutput[Invoice] shouldBe Right(Invoice("Acme", 12.5))
    client.callCount shouldBe 2
  }

  it should "route the corrective call's tool calls as usual" in {
    val echo = SpecTools.tool("echo")((a, _) => ToolOutcome.Success(ujson.Str(a.text)))
    val client = new ScriptedLLMClient(
      Right(CompletionFixture.simple(invalid)),
      Right(calling(call("c1", "echo"))),
      Right(CompletionFixture.simple(valid))
    )
    val result = built(withFormat(client).withTools(SpecTools.set(echo))).run("bill Acme").value

    result.decodeOutput[Invoice] shouldBe Right(Invoice("Acme", 12.5))
    result.usage.requestCount shouldBe 3
    // the rejected answer is not in the thread; the corrective call's tool call is
    result.messages.collect { case a: AssistantMessage => a.content } shouldBe Vector("", valid)
  }

  it should "leave the thread for recover when the corrective call fails, which makes both calls again" in {
    val client = new ScriptedLLMClient(
      Right(CompletionFixture.simple(invalid)),
      Left(ValidationError("provider", "unavailable")),
      Right(CompletionFixture.simple(invalid)),
      Right(CompletionFixture.simple(valid))
    )
    val store  = new InMemoryCheckpointer()
    val agent  = built(withFormat(client).withRuntime(new GraphRuntime(store)))
    val thread = ThreadId("corrective-failed")

    cause(agent.run(thread, "bill Acme").error) shouldBe ValidationError("provider", "unavailable")
    // the failed task commits nothing, but the first call is counted on the durable RunFailed (rule 11)
    val failed = store
      .eventsAfter(thread, 0L, 1000)
      .fold(e => fail(e.message), identity)
      .collect { case EventRecord(_, _, _, _, _, _, _, f: RunEvent.RunFailed) => f }
    failed.flatMap(_.usage).map(_.requestCount) shouldBe Vector(1L)
    val recovered = agent.recover(thread).value
    recovered.decodeOutput[Invoice] shouldBe Right(Invoice("Acme", 12.5))
    client.callCount shouldBe 4
  }

  // every provider call of the step - a wrapper's retries and the corrective request's included - is recorded once,
  // numbered within the step, on every path the step ends by (Codex, #1924; C1's accounting, design §4.22)

  /** Calls the model `retries + 1` times on the first request (keeping the last), once on later ones. */
  final private class Retrying(retries: Int) extends AgentMiddleware {
    val requests         = new AtomicInteger(0)
    val id: MiddlewareId = MiddlewareId("retrying")
    override def wrapModelCall(request: ModelRequest, context: ModelContext)(
      next: ModelRequest => Result[ModelResponse]
    ): Result[ModelResponse] =
      if (requests.incrementAndGet() == 1) (1 to retries).foldLeft(next(request))((_, _) => next(request))
      else next(request)
  }

  "Each provider call" should "be recorded once, numbered within the step, when the corrective answer is stored" in {
    val store  = new InMemoryCheckpointer()
    val thread = ThreadId("attempts-stored")
    val agent = built(
      withFormat(answers(invalid, invalid, valid)).withMiddleware(new Retrying(1)).withRuntime(new GraphRuntime(store))
    )
    agent.run(thread, "bill Acme").value.decodeOutput[Invoice] shouldBe Right(Invoice("Acme", 12.5))
    modelCallsCompleted(store, thread).map(_.attempts) shouldBe Vector(1, 2, 3)
  }

  it should "be recorded once, numbered within the step, when the second answer is invalid too" in {
    val store  = new InMemoryCheckpointer()
    val thread = ThreadId("attempts-blocked")
    val agent = built(
      withFormat(answers(invalid, invalid, invalid))
        .withMiddleware(new Retrying(1))
        .withRuntime(new GraphRuntime(store))
    )
    agent.run(thread, "bill Acme").error shouldBe a[GraphError.OutputInvalid]
    modelCallsCompleted(store, thread).map(_.attempts) shouldBe Vector(1, 2, 3)
  }

  it should "be recorded once, numbered within the step, when a wrapper asks during the corrective request" in {
    val store  = new InMemoryCheckpointer()
    val thread = ThreadId("attempts-asked")
    // asks after the corrective request's call, so the step suspends with both calls' usage
    val reviewer = new AgentMiddleware.Asking[String, Boolean] {
      val requests         = new AtomicInteger(0)
      val id: MiddlewareId = MiddlewareId("reviewer")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] =
        if (requests.incrementAndGet() == 2) next(request).flatMap(r => ask(r.completion.message.content))
        else next(request)
    }
    val agent = built(withFormat(answers(invalid, valid)).withMiddleware(reviewer).withRuntime(new GraphRuntime(store)))
    val parked = agent.run(thread, "bill Acme").value

    parked.status shouldBe a[AgentStatus.Suspended]
    parked.usage.requestCount shouldBe 2
    modelCallsCompleted(store, thread).map(_.attempts) shouldBe Vector(1, 2)
  }

  "A second invalid answer" should "end the turn Left(OutputInvalid) with the problems, retracting the turn" in {
    val store  = new InMemoryCheckpointer()
    val client = answers(valid, invalid, "not json at all", valid)
    val agent  = built(withFormat(client).withRuntime(new GraphRuntime(store)))
    val thread = ThreadId("invalid")

    val first = agent.run(thread, "bill Acme").value
    val error = agent.run(thread, "bill Beta").error

    error shouldBe a[GraphError.OutputInvalid]
    val problems = error.asInstanceOf[GraphError.OutputInvalid].errors
    problems should not be empty
    problems.head should include("not JSON")
    // the message goes into durable events: it names no problem, since problems quote the answer
    (error.message should not).include("not json at all")

    // the thread closed Failed, holding the history from before the turn
    val latest = store.latest(thread).fold(e => fail(e.message), identity).getOrElse(fail("no checkpoint"))
    latest.checkpoint.status shouldBe CheckpointStatus.Failed
    val state = agent.loop.graph.restore(latest.checkpoint.snapshot).fold(e => fail(e.message), _.state)
    state.get(org.llm4s.agent.graph.toolloop.Messages.key).map(_.map(_.message)) shouldBe Right(first.messages)

    // recover has nothing to continue; the next turn runs from before the blocked one
    agent.recover(thread).error shouldBe GraphError.NothingToRecover(thread.value)
    val next = agent.run(thread, "bill Gamma").value
    next.decodeOutput[Invoice] shouldBe Right(Invoice("Acme", 12.5))
    next.messages.collect { case u: UserMessage => u.content } shouldBe Vector("bill Acme", "bill Gamma")
    client.sent(3).collect { case u: UserMessage => u.content } shouldBe Vector("bill Acme", "bill Gamma")
  }

  it should "keep both calls in the thread's usage and events" in {
    val store  = new InMemoryCheckpointer()
    val client = answers(invalid, invalid)
    val agent  = built(withFormat(client).withRuntime(new GraphRuntime(store)))
    val thread = ThreadId("accounted")

    agent.run(thread, "bill Acme").error shouldBe a[GraphError.OutputInvalid]

    modelCallsCompleted(store, thread) should have size 2
    val latest = store.latest(thread).fold(e => fail(e.message), identity).getOrElse(fail("no checkpoint"))
    val state  = agent.loop.graph.restore(latest.checkpoint.snapshot).fold(e => fail(e.message), _.state)
    state.get(org.llm4s.agent.graph.toolloop.LoopKeys.usage).map(_.requestCount) shouldBe Right(2)
  }

  it should "remove a turn's tool calls too" in {
    val echo = SpecTools.tool("echo")((a, _) => ToolOutcome.Success(ujson.Str(a.text)))
    val client = new ScriptedLLMClient(
      Right(calling(call("c1", "echo"))),
      Right(CompletionFixture.simple(invalid)),
      Right(CompletionFixture.simple(invalid)),
      Right(CompletionFixture.simple(valid))
    )
    val agent  = built(withFormat(client).withTools(SpecTools.set(echo)))
    val thread = ThreadId("tools-removed")

    agent.run(thread, "bill Acme").error shouldBe a[GraphError.OutputInvalid]
    val next = agent.run(thread, "bill Beta").value
    next.messages shouldBe Vector(UserMessage("bill Beta"), AssistantMessage(valid))
  }

  it should "restore every key the turn wrote, as an output Block does (design §4.22)" in {
    val files = org.llm4s.agent.graph.StateKey.replace[Map[String, String]]("files", Map.empty)
    val write = org.llm4s.agent.graph.tool.AgentTool(SpecTools.spec("write"), writes = Set(files)) { (a, context) =>
      val current = context.state.get(files).getOrElse(Map.empty)
      ToolOutcome.Success(
        ujson.Str("ok"),
        org.llm4s.agent.graph.StateUpdate.update(files, current + (a.text -> a.text))
      )
    }
    val store = new InMemoryCheckpointer()
    val client = new ScriptedLLMClient(
      Right(calling(call("c1", "write", "one"))),
      Right(CompletionFixture.simple(valid)),
      Right(calling(call("c2", "write", "two"))),
      Right(CompletionFixture.simple(invalid)),
      Right(CompletionFixture.simple(invalid))
    )
    val agent  = built(withFormat(client).withTools(SpecTools.set(write)).withRuntime(new GraphRuntime(store)))
    val thread = ThreadId("restored")

    agent.run(thread, "first").value
    agent.run(thread, "second").error shouldBe a[GraphError.OutputInvalid]

    val latest = store.latest(thread).fold(e => fail(e.message), identity).getOrElse(fail("no checkpoint"))
    val state  = agent.loop.graph.restore(latest.checkpoint.snapshot).fold(e => fail(e.message), _.state)
    state.get(files) shouldBe Right(Map("one" -> "one"))
    state.get(org.llm4s.agent.graph.toolloop.LoopKeys.turn).map(_.priorValues) shouldBe Right(Map.empty)
  }

  it should "restore them too when the step ran again after a wrapper's question" in {
    val files = org.llm4s.agent.graph.StateKey.replace[Map[String, String]]("files", Map.empty)
    val write = org.llm4s.agent.graph.tool.AgentTool(SpecTools.spec("write"), writes = Set(files)) { (a, context) =>
      val current = context.state.get(files).getOrElse(Map.empty)
      ToolOutcome.Success(
        ujson.Str("ok"),
        org.llm4s.agent.graph.StateUpdate.update(files, current + (a.text -> a.text))
      )
    }
    // asks about the first corrective request; once answered, it lets every request through
    val reviewer = new AgentMiddleware.Asking[String, Boolean] {
      val id: MiddlewareId = MiddlewareId("reviewer")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] =
        val corrective =
          request.messages.lastOption.exists(_.content.contains("did not match the required JSON Schema"))
        if (corrective && answered(context).isEmpty) next(request).flatMap(r => ask(r.completion.message.content))
        else next(request)
    }
    val store = new InMemoryCheckpointer()
    val scripted = new ScriptedLLMClient(
      (Right(calling(call("c1", "write", "one"))) +: Vector.fill(4)(Right(CompletionFixture.simple(invalid))))*
    )
    val agent = built(
      withFormat(scripted).withTools(SpecTools.set(write)).withMiddleware(reviewer).withRuntime(new GraphRuntime(store))
    )
    val thread = ThreadId("restored-after-question")

    val parked = agent.run(thread, "first").value
    val asked = parked.status match {
      case AgentStatus.Suspended(_, _, asked, _) => asked
      case other                                 => fail(s"expected Suspended, got $other")
    }
    agent.resume(thread, Map(parked.reply(asked.head._1, true))).error shouldBe a[GraphError.OutputInvalid]

    val latest = store.latest(thread).fold(e => fail(e.message), identity).getOrElse(fail("no checkpoint"))
    val state  = agent.loop.graph.restore(latest.checkpoint.snapshot).fold(e => fail(e.message), _.state)
    state.get(files) shouldBe Right(Map.empty)
    state.get(org.llm4s.agent.graph.toolloop.Messages.key).map(_.size) shouldBe Right(0)
  }

  "An output guardrail" should "see the validated JSON, and its Block keep both calls' usage" in {
    val seen = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val refuse: OutputGuardrail = new OutputGuardrail {
      val name: String = "Refuse"
      def validate(value: String): Result[String] =
        seen.add(value)
        Left(ValidationError.invalid("output", "no"))
    }
    val result = built(
      withFormat(answers(invalid, s"```json\n$valid\n```")).withMiddleware(new GuardrailMiddleware(Nil, Seq(refuse)))
    ).run("bill Acme").value

    result.status shouldBe a[AgentStatus.Blocked]
    seen.toArray.toVector shouldBe Vector(valid)
    result.usage.requestCount shouldBe 2
  }

  "A model wrapper" should "see the corrective request as a request of its own" in {
    val requests = new AtomicInteger(0)
    val counting = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("counting")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] =
        requests.incrementAndGet()
        next(request)
    }
    built(withFormat(answers(invalid, valid)).withMiddleware(counting)).run("bill Acme").value
    requests.get() shouldBe 2
  }

  "build" should "refuse a schema that is not a JSON object" in {
    val refused = Agent.builder("extractor", answers()).withOutput(StringSchema("a name")).build()
    refused.left.map(_.message).swap.getOrElse("") should include("object")
  }

  it should "refuse a schema keyword the agent's validator cannot check" in {
    // a validator that cannot check `minimum`, which the invoice schema's total uses
    val noMinimum = new ToolArgumentValidator {
      def unsupported(schema: ujson.Value): Vector[String] =
        ToolArgumentValidator.default.unsupported(schema) ++
          (if (ujson.write(schema).contains("\"minimum\"")) Vector("$.properties.total.minimum") else Vector.empty)
      def validate(schema: ujson.Value, arguments: ujson.Value): Vector[String] =
        ToolArgumentValidator.default.validate(schema, arguments)
    }
    val tools   = ToolSet.of(noMinimum).fold(e => fail(e.message), identity)
    val refused = Agent.builder("extractor", answers()).withTools(tools).withOutput(invoiceSchema).build()
    refused.left.map(_.message).swap.getOrElse("") should include("minimum")
  }

  it should "refuse a format whose prompt section alone is over the agent's prompt section budget" in {
    val huge = ObjectSchema[Invoice](
      "x" * 40000,
      Seq(PropertyDefinition("customer", StringSchema("who")))
    )
    Agent.builder("extractor", answers()).withOutput(huge).build().isLeft shouldBe true
    val small = Agent.builder("extractor", answers()).withOutput(invoiceSchema).withMaxPromptSectionTokens(10).build()
    small.left.map(_.message).swap.getOrElse("") should include("maxPromptSectionTokens of 10")
  }

  "The format's prompt section" should "count with the middleware's against the agent's budget at run time" in {
    val loud = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("loud")
      override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
        Right(Vector(PromptSection("loud", "y" * 4000)))
    }
    val budget = (OutputFormat.of(invoiceSchema).section.text.length + 3) / 4 + 10
    val agent  = built(withFormat(answers(valid)).withMiddleware(loud).withMaxPromptSectionTokens(budget))
    agent.run("bill Acme").error.message should include("response-format/response-format")
  }

  it should "come before the middleware's sections that are not cacheable" in {
    val note = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("note")
      override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
        Right(Vector(PromptSection("note", "A volatile note.")))
    }
    val client = answers(valid)
    built(withFormat(client).withSystemPrompt("You extract invoices.").withMiddleware(note)).run("bill Acme").value
    val system = client.sent.head.head.content
    system.indexOf("You extract invoices.") should be < system.indexOf("JSON Schema")
    system.indexOf("JSON Schema") should be < system.indexOf("A volatile note.")
  }

  "The graph version" should "change with the response format, and not without one" in {
    val client                   = answers()
    def version(b: AgentBuilder) = built(b).loop.graph.version
    val plain                    = Agent.builder("extractor", client)

    version(plain.withOutput(invoiceSchema)) should not be version(plain)
    version(plain.withOutput(partialSchema)) should not be version(plain.withOutput(invoiceSchema))
    version(plain) shouldBe version(Agent.builder("extractor", client))
  }

  "decodeOutput" should "be Left for a turn without an answer" in {
    val blocked = AgentResultFixture(AgentStatus.StepLimitReached)
    blocked.decodeOutput[Invoice].isLeft shouldBe true
  }

  it should "be Left for an answer that is not JSON, is null, or is not the type" in {
    AgentResultFixture(AgentStatus.Completed("hello")).decodeOutput[Invoice].isLeft shouldBe true
    AgentResultFixture(AgentStatus.Completed("null")).decodeOutput[Invoice].isLeft shouldBe true
    AgentResultFixture(AgentStatus.Completed("""{"x":1}""")).decodeOutput[Invoice].isLeft shouldBe true
  }

  it should "read a JSON answer of an agent without a format, fenced or not" in {
    AgentResultFixture(AgentStatus.Completed(s"```json\n$valid\n```")).decodeOutput[Invoice] shouldBe
      Right(Invoice("Acme", 12.5))
  }
}

object AgentOutputSpec {
  final case class Invoice(customer: String, total: Double) derives ReadWriter

  val invoiceSchema: ObjectSchema[Invoice] = ObjectSchema[Invoice](
    "An invoice",
    Seq(
      PropertyDefinition("customer", StringSchema("who is billed")),
      PropertyDefinition("total", NumberSchema("the amount", minimum = Some(0)))
    )
  )

  /** An Invoice schema that lists only the customer and allows other keys, so the type's reader must check the total. */
  val partialSchema: SchemaDefinition[Invoice] = ObjectSchema[Invoice](
    "An invoice, partly",
    Seq(PropertyDefinition("customer", StringSchema("who is billed"))),
    additionalProperties = true
  )
}
