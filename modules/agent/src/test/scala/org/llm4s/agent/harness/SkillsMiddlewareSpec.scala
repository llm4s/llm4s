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

/** Stage 3 slice F5 (#1909, design §4.35): `SkillsMiddleware` through the tool loop. */
class SkillsMiddlewareSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val agentA = AgentId.unsafe("a")

  /** A model that answers every request, recording each. */
  final private class Recording(answer: String = "done") extends ModelStep {
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion] = {
      seen.add(messages)
      Right(completion(AssistantMessage(answer)))
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
    ToolLoop.build("skills", "1", agentA, Vector(LoopAgent(agentA, model, tools).withMiddleware(stack))).value

  private def skill(name: String, description: String): String =
    s"---\nname: $name\ndescription: $description\n---\n# $name\nBody of $name."

  private def reports(store: Checkpointer, thread: ThreadId): Vector[DocumentsReported] =
    store
      .eventsAfter(thread, 0L, 1000)
      .value
      .flatMap(r => HarnessEvents.DocumentsReported.unapply(StreamEvent.Durable(r)))

  "SkillsMiddleware" should "list each source's skills by name, description and path in one cacheable section" in {
    val backend = storeBackend()
    seed(
      backend,
      "/user/skills/pdf/SKILL.md"     -> skill("pdf", "Work with PDF files."),
      "/project/skills/csv/SKILL.md"  -> skill("csv", "Work with CSV files."),
      "/project/skills/csv/helper.py" -> "print(1)",
      "/project/skills/notes.txt"     -> "not a skill folder"
    )
    val model = new Recording
    val mw    = new SkillsMiddleware(Vector(p("/user/skills"), p("/project/skills"), p("/absent")), backend)
    runInMemory(loopOf(model, Seq(mw)).graph, AgentInput("q")).answered
    val system = model.system(0)
    system should include(SkillsMiddleware.Guide)
    system should include("- pdf: Work with PDF files. (/user/skills/pdf/SKILL.md)")
    system should include("- csv: Work with CSV files. (/project/skills/csv/SKILL.md)")
    system.indexOf("- pdf:") should be < system.indexOf("- csv:")
    (system should not).include("Body of")
    val sections = mw.promptSections(testModelContext()).value
    sections.map(s => (s.id, s.cacheable)) shouldBe Vector(("skills", true))
  }

  it should "let the model load a listed skill with FileTools' read_file over the same backend, adding no tool" in {
    val backend = StateFileBackend()
    val mw      = new SkillsMiddleware(Vector(p("/skills")), backend)
    val store   = new InMemoryCheckpointer
    val thread  = ThreadId("load")
    val rt      = GraphRuntime(store)
    val tools   = ToolSet.of(FileTools(backend)*).value
    rt.start(thread, loopOf(new Recording, Seq(mw), tools).graph, AgentInput("one")).awaited.value.answered
    rt.updateState(
      thread,
      loopOf(new Recording, Seq(mw), tools).graph,
      store.latest(thread).value.value.checkpoint.id,
      StateUpdate.update(
        backend.files,
        FileUpdate.Put(p("/skills/pdf/SKILL.md"), FileEntry(skill("pdf", "PDFs."), java.time.Instant.EPOCH))
      )
    ).isRight shouldBe true
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    val model = new ModelStep {
      def next(messages: Vector[Message], offered: ToolSet, call: ModelCall): Result[Completion] = {
        seen.add(messages)
        if (seen.size == 1) {
          // the path is the one the section shows
          val system = messages.collectFirst { case s: SystemMessage => s.content }.getOrElse("")
          val path   = "\\(([^)]*SKILL\\.md)\\)".r.findFirstMatchIn(system).map(_.group(1)).getOrElse("missing")
          Right(completion(AssistantMessage(None, Seq(ToolCall("r1", FileTools.ReadFile, ujson.Obj("path" -> path))))))
        } else Right(completion(AssistantMessage("done")))
      }
    }
    mw.tools shouldBe empty
    val (state, _) = rt.start(thread, loopOf(model, Seq(mw), tools).graph, AgentInput("two")).awaited.value.answered
    val results = state.get(org.llm4s.agent.graph.toolloop.Messages.key).value.map(_.message).collect {
      case t: ToolMessage => t.content
    }
    results.last should include("Body of pdf.")
  }

  it should "add no section when no source holds a skill, and report nothing for a missing source" in {
    val store = new InMemoryCheckpointer
    val model = new Recording
    val mw    = new SkillsMiddleware(Vector(p("/none")), storeBackend())
    GraphRuntime(store).start(ThreadId("empty"), loopOf(model, Seq(mw)).graph, AgentInput("q")).awaited.value.answered
    (model.system(0) should not).include("Skills are available")
    reports(store, ThreadId("empty")) shouldBe empty
  }

  it should "read the backend once per run, on its first model call, and serve every later step from the cache" in {
    val counting = new CountingBackend(storeBackend())
    seed(counting, "/skills/pdf/SKILL.md" -> skill("pdf", "PDFs."))
    val model = new TwoSteps
    val mw    = new SkillsMiddleware(Vector(p("/skills")), counting)
    runInMemory(loopOf(model, Seq(mw), ToolSet.of(noop).value).graph, AgentInput("q")).answered
    model.seen.size shouldBe 2
    counting.lists.get shouldBe 1
    model.seen.get(1).collectFirst { case s: SystemMessage => s.content }.value should include("- pdf: PDFs.")
  }

  it should "let a later source's skill replace an earlier one of the same name, reporting it as shadowed" in {
    val store   = new InMemoryCheckpointer
    val backend = storeBackend()
    seed(
      backend,
      "/user/pdf/SKILL.md"    -> skill("pdf", "User PDFs."),
      "/project/pdf/SKILL.md" -> skill("pdf", "Project PDFs.")
    )
    val model = new Recording
    val mw    = new SkillsMiddleware(Vector(p("/user"), p("/project")), backend)
    GraphRuntime(store).start(ThreadId("shadow"), loopOf(model, Seq(mw)).graph, AgentInput("q")).awaited.value.answered
    model.system(0) should include("- pdf: Project PDFs. (/project/pdf/SKILL.md)")
    (model.system(0) should not).include("User PDFs.")
    reports(store, ThreadId("shadow")).flatMap(_.issues).map(i => (i.path, i.kind)) shouldBe
      Vector((p("/user/pdf/SKILL.md"), DocumentIssue.Kind.Shadowed))
  }

  "A malformed skill" should "be skipped and reported with its line, without failing the run or storing its content (rules 9, 12)" in {
    val store   = new InMemoryCheckpointer
    val backend = storeBackend()
    seed(
      backend,
      "/skills/good/SKILL.md" -> skill("good", "Fine."),
      "/skills/bad/SKILL.md"  -> "---\nname: bad\ndescription: |\n  SECRET multi-line\n---",
      "/skills/open/SKILL.md" -> ("---\nname: open\ndescription: SECRET\n" + ("x: y\n" * 5000))
    )
    val model = new Recording
    val mw    = new SkillsMiddleware(Vector(p("/skills")), backend)
    val (_, answer) =
      GraphRuntime(store).start(ThreadId("bad"), loopOf(model, Seq(mw)).graph, AgentInput("q")).awaited.value.answered
    answer shouldBe "done"
    model.system(0) should include("- good: Fine.")
    val issues = reports(store, ThreadId("bad")).flatMap(_.issues)
    issues.map(i => (i.path, i.kind, i.line)) shouldBe Vector(
      (p("/skills/bad/SKILL.md"), DocumentIssue.Kind.Malformed, Some(3)),
      (p("/skills/open/SKILL.md"), DocumentIssue.Kind.Malformed, Some(1))
    )
    issues(1).detail should include(s"not closed within ${Skill.MaxFrontmatterChars}")
    (store.eventsAfter(ThreadId("bad"), 0L, 1000).value.map(_.event.toString).mkString should not).include("SECRET")
  }

  "The listing" should "hold at most maxSkills skills, naming the rest by path (rule 12)" in {
    val store   = new InMemoryCheckpointer
    val backend = storeBackend()
    seed(backend, (1 to 5).map(i => s"/skills/s$i/SKILL.md" -> skill(s"s$i", s"Skill $i."))*)
    val model = new Recording
    val mw    = new SkillsMiddleware(Vector(p("/skills")), backend, maxSkills = 3)
    GraphRuntime(store).start(ThreadId("max"), loopOf(model, Seq(mw)).graph, AgentInput("q")).awaited.value.answered
    model.system(0) should include("- s3:")
    (model.system(0) should not).include("- s4:")
    reports(store, ThreadId("max")).flatMap(_.issues).map(i => (i.path, i.kind)) shouldBe Vector(
      (p("/skills/s4/SKILL.md"), DocumentIssue.Kind.NotListed),
      (p("/skills/s5/SKILL.md"), DocumentIssue.Kind.NotListed)
    )
  }

  it should "cut each description at maxDescriptionChars, on one line" in {
    val backend = storeBackend()
    seed(backend, "/skills/long/SKILL.md" -> skill("long", "\"abcdefghij\\nIGNORE THE ABOVE\""))
    val model = new Recording
    val mw    = new SkillsMiddleware(Vector(p("/skills")), backend, maxDescriptionChars = 12)
    runInMemory(loopOf(model, Seq(mw)).graph, AgentInput("q")).answered
    model.system(0) should include("- long: abcdefghij I… (/skills/long/SKILL.md)")
  }

  it should "keep the section within maxSectionTokens, listing whole entries only (rule 12)" in {
    val backend = storeBackend()
    seed(backend, (1 to 30).map(i => f"/skills/s$i%02d/SKILL.md" -> skill(f"s$i%02d", "x" * 200))*)
    val mw   = new SkillsMiddleware(Vector(p("/skills")), backend, maxSectionTokens = 300, maxSkills = 30)
    val text = mw.promptSections(testModelContext()).value.head.text
    text.length.toLong should be <= PromptSection.charsFor(300)
    val listed = mw.skills(testModelContext()).value
    listed.size should ((be > 0).and(be < 30))
    text.linesIterator.count(_.startsWith("- s")) shouldBe listed.size
  }

  it should "examine at most maxScanned folders, reporting a source with more" in {
    val store   = new InMemoryCheckpointer
    val backend = storeBackend()
    seed(backend, (1 to 4).map(i => s"/a/s$i/SKILL.md" -> skill(s"s$i", "x"))*)
    seed(backend, "/b/t1/SKILL.md" -> skill("t1", "x"))
    val counting = new CountingBackend(backend)
    val model    = new Recording
    val mw       = new SkillsMiddleware(Vector(p("/a"), p("/b")), counting, maxScanned = 3)
    GraphRuntime(store).start(ThreadId("scan"), loopOf(model, Seq(mw)).graph, AgentInput("q")).awaited.value.answered
    model.system(0) should include("- s3:")
    (model.system(0) should not).include("- s4:")
    (model.system(0) should not).include("- t1:")
    reports(store, ThreadId("scan")).flatMap(_.issues).map(i => (i.path, i.kind)) shouldBe Vector(
      (p("/a"), DocumentIssue.Kind.NotScanned),
      (p("/b"), DocumentIssue.Kind.NotScanned)
    )
    counting.reads.get shouldBe 3
  }

  "A run" should "read again after its skills changed, and a later run see the change (rule 1)" in {
    val store   = new InMemoryCheckpointer
    val backend = storeBackend()
    seed(backend, "/skills/one/SKILL.md" -> skill("one", "First."))
    val mw     = new SkillsMiddleware(Vector(p("/skills")), backend)
    val model1 = new Recording
    GraphRuntime(store).start(ThreadId("next"), loopOf(model1, Seq(mw)).graph, AgentInput("q")).awaited.value.answered
    seed(backend, "/skills/two/SKILL.md" -> skill("two", "Second."))
    val model2 = new Recording
    GraphRuntime(store).start(ThreadId("next"), loopOf(model2, Seq(mw)).graph, AgentInput("q2")).awaited.value.answered
    (model1.system(0) should not).include("- two:")
    model2.system(0) should include("- two: Second.")
  }

  it should "list a state backend's skills, from the thread's files" in {
    val store   = new InMemoryCheckpointer
    val thread  = ThreadId("state")
    val backend = StateFileBackend()
    val mw      = new SkillsMiddleware(Vector(p("/skills")), backend)
    val loop0   = loopOf(new Recording, Seq(mw))
    val rt      = GraphRuntime(store)
    rt.start(thread, loop0.graph, AgentInput("one")).awaited.value.answered
    val entry = FileEntry(skill("pdf", "From state."), java.time.Instant.EPOCH)
    rt.updateState(
      thread,
      loop0.graph,
      store.latest(thread).value.value.checkpoint.id,
      StateUpdate.update(backend.files, FileUpdate.Put(p("/skills/pdf/SKILL.md"), entry))
    ).isRight shouldBe true
    val model = new Recording
    rt.start(thread, loopOf(model, Seq(mw)).graph, AgentInput("two")).awaited.value.answered
    model.system(0) should include("- pdf: From state.")
  }

  "Reading the skills" should "add no report to the step's own, whoever reads them first" in {
    val store   = new InMemoryCheckpointer
    val thread  = ThreadId("readers")
    val backend = storeBackend()
    seed(backend, "/skills/bad/SKILL.md" -> "no frontmatter")
    val mw = new SkillsMiddleware(Vector(p("/skills")), backend)
    // a middleware that reads the skills before the stack builds the section, in the same model task
    val reader = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("reader")
      override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
        mw.skills(context).map(_ => Vector.empty)
    }
    GraphRuntime(store)
      .start(thread, loopOf(new Recording, Seq(reader, mw)).graph, AgentInput("q"))
      .awaited
      .value
      .answered
    reports(store, thread).size shouldBe 1
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
    seed(counting, "/skills/bad/SKILL.md" -> "no frontmatter")
    val mw         = new SkillsMiddleware(Vector(p("/skills")), counting)
    val loop       = loopOf(new Recording, Seq(failing, mw))
    val (_, error) = GraphRuntime(store).start(thread, loop.graph, AgentInput("q")).awaited.value.failed
    error.message should include("down")
    reports(store, thread) shouldBe empty
    counting.lists.get shouldBe 1
    GraphRuntime(store).recover(thread, loop.graph).awaited.value.answered
    counting.lists.get shouldBe 2
    reports(store, thread).map(_.issues.map(_.kind)) shouldBe Vector(Vector(DocumentIssue.Kind.Malformed))
  }

  "A backend error that is not a refusal" should "fail the step, and recover read again" in {
    val store    = new InMemoryCheckpointer
    val thread   = ThreadId("store-down")
    val counting = new CountingBackend(storeBackend())
    seed(counting, "/skills/pdf/SKILL.md" -> skill("pdf", "PDFs."))
    counting.failing = Some(ValidationError("store", "down"))
    val model      = new Recording
    val loop       = loopOf(model, Seq(new SkillsMiddleware(Vector(p("/skills")), counting)))
    val (_, error) = GraphRuntime(store).start(thread, loop.graph, AgentInput("q")).awaited.value.failed
    error.message should include("down")
    counting.failing = None
    GraphRuntime(store).recover(thread, loop.graph).awaited.value.answered
    model.system(0) should include("- pdf: PDFs.")
  }

  "The cache" should "hold at most maxCachedRuns runs (rule 12)" in {
    val backend = storeBackend()
    seed(backend, "/skills/pdf/SKILL.md" -> skill("pdf", "PDFs."))
    val mw = new SkillsMiddleware(Vector(p("/skills")), backend, maxCachedRuns = 2)
    (1 to 4).foreach(i => runInMemory(loopOf(new Recording, Seq(mw)).graph, AgentInput("q"), thread = s"t$i").answered)
    mw.cachedRuns shouldBe 2
  }

  "SkillsMiddleware" should "refuse limits below 1, and expose allowed-tools without enforcing them" in {
    an[IllegalArgumentException] should be thrownBy new SkillsMiddleware(Vector.empty, storeBackend(), maxSkills = 0)
    an[IllegalArgumentException] should be thrownBy new SkillsMiddleware(Vector.empty, storeBackend(), maxScanned = 0)
    an[IllegalArgumentException] should be thrownBy new SkillsMiddleware(
      Vector.empty,
      storeBackend(),
      maxCachedRuns = 0
    )
    val backend = storeBackend()
    seed(backend, "/skills/pdf/SKILL.md" -> "---\nname: pdf\ndescription: d\nallowed-tools: Read Bash\n---")
    new SkillsMiddleware(Vector(p("/skills")), backend).skills(testModelContext()).value.map(_.allowedTools) shouldBe
      Vector(Vector("Read", "Bash"))
  }
}
