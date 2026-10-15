package org.llm4s.agent.harness

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.agent.graph.middleware.*
import org.llm4s.agent.graph.tool.*
import org.llm4s.agent.graph.toolloop.*
import org.llm4s.agent.graph.toolloop.ToolLoopFixtures.*
import org.llm4s.agent.harness.HarnessFiles.*
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Stage 3 slice F6 (#1910, design §4.36): `InstructionMemoryMiddleware` through the tool loop. */
class InstructionMemoryMiddlewareSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val agentA = AgentId.unsafe("a")

  /** A model that answers every request, recording each. */
  final private class Recording extends ModelStep {
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion] = {
      seen.add(messages)
      Right(completion(AssistantMessage("done")))
    }
    def system(i: Int): String = seen.get(i).collectFirst { case s: SystemMessage => s.content }.getOrElse("")
  }

  /** A model whose first request calls the tool `noop`, then answers. */
  final private class TwoSteps extends ModelStep {
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion] = {
      seen.add(messages)
      if (seen.size == 1) Right(completion(AssistantMessage(None, Seq(ToolCall("c1", "noop", ujson.Obj())))))
      else Right(completion(AssistantMessage("done")))
    }
  }

  private val noop: AgentTool[ujson.Value] =
    AgentTool(
      AgentToolSpec[ujson.Value]("noop", "does nothing", org.llm4s.toolapi.Schema.`object`[ujson.Value]("none"))
    )((_, _) => ToolOutcome.Success(ToolContent.Json(ujson.Str("ok"))))

  private def loopOf(model: ModelStep, stack: Seq[AgentMiddleware], tools: ToolSet = ToolSet.empty): ToolLoop =
    ToolLoop.build("instructions", "1", agentA, Vector(LoopAgent(agentA, model, tools).withMiddleware(stack))).value

  private def reports(store: Checkpointer, thread: ThreadId): Vector[DocumentsReported] =
    store
      .eventsAfter(thread, 0L, 1000)
      .value
      .flatMap(r => HarnessEvents.DocumentsReported.unapply(StreamEvent.Durable(r)))

  private val user    = p("/user/AGENTS.md")
  private val project = p("/project/AGENTS.md")

  "InstructionMemoryMiddleware" should "show the files in order, each under its path, as one cacheable section" in {
    val backend = storeBackend()
    seed(backend, "/user/AGENTS.md" -> "Prefer short answers.", "/project/AGENTS.md" -> "Use Scala 3.\nRun sbt test.")
    val model = new Recording
    val mw    = new InstructionMemoryMiddleware(Vector(user, p("/absent/AGENTS.md"), project), backend)
    runInMemory(loopOf(model, Seq(mw)).graph, AgentInput("q")).answered
    val system = model.system(0)
    system should include(InstructionMemoryMiddleware.Guide)
    system should include("## /user/AGENTS.md\nPrefer short answers.")
    system should include("## /project/AGENTS.md\nUse Scala 3.\nRun sbt test.")
    system.indexOf("/user/AGENTS.md") should be < system.indexOf("/project/AGENTS.md")
    mw.promptSections(testModelContext()).value.map(s => (s.id, s.cacheable)) shouldBe Vector(("instructions", true))
    mw.instructions(testModelContext()).value.map(_.path) shouldBe Vector(user, project)
  }

  it should "add no section and report nothing when every file is missing or empty" in {
    val store   = new InMemoryCheckpointer
    val backend = storeBackend()
    seed(backend, "/project/AGENTS.md" -> "  \n")
    val model = new Recording
    val mw    = new InstructionMemoryMiddleware(Vector(user, project), backend)
    GraphRuntime(store).start(ThreadId("none"), loopOf(model, Seq(mw)).graph, AgentInput("q")).awaited.value.answered
    (model.system(0) should not).include("Standing instructions")
    reports(store, ThreadId("none")) shouldBe empty
  }

  it should "read the backend once per run, and serve every later step from the cache" in {
    val counting = new CountingBackend(storeBackend())
    seed(counting, "/user/AGENTS.md" -> "Be brief.")
    val model = new TwoSteps
    val mw    = new InstructionMemoryMiddleware(Vector(user), counting)
    runInMemory(loopOf(model, Seq(mw), ToolSet.of(noop).value).graph, AgentInput("q")).answered
    model.seen.size shouldBe 2
    counting.reads.get shouldBe 1
    model.seen.get(1).collectFirst { case s: SystemMessage => s.content }.value should include("Be brief.")
  }

  "A file over maxFileTokens" should "be shown cut and marked, and named in the event without its content (rules 9, 12)" in {
    val store   = new InMemoryCheckpointer
    val backend = storeBackend()
    seed(backend, "/user/AGENTS.md" -> ("SECRET " * 100), "/project/AGENTS.md" -> "Short.")
    val model = new Recording
    val mw = new InstructionMemoryMiddleware(Vector(user, project), backend, maxFileTokens = 10, maxTotalTokens = 500)
    GraphRuntime(store).start(ThreadId("cut"), loopOf(model, Seq(mw)).graph, AgentInput("q")).awaited.value.answered
    val shown = mw.instructions(testModelContext()).value
    shown.head.text.length should be <= (40 + "\n[cut: this file is over 10 tokens]".length)
    shown.head.cut shouldBe true
    model.system(0) should include("[cut: this file is over 10 tokens]")
    model.system(0) should include("## /project/AGENTS.md\nShort.")
    reports(store, ThreadId("cut")).flatMap(_.issues).map(i => (i.path, i.kind)) shouldBe
      Vector((user, DocumentIssue.Kind.Cut))
    (store.eventsAfter(ThreadId("cut"), 0L, 1000).value.map(_.event.toString).mkString should not).include("SECRET")
  }

  it should "be shown and reported when its kept prefix is only whitespace, since it was cut, not empty" in {
    val store   = new InMemoryCheckpointer
    val backend = storeBackend()
    seed(backend, "/user/AGENTS.md" -> ((" " * 100) + "\nReal instructions."))
    val mw = new InstructionMemoryMiddleware(Vector(user), backend, maxFileTokens = 10, maxTotalTokens = 500)
    GraphRuntime(store)
      .start(ThreadId("blank"), loopOf(new Recording, Seq(mw)).graph, AgentInput("q"))
      .awaited
      .value
      .answered
    mw.instructions(testModelContext()).value.map(i => (i.path, i.cut)) shouldBe Vector((user, true))
    reports(store, ThreadId("blank")).flatMap(_.issues).map(i => (i.path, i.kind)) shouldBe
      Vector((user, DocumentIssue.Kind.Cut))
  }

  "The section" should "stay within maxTotalTokens, cutting the file that fills it and leaving out the rest (rule 12)" in {
    val store   = new InMemoryCheckpointer
    val backend = storeBackend()
    val third   = p("/third/AGENTS.md")
    seed(backend, "/user/AGENTS.md" -> ("a" * 300), "/project/AGENTS.md" -> ("b" * 300), "/third/AGENTS.md" -> "c")
    val model = new Recording
    val mw =
      new InstructionMemoryMiddleware(Vector(user, project, third), backend, maxFileTokens = 100, maxTotalTokens = 200)
    GraphRuntime(store).start(ThreadId("total"), loopOf(model, Seq(mw)).graph, AgentInput("q")).awaited.value.answered
    val section = mw.promptSections(testModelContext()).value.head.text
    section.length.toLong should be <= PromptSection.charsFor(200)
    section should include("[cut: the instructions are over 200 tokens]")
    (section should not).include("/third/AGENTS.md")
    reports(store, ThreadId("total")).flatMap(_.issues).map(i => (i.path, i.kind)) shouldBe Vector(
      (project, DocumentIssue.Kind.Cut),
      (third, DocumentIssue.Kind.NotListed)
    )
  }

  /** A thread whose state backend holds the files `files`, after one run of `mw`. */
  private def seeded(thread: ThreadId, store: Checkpointer, backend: StateFileBackend, mw: AgentMiddleware)(
    files: (VPath, String)*
  ): GraphRuntime = {
    val rt    = GraphRuntime(store)
    val loop0 = loopOf(new Recording, Seq(mw), ToolSet.of(FileTools(backend)*).value)
    rt.start(thread, loop0.graph, AgentInput("seed")).awaited.value.answered
    files.foreach { case (path, text) =>
      rt.updateState(
        thread,
        loop0.graph,
        store.latest(thread).value.value.checkpoint.id,
        StateUpdate.update(backend.files, FileUpdate.Put(path, FileEntry(text, java.time.Instant.EPOCH)))
      ).isRight shouldBe true
    }
    rt
  }

  /** A model that calls `edit_file` on each of `paths`, replacing "one" with "two", then answers. */
  final private class Editing(paths: VPath*) extends ModelStep {
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion] = {
      seen.add(messages)
      if (seen.size == 1)
        Right(
          completion(
            AssistantMessage(
              None,
              paths.zipWithIndex.map { case (path, i) =>
                ToolCall(
                  s"e$i",
                  FileTools.EditFile,
                  ujson.Obj("path" -> path.value, "old_string" -> "one", "new_string" -> "two")
                )
              }
            )
          )
        )
      else Right(completion(AssistantMessage("done")))
    }
    def system(i: Int): String = seen.get(i).collectFirst { case s: SystemMessage => s.content }.getOrElse("")
  }

  it should "read no file once the section is full, naming each left out" in {
    val counting = new CountingBackend(storeBackend())
    val many     = (1 to 50).map(i => p(s"/scope$i/AGENTS.md"))
    seed(counting, many.map(path => path.value -> ("x" * 400))*)
    val mw = new InstructionMemoryMiddleware(many.toVector, counting, maxFileTokens = 100, maxTotalTokens = 200)
    mw.instructions(testModelContext()).value.size shouldBe 2
    counting.reads.get shouldBe 2
  }

  it should "read no further file once the room left cannot show one, even when a file filled the section exactly" in {
    val counting = new CountingBackend(storeBackend())
    val first    = p("/a/AGENTS.md")
    val second   = p("/b/AGENTS.md")
    // the guide and the first heading, then exactly the room left: the section is full to its last character
    val room =
      PromptSection.charsFor(200).toInt - InstructionMemoryMiddleware.Guide.length - s"\n\n## ${first.value}\n".length
    room should be <= 800
    seed(counting, first.value -> ("x" * room), second.value -> "never read")
    val mw = new InstructionMemoryMiddleware(Vector(first, second), counting, maxFileTokens = 200, maxTotalTokens = 200)
    mw.instructions(testModelContext()).value.map(i => (i.path, i.cut)) shouldBe Vector((first, false))
    counting.reads.get shouldBe 1
  }

  "An edit with FileTools' edit_file" should "show from the next run, not in the run that read the files (rule 1)" in {
    val store   = new InMemoryCheckpointer
    val thread  = ThreadId("edit")
    val backend = StateFileBackend()
    val mw      = new InstructionMemoryMiddleware(Vector(project), backend)
    val tools   = ToolSet.of(FileTools(backend)*).value
    val rt      = seeded(thread, store, backend, mw)(project -> "Version one.")
    val model   = new Editing(project)
    rt.start(thread, loopOf(model, Seq(mw), tools).graph, AgentInput("two")).awaited.value.answered
    model.system(1) should include("Version one.")
    val next = new Recording
    rt.start(thread, loopOf(next, Seq(mw), tools).graph, AgentInput("three")).awaited.value.answered
    next.system(0) should include("Version two.")
    mw.tools shouldBe empty
  }

  it should "change only the files the permission rules let the agent write" in {
    val store   = new InMemoryCheckpointer
    val thread  = ThreadId("permitted")
    val backend = StateFileBackend()
    val mw      = new InstructionMemoryMiddleware(Vector(user, project), backend)
    val tools   = ToolSet.of(FileTools(backend)*).value
    val rules = new PermissionMiddleware(
      Seq(
        PermissionRule.deny(Operation.Write, "/user/**"),
        PermissionRule.allow(Operation.Write, "/project/**"),
        PermissionRule.allow(Operation.Read, "**"),
        PermissionRule.allow(Operation.Invoke, "*")
      )
    )
    val rt = seeded(thread, store, backend, mw)(user -> "User one.", project -> "Project one.")
    rt.start(thread, loopOf(new Editing(user, project), Seq(rules, mw), tools).graph, AgentInput("edit"))
      .awaited
      .value
      .answered
    val next = new Recording
    rt.start(thread, loopOf(next, Seq(rules, mw), tools).graph, AgentInput("after")).awaited.value.answered
    next.system(0) should include("User one.")
    next.system(0) should include("Project two.")
  }

  "A first model step that failed" should "commit no report, and its recovery read again and report once (rules 4, 10)" in {
    val broken = new AtomicBoolean(true)
    val failing = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("failing")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] =
        if (broken.getAndSet(false)) Left(ValidationError("model", "down")) else next(request)
    }
    val store    = new InMemoryCheckpointer
    val thread   = ThreadId("recover")
    val counting = new CountingBackend(storeBackend())
    seed(counting, "/user/AGENTS.md" -> ("x" * 100))
    val mw   = new InstructionMemoryMiddleware(Vector(user), counting, maxFileTokens = 5, maxTotalTokens = 500)
    val loop = loopOf(new Recording, Seq(failing, mw))
    GraphRuntime(store).start(thread, loop.graph, AgentInput("q")).awaited.value.failed
    reports(store, thread) shouldBe empty
    val before = counting.reads.get
    GraphRuntime(store).recover(thread, loop.graph).awaited.value.answered
    counting.reads.get should be > before
    reports(store, thread).map(_.issues.map(_.kind)) shouldBe Vector(Vector(DocumentIssue.Kind.Cut))
  }

  "A backend error that is not a refusal" should "fail the step, and recover read again" in {
    val store    = new InMemoryCheckpointer
    val thread   = ThreadId("store-down")
    val counting = new CountingBackend(storeBackend())
    seed(counting, "/user/AGENTS.md" -> "Be brief.")
    counting.failing = Some(ValidationError("store", "down"))
    val model      = new Recording
    val loop       = loopOf(model, Seq(new InstructionMemoryMiddleware(Vector(user), counting)))
    val (_, error) = GraphRuntime(store).start(thread, loop.graph, AgentInput("q")).awaited.value.failed
    error.message should include("down")
    counting.failing = None
    GraphRuntime(store).recover(thread, loop.graph).awaited.value.answered
    model.system(0) should include("Be brief.")
  }

  "The cache" should "hold at most maxCachedRuns runs (rule 12)" in {
    val backend = storeBackend()
    seed(backend, "/user/AGENTS.md" -> "Be brief.")
    val mw = new InstructionMemoryMiddleware(Vector(user), backend, maxCachedRuns = 2)
    (1 to 4).foreach(i => runInMemory(loopOf(new Recording, Seq(mw)).graph, AgentInput("q"), thread = s"t$i").answered)
    mw.cachedRuns shouldBe 2
  }

  "InstructionMemoryMiddleware" should "refuse limits below 1, or a file limit over the total" in {
    an[IllegalArgumentException] should be thrownBy new InstructionMemoryMiddleware(
      Vector.empty,
      storeBackend(),
      maxFileTokens = 0
    )
    an[IllegalArgumentException] should be thrownBy
      new InstructionMemoryMiddleware(Vector.empty, storeBackend(), maxFileTokens = 10, maxTotalTokens = 5)
    an[IllegalArgumentException] should be thrownBy new InstructionMemoryMiddleware(
      Vector.empty,
      storeBackend(),
      maxCachedRuns = 0
    )
  }
}
