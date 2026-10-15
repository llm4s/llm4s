package org.llm4s.agent.harness

import org.llm4s.agent.{ Agent, AgentStatus, CompletionFixture, ScriptedLLMClient }
import org.llm4s.agent.AgentFixture.{ built, value }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.GraphTestSupport.testRunContext
import org.llm4s.agent.graph.middleware.*
import org.llm4s.agent.graph.tool.*
import org.llm4s.agent.graph.toolloop.LoopAgent
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.agent.harness.HarnessFiles.p
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.*

/** Stage 3 slice P1 (#1915, design §4.37): `HarnessProfile` and `AgentBuilder.withHarness`. */
class HarnessProfileSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private def call(name: String, args: ujson.Obj, id: String = "c1"): Completion =
    CompletionFixture.withMessage(AssistantMessage(None, Seq(ToolCall(id, name, args))))

  private def calls(cs: (String, ujson.Obj)*): Completion =
    CompletionFixture.withMessage(
      AssistantMessage(None, cs.zipWithIndex.map { case ((name, args), i) => ToolCall(s"c$i", name, args) })
    )

  /** The text of `path` in `state`, read through `backend`; `None` when it does not exist. */
  private def fileIn(backend: FileBackend, state: ThreadState, path: String): Option[String] = {
    val ctx = FileContext(
      ToolContext(testRunContext(), ToolCallId("read"), IdempotencyKey(UUID.randomUUID().toString), state)
    )
    backend.read(p(path), 0, 1000, ctx).toOption.map(_.lines.mkString("\n"))
  }

  private def stateOf(agent: Agent, store: Checkpointer, thread: ThreadId): ThreadState =
    agent.loop.graph.restore(store.latest(thread).value.value.checkpoint.snapshot).value.state

  private def system(messages: Vector[Message]): String =
    messages.collectFirst { case s: SystemMessage => s.content }.getOrElse("")

  /** A sandbox runner that answers every command with its text. */
  private object Echo extends SandboxRunner {
    def locks(workdir: VPath): Set[LockKey] = Set(LockKey("sandbox"))
    def run(
      command: String,
      workdir: VPath,
      timeout: FiniteDuration,
      maxOutputBytes: Int,
      ctx: FileContext
    ): Result[ExecResult] = Right(ExecResult(0, s"ran $command", ""))
  }

  "The deepAgents preset" should "install its middleware outermost first, in the documented order" in {
    val harness = HarnessProfile.deepAgents
      .withPermissions(Vector(PermissionRule.allow(Operation.Invoke, "**")))
      .assembled
      .value
    harness.middleware.map(_.id.value) shouldBe
      Vector("permissions", "summarization", "tool-output-offload", "skills", "instructions", "todos")
    harness.tools.map(_.spec.name) shouldBe FileTools.Names
    harness.retention shouldBe RetentionPolicy(maxCheckpoints = Some(HarnessProfile.DefaultRetainedCheckpoints))
  }

  it should "run its middleware outside the builder's own, and keep the order through build()" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("done"))
    val mine = new AgentMiddleware {
      val id = MiddlewareId("mine")
      override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
        Right(Vector(PromptSection("mine", "MINE", cacheable = true)))
    }
    built(Agent.builder("assistant", client).withMiddleware(mine).withHarness(HarnessProfile().withTodos(TodoConfig())))
      .run("hi")
      .value
    val prompt = system(client.sent.head)
    prompt.indexOf("write_todos") should be < prompt.indexOf("MINE")
  }

  it should "refuse a builder's own summariser, which would run inside the harness's offload" in {
    val compressor = new SummaryCompressor {
      def compress(previous: Option[String], messages: Vector[Message], maxTokens: Int, context: ModelContext) =
        Right("summary")
    }
    val error = Agent
      .builder("assistant", ScriptedLLMClient.of())
      .withMiddleware(new SummarizationMiddleware(SummaryTrigger.Messages(10), compressor = compressor))
      .withHarness(HarnessProfile().withOffload(OffloadConfig()))
      .build()
      .left
      .value
    error.message should include("'summarization' must run outside 'tool-output-offload'")
  }

  "The minimal profile" should "install only the file tools, with no middleware" in {
    val harness = HarnessProfile().assembled.value
    harness.middleware shouldBe empty
    harness.tools.map(_.spec.name) shouldBe FileTools.Names
  }

  it should "install no permission middleware for an empty rule list, which would deny every call" in {
    HarnessProfile().withTodos(TodoConfig()).assembled.value.middleware.map(_.id.value) shouldBe Vector("todos")
  }

  "A sandbox" should "add execute, and only then" in {
    HarnessProfile().withSandbox(SandboxBackend(Echo)).assembled.value.tools.map(_.spec.name) shouldBe
      FileTools.Names :+ ExecuteTool.Name
    HarnessProfile().toolNames should not contain ExecuteTool.Name
  }

  it should "run a command through the agent" in {
    val client =
      ScriptedLLMClient.of(call("execute", ujson.Obj("command" -> "make test")), CompletionFixture.simple("ok"))
    val agent  = built(Agent.builder("coder", client).withHarness(HarnessProfile().withSandbox(SandboxBackend(Echo))))
    val result = agent.run("test it").value
    result.messages.collect { case t: ToolMessage => t.content }.head should include("ran make test")
  }

  "A tool selection" should "select by Only and Except" in {
    HarnessProfile()
      .withTools(ToolSelection.Only(Set(FileTools.ReadFile, FileTools.Ls)))
      .assembled
      .value
      .tools
      .map(_.spec.name) shouldBe Vector(FileTools.Ls, FileTools.ReadFile)
    HarnessProfile()
      .withTools(ToolSelection.Except(Set(FileTools.Delete, FileTools.WriteFile)))
      .assembled
      .value
      .tools
      .map(_.spec.name) shouldBe FileTools.Names.filterNot(Set(FileTools.Delete, FileTools.WriteFile))
  }

  it should "refuse a name that is not a harness tool, and execute without a sandbox, when the agent is built" in {
    val client = ScriptedLLMClient.of()
    val error = Agent
      .builder("assistant", client)
      .withHarness(HarnessProfile().withTools(ToolSelection.Only(Set("read_flie", ExecuteTool.Name))))
      .build()
      .left
      .value
    error shouldBe a[ValidationError]
    error.message should include("'read_flie' is not a harness tool")
    error.message should include("'execute' needs a sandbox")
    HarnessProfile().withTools(ToolSelection.Except(Set("nope"))).assembled.left.value.message should include("'nope'")
    // excluding execute without a sandbox excludes nothing that is there
    HarnessProfile()
      .withTools(ToolSelection.Except(Set(ExecuteTool.Name)))
      .assembled
      .value
      .tools
      .map(_.spec.name) shouldBe
      FileTools.Names
  }

  "A setting a component refuses" should "be a ValidationError from build(), naming the profile field" in {
    val error = Agent
      .builder("assistant", ScriptedLLMClient.of())
      .withHarness(HarnessProfile.deepAgents.withTodos(TodoConfig(maxTodos = 0)).withOffload(OffloadConfig(10)))
      .build()
      .left
      .value
    error.message should include("harness.todos")
    error.message should include("harness.offload")
  }

  "The default prompt" should "teach plan, act and verify, context management and tool safety, by what is on" in {
    val full = HarnessProfile.defaultPrompt(HarnessProfile.deepAgents.withSandbox(SandboxBackend(Echo)))
    Seq("Plan:", "Act:", "Verify:", "write_todos", "Context:", "/large_tool_results", "execute", "Tool safety:")
      .foreach(full should include(_))
    val bare = HarnessProfile.defaultPrompt(HarnessProfile().withTools(ToolSelection.Only(Set.empty)))
    Seq("write_todos", "Context:", "execute", "read_file").foreach(bare should not include _)
    bare should include("Verify:")
    bare should include("Tool safety:")
  }

  it should "stay a fixed size: the whole preset's is under 1,000 estimated tokens" in {
    val full = HarnessProfile.defaultPrompt(HarnessProfile.deepAgents.withSandbox(SandboxBackend(Echo)))
    PromptSection.estimatedTokens(full.length.toLong) should be < 1000L
  }

  "The system prompt" should "follow the PromptMode, sent on every model call" in {
    def sent(mode: PromptMode): String = {
      val client = ScriptedLLMClient.of(CompletionFixture.simple("done"))
      built(Agent.builder("assistant", client).withHarness(HarnessProfile().withSystemPrompt(mode))).run("hi").value
      system(client.sent.head)
    }
    val default = HarnessProfile.defaultPrompt(HarnessProfile())
    sent(PromptMode.Default) should startWith(default)
    sent(PromptMode.Replace("Only this.")) should startWith("Only this.")
    (sent(PromptMode.Replace("Only this.")) should not).include("Tool safety:")
    sent(PromptMode.Suffix("And this.")) should startWith(s"$default\n\nAnd this.")
  }

  it should "refuse withSystemPrompt beside a harness, and a blank replacement" in {
    Agent
      .builder("assistant", ScriptedLLMClient.of())
      .withSystemPrompt("mine")
      .withHarness(HarnessProfile())
      .build()
      .left
      .value
      .message should include("PromptMode.Replace or Suffix")
    HarnessProfile().withSystemPrompt(PromptMode.Replace(" ")).assembled.left.value.message should include("blank")
  }

  "toolTimeout" should "bound each profile tool that declares no timeout of its own" in {
    val harness = HarnessProfile().withSandbox(SandboxBackend(Echo)).withToolTimeout(30.seconds).assembled.value
    harness.tools.filter(t => FileTools.Names.contains(t.spec.name)).map(_.spec.timeout).distinct shouldBe
      Vector(Some(30.seconds))
    // execute keeps the bound it declares
    harness.tools.find(_.spec.name == ExecuteTool.Name).value.spec.timeout should not be Some(30.seconds)
    HarnessProfile().withToolTimeout(Duration.Zero).assembled.left.value.message should include("toolTimeout")
  }

  it should "time a slow tool out through the agent" in {
    final class Slow(underlying: FileBackend) extends FileBackend {
      def writes                                        = underlying.writes
      def locks(path: VPath): Set[LockKey]              = underlying.locks(path)
      def ls(path: VPath, limit: Int, ctx: FileContext) = { Thread.sleep(5000); underlying.ls(path, limit, ctx) }
      def read(path: VPath, offset: Int, limit: Int, ctx: FileContext) = underlying.read(path, offset, limit, ctx)
      def write(path: VPath, content: String, ctx: FileContext)        = underlying.write(path, content, ctx)
      def edit(path: VPath, old: String, replacement: String, replaceAll: Boolean, ctx: FileContext) =
        underlying.edit(path, old, replacement, replaceAll, ctx)
      def delete(path: VPath, ctx: FileContext) = underlying.delete(path, ctx)
      def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext) =
        underlying.find(under, accept, limit, ctx)
      def search(under: VPath, regex: java.util.regex.Pattern, accept: VPath => Boolean, limit: Int, ctx: FileContext) =
        underlying.search(under, regex, accept, limit, ctx)
    }
    val client  = ScriptedLLMClient.of(call("ls", ujson.Obj()), CompletionFixture.simple("done"))
    val profile = HarnessProfile().withBackend(new Slow(StateFileBackend())).withToolTimeout(100.millis)
    val result  = built(Agent.builder("assistant", client).withHarness(profile)).run("list").value
    result.messages.collect { case t: ToolMessage => t.content }.head should include("timed out")
  }

  "Offload in a profile" should "mount the stored results, so read_file reads an offloaded result by its path" in {
    val big = (1 to 2000).map(i => s"line $i of the page").mkString("\n")
    val fetch: AgentTool[ujson.Value] =
      AgentTool(AgentToolSpec[ujson.Value]("fetch", "fetches", org.llm4s.toolapi.Schema.`object`[ujson.Value]("none")))(
        (_, _) => ToolOutcome.Success(ToolContent.Json(ujson.Str(big)))
      )
    // a model that fetches, then reads the path the preview names, then answers
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    val client = new LLMClient {
      def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
        val messages = conversation.messages.toVector
        seen.add(messages)
        val results = messages.collect { case t: ToolMessage => t.content }
        if (results.isEmpty) Right(call("fetch", ujson.Obj()))
        else if (results.size == 1) {
          val path = ToolOutputOffload.PathPattern.findFirstIn(results.head).value
          Right(call("read_file", ujson.Obj("path" -> path, "offset" -> 1499, "limit" -> 1), "c2"))
        } else Right(CompletionFixture.simple("read"))
      }
      def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      def getContextWindow(): Int     = 128000
      def getReserveCompletion(): Int = 4096
    }
    val agent = built(
      Agent
        .builder("researcher", client)
        .withTools(ToolSet.of(fetch).value)
        .withHarness(HarnessProfile().withOffload(OffloadConfig(thresholdTokens = 1000)))
    )
    val results = agent.run("research").value.messages.collect { case t: ToolMessage => t.content }
    results.head should include(ToolOutputOffload.Mount.value)
    results(1) should include("line 1499 of the page")
  }

  "A parallel batch of writes through a harness agent" should "keep every file (rule 1)" in {
    val store   = new InMemoryCheckpointer
    val backend = StateFileBackend()
    val client = ScriptedLLMClient.of(
      calls(
        "write_file" -> ujson.Obj("path" -> "/a.md", "content" -> "A"),
        "write_file" -> ujson.Obj("path" -> "/b.md", "content" -> "B")
      ),
      CompletionFixture.simple("written")
    )
    val agent = built(
      Agent.builder("writer", client).withHarness(HarnessProfile(backend = backend)).withRuntime(GraphRuntime(store))
    )
    agent.run(ThreadId("batch"), "write").value
    val state = stateOf(agent, store, ThreadId("batch"))
    fileIn(backend, state, "/a.md") shouldBe Some("A")
    fileIn(backend, state, "/b.md") shouldBe Some("B")
  }

  "An output Block on a harness agent" should "leave no file and no plan from the blocked turn (rule 5)" in {
    val store   = new InMemoryCheckpointer
    val backend = StateFileBackend()
    val client = ScriptedLLMClient.of(
      calls(
        "write_file"  -> ujson.Obj("path" -> "/report.md", "content" -> "draft"),
        "write_todos" -> ujson.Obj("todos" -> ujson.Arr(ujson.Obj("content" -> "Write", "status" -> "done")))
      ),
      CompletionFixture.simple("an answer far too long for the guardrail")
    )
    val agent = built(
      Agent
        .builder("writer", client)
        .withHarness(HarnessProfile(backend = backend).withTodos(TodoConfig()))
        .withMiddleware(new GuardrailMiddleware(Nil, Seq(new LengthCheck(1, 5))))
        .withRuntime(GraphRuntime(store))
    )
    agent.run(ThreadId("blocked"), "write").value.status shouldBe a[AgentStatus.Blocked]
    val state = stateOf(agent, store, ThreadId("blocked"))
    fileIn(backend, state, "/report.md") shouldBe None
    state.get(TodoMiddleware.key).value.todos shouldBe empty
  }

  "Retention" should "prune a thread's earlier turns to the profile's policy before each turn (rule 6)" in {
    val store  = new InMemoryCheckpointer
    val thread = ThreadId("kept")
    val client = ScriptedLLMClient.of((1 to 6).map(i => CompletionFixture.simple(s"answer $i"))*)
    val agent = built(
      Agent
        .builder("assistant", client)
        .withHarness(HarnessProfile().withRetention(RetentionPolicy(maxCheckpoints = Some(2))))
        .withRuntime(GraphRuntime(store))
    )
    val results = (1 to 3).map(i => agent.run(thread, s"turn $i").value)
    val history = store.history(thread, None, 100).value
    // pruned to two as the latest turn was admitted - its claim checkpoint and one before it - then the turn whole
    val latest = history.filter(_.runId == results.last.runId.value)
    latest.size should be > 1
    history.size shouldBe latest.size + 1
    // the thread goes on from its latest checkpoint
    agent.run(thread, "turn 4").value.answer.value shouldBe "answer 4"
  }

  it should "leave the thread unchanged when a turn is refused" in {
    val store  = new InMemoryCheckpointer
    val thread = ThreadId("refused")
    val client = ScriptedLLMClient.of((1 to 3).map(i => CompletionFixture.simple(s"answer $i"))*)
    val agent = built(
      Agent
        .builder("assistant", client)
        .withHarness(HarnessProfile().withRetention(RetentionPolicy(maxCheckpoints = Some(1), maxEvents = Some(1))))
        .withRuntime(GraphRuntime(store))
    )
    agent.run(thread, "first").value
    val before = (store.history(thread, None, 100).value, store.eventsAfter(thread, 0L, 1000).value)
    agent.run(thread, " ").isLeft shouldBe true          // a blank query
    agent.recover(thread).isLeft shouldBe true           // nothing to recover
    agent.resume(thread, Map.empty).isLeft shouldBe true // not suspended
    (store.history(thread, None, 100).value, store.eventsAfter(thread, 0L, 1000).value) shouldBe before
  }

  it should "leave a finished turn's own events, so its handle still replays it" in {
    val store  = new InMemoryCheckpointer
    val thread = ThreadId("replayed")
    val client =
      ScriptedLLMClient.of(CompletionFixture.simple("one"), call("ls", ujson.Obj()), CompletionFixture.simple("two"))
    val agent = built(
      Agent
        .builder("assistant", client)
        .withHarness(HarnessProfile().withRetention(RetentionPolicy(maxCheckpoints = Some(1), maxEvents = Some(1))))
        .withRuntime(GraphRuntime(store))
    )
    agent.run(thread, "first").value
    val run = agent.start(thread, "second").value
    run.await().value.answer.value shouldBe "two"
    val seen = new CopyOnWriteArrayList[StreamEvent]()
    run.subscribe()(e => seen.add(e): Unit).value
    run.await().value // returns once the listener has had the turn's last event
    val events = seen.toArray.toVector
    events.collect { case d: StreamEvent.Disconnected => d } shouldBe empty
    events.collect { case d: StreamEvent.Durable => d } should not be empty
  }

  it should "skip RetentionPolicy.keepAll, and bound by the default otherwise" in {
    def kept(profile: HarnessProfile): Int = {
      val store  = new InMemoryCheckpointer
      val client = ScriptedLLMClient.of((1 to 3).map(i => CompletionFixture.simple(s"a$i"))*)
      val agent  = built(Agent.builder("assistant", client).withHarness(profile).withRuntime(GraphRuntime(store)))
      (1 to 3).foreach(i => agent.run(ThreadId("t"), s"q$i").value)
      store.history(ThreadId("t"), None, 1000).value.size
    }
    kept(HarnessProfile().withRetention(RetentionPolicy(maxCheckpoints = Some(1)))) should be <
      kept(HarnessProfile().withRetention(RetentionPolicy.keepAll))
    HarnessProfile().retention.maxCheckpoints shouldBe Some(HarnessProfile.DefaultRetainedCheckpoints)
  }

  "The graph version" should "be the same for the same profile, and change with the profile" in {
    def version(profile: HarnessProfile): String =
      built(Agent.builder("assistant", ScriptedLLMClient.of()).withHarness(profile)).loop.graph.version
    version(HarnessProfile.deepAgents) shouldBe version(HarnessProfile.deepAgents)
    version(HarnessProfile.deepAgents) should not be version(HarnessProfile.deepAgents.withTodos(None))
    version(HarnessProfile()) should not be version(HarnessProfile().withSystemPrompt(PromptMode.Suffix("x")))
  }

  "The preset's prompt sections" should "fit the agent's default section budget together, each full" in {
    val profile = HarnessProfile.deepAgents
    val todos   = profile.todos.value
    // a plan at the preset's limits, rendered as sent: each line's id, status and content
    val plan = TodoList(
      (1 to todos.maxTodos).toVector.map(i => Todo(f"$i%020d", "c" * todos.maxTodoChars, TodoStatus.InProgress)),
      todos.maxTodos.toLong + 1
    )
    val state = HarnessTestSupport
      .committed(HarnessTestSupport.stateOf(TodoMiddleware.key), StateUpdate.update(TodoMiddleware.key, plan))
      .value
    val todoText = new TodoMiddleware(todos.maxTodos, todos.maxTodoChars)
      .promptSections(new ModelContext(testRunContext(), state, org.llm4s.agent.AgentId.unsafe("a")))
      .value
      .map(_.text.length.toLong)
    val lengths = todoText ++ Vector(
      PromptSection.charsFor(profile.skills.value.maxSectionTokens),
      PromptSection.charsFor(profile.instructions.value.maxTotalTokens),
      // llm4s-agent-memory's recalled memories, added beside a harness with withMiddleware (MemoryRecall's default)
      PromptSection.charsFor(1024 + 64)
    )
    PromptSection.estimatedTokens(
      lengths.sum + 2L * lengths.size
    ) should be <= LoopAgent.DefaultMaxPromptSectionTokens.toLong
  }
}
