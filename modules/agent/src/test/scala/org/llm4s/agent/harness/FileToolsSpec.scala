package org.llm4s.agent.harness

import org.llm4s.agent.{ Agent, AgentResult, AgentStatus, CompletionFixture, ScriptedLLMClient }
import org.llm4s.agent.AgentFixture.built
import org.llm4s.agent.graph.{ GraphRuntime, InMemoryCheckpointer, ThreadId }
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  BoundaryResult,
  GuardrailBlocked,
  MiddlewareId,
  ModelContext,
  PermissionMiddleware,
  PermissionRule,
  ToolCallRequest
}
import org.llm4s.agent.graph.tool.{ AgentTool, LockKey, Operation, ToolContext, ToolOutcome, ToolResource, ToolSet }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, ToolCall, ToolMessage }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

/** Stage 3 slice F1 (#1905, design §4.32): the file tools through `Agent`. */
class FileToolsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def p(s: String): VPath = VPath(s).value

  private def toolSet(backend: FileBackend, limits: FileToolLimits = FileToolLimits.default): ToolSet =
    ToolSet.of(FileTools(backend, limits)*).fold(e => fail(e.message), identity)

  private val ids = Iterator.from(1)

  /** One model message calling each of `calls` (a tool name and its arguments). */
  private def batch(calls: (String, ujson.Obj)*): Completion =
    CompletionFixture.withMessage(
      AssistantMessage(None, calls.map((name, args) => ToolCall(s"c${ids.next()}", name, args)))
    )

  /** A tool error as the loop records it for the model. */
  private def err(message: String): String = ujson.write(ujson.Obj("error" -> message))

  private def results(result: AgentResult): Vector[String] =
    result.messages.collect { case t: ToolMessage => t.content }.toVector

  /** Runs each message in turn, one call per message, then a final answer; every tool result. */
  private def sequential(
    backend: FileBackend,
    calls: Seq[(String, ujson.Obj)],
    limits: FileToolLimits = FileToolLimits.default
  ): Vector[String] =
    val client = ScriptedLLMClient.of((calls.map(c => batch(c)) :+ CompletionFixture.simple("done"))*)
    results(built(Agent.builder("assistant", client).withTools(toolSet(backend, limits))).run("go").value)

  private def write(path: String, content: String) =
    FileTools.WriteFile -> ujson.Obj("path" -> path, "content" -> content)
  private def read(path: String, more: (String, ujson.Value)*) =
    FileTools.ReadFile -> ujson.Obj.from(("path" -> ujson.Str(path)) +: more)
  private def edit(path: String, old: String, next: String, all: Boolean = false) =
    FileTools.EditFile -> ujson.Obj("path" -> path, "old_string" -> old, "new_string" -> next, "replace_all" -> all)

  private def composite(): FileBackend =
    CompositeBackend(StateFileBackend(), p("/memories") -> StoreFileBackend(InMemoryLongTermStore()).value).value

  private val backends: Seq[(String, () => FileBackend)] =
    Seq("state" -> (() => StateFileBackend()), "composite" -> (() => composite()))

  "FileTools" should "offer the seven tools, by their public names, as the backend declares them" in {
    val backend = StateFileBackend()
    val tools   = FileTools(backend)
    tools.map(_.spec.name) shouldBe FileTools.Names
    val byName = tools.map(t => t.spec.name -> t).toMap
    for (name <- Seq(FileTools.Ls, FileTools.ReadFile, FileTools.Glob, FileTools.Grep)) withClue(name) {
      byName(name).spec.locksOf shouldBe None
      byName(name).writes shouldBe empty
      byName(name).spec.hints.readOnly shouldBe true
    }
    for (name <- Seq(FileTools.WriteFile, FileTools.EditFile, FileTools.Delete)) withClue(name) {
      byName(name).writes shouldBe backend.writes
      byName(name).spec.hints.readOnly shouldBe false
      byName(name).spec.hints.destructive shouldBe true
    }
    byName(FileTools.EditFile).spec.hints.idempotent shouldBe false
    tools.foreach(_.spec.hints.openWorld shouldBe false)
  }

  it should "run each tool on state and composite backends" in {
    for ((name, backend) <- backends) withClue(name) {
      val out = sequential(
        backend(),
        Seq(
          write("/src/a.txt", "one\ntwo\nthree"),
          write("/src/sub/b.txt", "two too"),
          write("/memories/note.md", "remember two"),
          FileTools.Ls -> ujson.Obj("path" -> "/src"),
          read("/src/a.txt"),
          FileTools.Glob -> ujson.Obj("pattern" -> "**/*.txt", "path" -> "/src"),
          FileTools.Grep -> ujson.Obj("pattern" -> "two", "path" -> "/", "include" -> ujson.Null),
          edit("/src/a.txt", "two", "2"),
          read("/src/a.txt", "offset" -> ujson.Num(2), "limit" -> ujson.Num(1)),
          FileTools.Delete -> ujson.Obj("path" -> "/src/sub/b.txt"),
          FileTools.Ls     -> ujson.Obj("path" -> ujson.Null)
        )
      )
      out(0) shouldBe "Created /src/a.txt (13 bytes)"
      out(2) shouldBe "Created /memories/note.md (12 bytes)"
      out(3) shouldBe "/src/a.txt (13 bytes)\n/src/sub/"
      out(4) shouldBe "     1\tone\n     2\ttwo\n     3\tthree"
      out(5) shouldBe "/src/a.txt\n/src/sub/b.txt"
      out(6) shouldBe "/memories/note.md:1: remember two\n/src/a.txt:2: two\n/src/sub/b.txt:1: two too"
      out(7) shouldBe "Edited /src/a.txt: 1 replacement"
      out(8) shouldBe "     2\t2\n(lines 2-2 of 3; continue with offset=3)"
      out(9) shouldBe "Deleted /src/sub/b.txt"
      out(10) shouldBe "/memories/\n/src/"
    }
  }

  it should "refuse an absent or ambiguous old_string, and replace every occurrence on request" in {
    val out = sequential(
      StateFileBackend(),
      Seq(
        write("/a.txt", "x y x"),
        edit("/a.txt", "x", "z"),
        edit("/a.txt", "q", "z"),
        edit("/a.txt", "x", "z", all = true),
        read("/a.txt")
      )
    )
    out(1) should include("occurs 2 times")
    out(2) should include("not found")
    out(3) shouldBe "Edited /a.txt: 2 replacements"
    out(4) shouldBe "     1\tz y z"
  }

  it should "refuse a relative or unsafe path, a bad offset, and a missing file, as errors the model sees" in {
    val out = sequential(
      StateFileBackend(),
      Seq(
        write("src/a.txt", "x"),
        read("/a/../b"),
        write("/a.txt", "one"),
        read("/a.txt", "offset" -> ujson.Num(0)),
        read("/a.txt", "offset" -> ujson.Num(5)),
        read("/missing"),
        FileTools.Ls -> ujson.Obj("path" -> "/a.txt")
      )
    )
    out(0) should include("absolute path")
    out(1) should include("path")
    out(3) should include("offset")
    out(4) shouldBe err("offset 5 is past the end of /a.txt, which has 1 lines")
    out(5) should include("not found")
    out(6) should include("not a directory")
  }

  it should "refuse an oversized glob or grep pattern before it becomes a resource a rule compares" in {
    val long = "*" * 1001
    val out = sequential(
      StateFileBackend(),
      Seq(
        FileTools.Glob -> ujson.Obj("pattern" -> long),
        FileTools.Grep -> ujson.Obj("pattern" -> "x", "include" -> long),
        FileTools.Grep -> ujson.Obj("pattern" -> long)
      )
    )
    out.foreach(_ should include("longer than 1000"))
  }

  "read_file" should "page by readLimit, number lines from offset, and cut long lines" in {
    val limits = FileToolLimits(readLimit = 2, maxLineChars = 5)
    val out = sequential(
      StateFileBackend(),
      Seq(
        write("/a.txt", "1\n2\n3\n4\n0123456789"),
        read("/a.txt"),
        read("/a.txt", "offset" -> ujson.Num(3), "limit" -> ujson.Num(100)),
        read("/a.txt", "offset" -> ujson.Num(5)),
        write("/empty", ""),
        read("/empty")
      ),
      limits
    )
    out(1) shouldBe "     1\t1\n     2\t2\n(lines 1-2 of 5; continue with offset=3)"
    // the model's limit is capped at readLimit
    out(2) shouldBe "     3\t3\n     4\t4\n(lines 3-4 of 5; continue with offset=5)"
    out(3) shouldBe "     5\t01234 [... line cut at 5 characters]"
    out(5) shouldBe "(/empty is an empty file)"
  }

  "ls, glob and grep" should "stop at maxResults and say so" in {
    val limits = FileToolLimits(maxResults = 2)
    val out = sequential(
      StateFileBackend(),
      Seq(
        write("/a", "x"),
        write("/b", "x"),
        write("/c", "x"),
        FileTools.Ls   -> ujson.Obj(),
        FileTools.Glob -> ujson.Obj("pattern" -> "*"),
        FileTools.Grep -> ujson.Obj("pattern" -> "x"),
        FileTools.Glob -> ujson.Obj("pattern" -> "*.none"),
        FileTools.Grep -> ujson.Obj("pattern" -> "zzz")
      ),
      limits
    )
    val stop = "(stopped at 2 results; narrow the path or pattern)"
    out(3) shouldBe s"/a (1 bytes)\n/b (1 bytes)\n$stop"
    out(4) shouldBe s"/a\n/b\n$stop"
    out(5) shouldBe s"/a:1: x\n/b:1: x\n$stop"
    out(6) shouldBe "No files match *.none under /"
    out(7) shouldBe "No lines match zzz under /"
  }

  "Every result" should "be at most maxResultChars, cut with a note, whatever the backend returns" in {
    val limits = FileToolLimits(maxResultChars = 300, maxLineChars = 1000)
    val line   = "y" * 100
    val out = sequential(
      StateFileBackend(),
      Seq(
        write("/big.txt", Seq.fill(50)(line).mkString("\n")),
        read("/big.txt"),
        FileTools.Grep -> ujson.Obj("pattern" -> "y"),
        write("/" + "n" * 200, "x"),
        FileTools.Ls -> ujson.Obj()
      ),
      limits
    )
    out.foreach(_.length should be <= 300)
    out(1) should endWith("[output cut at 300 characters]")
    out(2) should endWith("[output cut at 300 characters]")
  }

  "Bounded" should "keep the trailer and the cut note within the bound, never splitting a surrogate pair" in {
    val rendered = Bounded.render(Iterator.fill(100)("abcdefghij"), Some("(trailer)"), 60)
    rendered.length should be <= 60
    rendered should endWith("(trailer)\n[output cut at 60 characters]")
    Bounded.render(Iterator("a", "b"), None, 60) shouldBe "a\nb"
    val pair = "😀" * 10
    Bounded.line(pair, 3) should startWith("😀 [")
    Bounded.cap("x" * 1000, 256).length should be <= 256
    Bounded.cap("short", 256) shouldBe "short"
  }

  "The guide's file tools example" should "compile and run as written" in {
    import org.llm4s.agent.graph.tool.ToolSet
    val client = ScriptedLLMClient.of(batch(write("/a.txt", "x")), CompletionFixture.simple("done"))
    // as in docs/guide/agents/index.md, "Harness File Tools"
    val built = for {
      tools <- ToolSet.of(FileTools(StateFileBackend(), FileToolLimits(maxResultChars = 20000))*)
      agent <- Agent.builder("coder", client).withTools(tools).build()
    } yield agent
    built.value.run("go").value.answer shouldBe Some("done")
  }

  "FileToolLimits" should "refuse a limit under its least value" in {
    an[IllegalArgumentException] should be thrownBy FileToolLimits(readLimit = 0)
    an[IllegalArgumentException] should be thrownBy FileToolLimits(maxResultChars = 100)
    FileToolLimits.default.withMaxResults(7).maxResults shouldBe 7
  }

  // Rule 1: locks order a batch's mutations; reads see the snapshot.

  "Two edits of one path in one batch" should "both apply, the second against the first's result" in {
    for ((name, backend) <- backends) withClue(name) {
      val b = backend()
      val client = ScriptedLLMClient.of(
        batch(write("/a.txt", "one two")),
        batch(edit("/a.txt", "one", "1"), edit("/a.txt", "two", "2")),
        batch(read("/a.txt")),
        CompletionFixture.simple("done")
      )
      results(built(Agent.builder("assistant", client).withTools(toolSet(b))).run("go").value).last shouldBe
        "     1\t1 2"
    }
  }

  "A read in the same batch as a write" should "see the file as it was before the batch" in {
    val client = ScriptedLLMClient.of(
      batch(write("/a.txt", "before")),
      batch(write("/a.txt", "after"), read("/a.txt")),
      CompletionFixture.simple("done")
    )
    results(
      built(Agent.builder("assistant", client).withTools(toolSet(StateFileBackend()))).run("go").value
    ).last shouldBe "     1\tbefore"
  }

  "A parallel batch of writes over the quota" should "refuse exactly the write that overflows" in {
    val client = ScriptedLLMClient.of(
      batch(write("/a", "0123456789"), write("/b", "0123456789"), write("/c", "0123456789")),
      CompletionFixture.simple("done")
    )
    val out = results(
      built(Agent.builder("assistant", client).withTools(toolSet(StateFileBackend(maxBytes = 30)))).run("go").value
    )
    out.take(2) shouldBe Vector("Created /a (10 bytes)", "Created /b (10 bytes)")
    out(2) should include("over this thread's limit of 30")
  }

  // Permission rules (C2) on the tools' resources.

  "Permission rules on paths" should "decide each tool by its path, and a search by its whole envelope" in {
    val rules = new PermissionMiddleware(
      Seq(
        PermissionRule.deny(Operation.Write, "/etc/**"),
        PermissionRule.allow(Operation.Write, "/**"),
        PermissionRule.deny(Operation.Read, "/secrets/**"),
        PermissionRule.allow(Operation.Read, "/**"),
        PermissionRule.deny(Operation.Delete, "/**")
      )
    )
    val calls = Seq(
      write("/etc/passwd", "x"),
      write("/src/a.scala", "val secret = 1"),
      write("/secrets/key", "k"),
      read("/secrets/key"),
      FileTools.Grep   -> ujson.Obj("pattern" -> "secret"),
      FileTools.Grep   -> ujson.Obj("pattern" -> "secret", "path" -> "/src"),
      FileTools.Glob   -> ujson.Obj("pattern" -> "**", "path" -> "/secrets"),
      FileTools.Glob   -> ujson.Obj("pattern" -> "*.scala", "path" -> "/src"),
      FileTools.Delete -> ujson.Obj("path" -> "/src/a.scala"),
      FileTools.Ls     -> ujson.Obj("path" -> "/secrets")
    )
    val client = ScriptedLLMClient.of((calls.map(c => batch(c)) :+ CompletionFixture.simple("done"))*)
    val out = results(
      built(Agent.builder("assistant", client).withTools(toolSet(StateFileBackend())).withMiddleware(rules))
        .run("go")
        .value
    )
    out(0) shouldBe err("Denied: write /etc/passwd")
    out(1) shouldBe "Created /src/a.scala (14 bytes)"
    out(2) shouldBe "Created /secrets/key (1 bytes)"
    out(3) shouldBe err("Denied: read /secrets/key")
    out(4) shouldBe err("Denied: read /**") // the root search reaches /secrets
    out(5) shouldBe "/src/a.scala:1: val secret = 1"
    out(6) shouldBe err("Denied: read /secrets")
    out(7) shouldBe "/src/a.scala"
    out(8) shouldBe err("Denied: delete /src/a.scala")
    out(9) shouldBe err("Denied: read /secrets")
  }

  // Rules 4, 5 and 10: recover, replay and Block.

  /** A tool that fails the run (`Fatal`) until `fixed`. */
  private def flaky(fixed: AtomicBoolean): AgentTool[?] =
    org.llm4s.agent.SpecTools.tool("flaky") { (_, _) =>
      if fixed.get then ToolOutcome.Success(org.llm4s.agent.graph.tool.ToolContent.Json(ujson.Str("fixed")))
      else ToolOutcome.Fatal(ValidationError("flaky", "down"))
    }

  "An edit in a batch whose other call failed" should "be visible after recover, applied once" in {
    val fixed   = new AtomicBoolean(false)
    val backend = StateFileBackend()
    val client = ScriptedLLMClient.of(
      batch(write("/a.txt", "x")),
      batch(edit("/a.txt", "x", "xx", all = true), "flaky" -> ujson.Obj("text" -> "go")),
      batch(read("/a.txt")),
      CompletionFixture.simple("done")
    )
    val tools  = ToolSet.of((FileTools(backend) :+ flaky(fixed))*).value
    val agent  = built(Agent.builder("assistant", client).withTools(tools))
    val thread = ThreadId("recovered")
    agent.run(thread, "go").isLeft shouldBe true
    fixed.set(true)
    results(agent.recover(thread).value).last shouldBe "     1\txx"
  }

  /** Fails the first run of `edit_file` after it ran: its effect applied, its outcome lost (a crash). */
  final private class CrashAfterEdit extends AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("crash")
    private val crashed  = new AtomicBoolean(false)
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      val outcome = next()
      if request.spec.name == FileTools.EditFile && !crashed.getAndSet(true) then
        ToolOutcome.Fatal(ValidationError("crash", "lost after the edit"))
      else outcome
  }

  "An edit of a store file whose task failed after it applied" should "replay its outcome on recover, editing once" in {
    val backend = StoreFileBackend(InMemoryLongTermStore()).value
    val client = ScriptedLLMClient.of(
      batch(write("/a.txt", "x")),
      batch(edit("/a.txt", "x", "xx", all = true)),
      batch(read("/a.txt")),
      CompletionFixture.simple("done")
    )
    val agent = built(Agent.builder("assistant", client).withTools(toolSet(backend)).withMiddleware(new CrashAfterEdit))
    val thread = ThreadId("replayed")
    agent.run(thread, "go").isLeft shouldBe true
    val out = results(agent.recover(thread).value)
    out(1) shouldBe "Edited /a.txt: 1 replacement" // the recorded outcome, not a second edit's
    out.last shouldBe "     1\txx"
  }

  /** Blocks an answer that mentions a secret. */
  private object BlockSecrets extends AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("block")
    override def afterAgent(text: String, context: ModelContext): Result[BoundaryResult] =
      if text.contains("secret") then Left(GuardrailBlocked("secrets", "no")) else Right(BoundaryResult(text))
  }

  "An output Block" should "undo the turn's edits of state files" in {
    val store   = new InMemoryCheckpointer
    val thread  = ThreadId("blocked")
    val backend = StateFileBackend()
    val client = ScriptedLLMClient.of(
      batch(write("/a.txt", "v1")),
      CompletionFixture.simple("written"),
      batch(edit("/a.txt", "v1", "v2"), write("/b.txt", "new")),
      CompletionFixture.simple("the secret is v2"),
      batch(read("/a.txt"), FileTools.Ls -> ujson.Obj()),
      CompletionFixture.simple("done")
    )
    val agent = built(
      Agent
        .builder("assistant", client)
        .withTools(toolSet(backend))
        .withMiddleware(BlockSecrets)
        .withRuntime(GraphRuntime(store))
    )
    agent.run(thread, "write").value.answer shouldBe Some("written")
    agent.run(thread, "edit").value.status shouldBe a[AgentStatus.Blocked]
    results(agent.run(thread, "read").value).takeRight(2) shouldBe Vector("     1\tv1", "/a.txt (2 bytes)")
  }

  /** `tool`'s locks and resources for the arguments `json`, as the loop computes them from the decoded call. */
  private def declared(tool: AgentTool[?], json: ujson.Value): (Option[Set[LockKey]], Vector[ToolResource]) =
    tool match {
      case t: AgentTool[a] =>
        val args = upickle.default.read[a](json)(using t.spec.codec)
        (t.spec.locksOf.map(_(args)), t.spec.resourcesOf(args))
    }

  "Each tool" should "lock a mutation's path as the backend says, and declare all it may touch" in {
    val backend                           = composite()
    val byName                            = FileTools(backend).map(t => t.spec.name -> t).toMap
    def of(name: String, json: ujson.Obj) = declared(byName(name), json)
    def read(targets: String*)            = targets.map(ToolResource(Operation.Read, _)).toVector
    of(FileTools.WriteFile, ujson.Obj("path" -> "/memories/a.md", "content" -> "x")) shouldBe
      (Some(backend.locks(p("/memories/a.md"))), Vector(ToolResource(Operation.Write, "/memories/a.md")))
    of(FileTools.EditFile, ujson.Obj("path" -> "/a", "old_string" -> "x", "new_string" -> "y")) shouldBe
      (Some(backend.locks(p("/a"))), Vector(ToolResource(Operation.Write, "/a")))
    of(FileTools.Delete, ujson.Obj("path" -> "/a")) shouldBe
      (Some(backend.locks(p("/a"))), Vector(ToolResource(Operation.Delete, "/a")))
    of(FileTools.ReadFile, ujson.Obj("path" -> "/a")) shouldBe (None, read("/a"))
    of(FileTools.Ls, ujson.Obj()) shouldBe (None, read("/"))
    // a search declares its root too: a backend searches the root itself when it is a file
    of(FileTools.Glob, ujson.Obj("pattern" -> "**/*.md")) shouldBe (None, read("/", "/**/*.md"))
    of(FileTools.Glob, ujson.Obj("pattern" -> "*.md", "path" -> "/src")) shouldBe (None, read("/src", "/src/*.md"))
    of(FileTools.Grep, ujson.Obj("pattern" -> "x", "path" -> "/src")) shouldBe (None, read("/src", "/src/**"))
    of(FileTools.Grep, ujson.Obj("pattern" -> "x", "include" -> "*.md")) shouldBe (None, read("/", "/**/*.md"))
    of(FileTools.Grep, ujson.Obj("pattern" -> "x", "path" -> "/src", "include" -> "sub/*.md")) shouldBe
      (None, read("/src", "/src/sub/*.md"))
  }

  "A search rooted at a denied file" should "be denied, though its envelope beneath the file matches no rule" in {
    val rules = new PermissionMiddleware(
      Seq(
        PermissionRule.deny(Operation.Read, "/secrets/key"), // matches no path beneath /secrets/key
        PermissionRule.allow(Operation.Read, "/**"),
        PermissionRule.allow(Operation.Write, "/**")
      )
    )
    val calls = Seq(
      write("/secrets/key", "k"),
      FileTools.Grep -> ujson.Obj("pattern" -> "k", "path" -> "/secrets/key", "include" -> "key"),
      FileTools.Glob -> ujson.Obj("pattern" -> "*", "path" -> "/secrets/key")
    )
    val client = ScriptedLLMClient.of((calls.map(c => batch(c)) :+ CompletionFixture.simple("done"))*)
    val out = results(
      built(
        Agent
          .builder("assistant", client)
          .withTools(toolSet(StateFileBackend()))
          .withMiddleware(rules)
      ).run("go").value
    )
    out(1) shouldBe err("Denied: read /secrets/key")
    out(2) shouldBe err("Denied: read /secrets/key")
  }

  "An error result" should "be at most maxResultChars as the loop renders it, escapes included" in {
    // a path of quotes: each takes two characters escaped, so the message alone is over the cap once rendered
    val quoted = "/" + "\"" * 200
    val out = sequential(
      StateFileBackend(),
      Seq(write(quoted, "a\u0000b"), read(quoted)),
      FileToolLimits(maxResultChars = 256)
    )
    out.foreach { r =>
      r.length should be <= 256
      r should include("[output cut at 256 characters]")
    }
    Bounded.error("short", 256) shouldBe "short"
    val cut = Bounded.error("\u0001" * 100 + "😀" * 100, 256)
    ujson.Obj("error" -> cut).render().length should be <= 256
  }
}
