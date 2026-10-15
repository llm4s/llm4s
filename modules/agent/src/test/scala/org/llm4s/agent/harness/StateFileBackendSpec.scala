package org.llm4s.agent.harness

import org.llm4s.agent.{ Agent, AgentResult, CompletionFixture }
import org.llm4s.agent.AgentFixture.built
import org.llm4s.agent.SpecTools.{ call, calling, Text }
import org.llm4s.agent.graph.HarnessTestSupport.{ committed, stateOf }
import org.llm4s.agent.graph.GraphTestSupport.testRunContext
import org.llm4s.agent.graph.{ StateUpdate, ThreadState }
import org.llm4s.agent.graph.tool.{ AgentTool, Glob, LockKey, ToolOutcome }
import org.llm4s.agent.{ ScriptedLLMClient, SpecTools }
import org.llm4s.llmconnect.model.ToolMessage
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneOffset }

class StateFileBackendSpec extends AnyFlatSpec with Matchers with EitherValues with org.scalatest.OptionValues {

  private val clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC)

  private def p(s: String): VPath = VPath(s).value

  /** A backend, and a context over a state with its key, as a tool call sees it. */
  final private class Fixture(backend: StateFileBackend) {
    var state: ThreadState                = stateOf(backend.files)
    def ctx: FileContext                  = FileContext(testRunContext(), state)
    def commit(update: StateUpdate): Unit = state = committed(state, update).value
    def write(path: String, content: String): FileWritten = {
      val (written, update) = backend.write(p(path), content, ctx).value
      commit(update)
      written
    }
  }

  private def fixture(backend: StateFileBackend = StateFileBackend(clock = clock)) = (backend, new Fixture(backend))

  "StateFileBackend" should "write, read back by line and report creation" in {
    val (backend, f) = fixture()
    f.write("/a.txt", "one\ntwo\nthree\n") shouldBe FileWritten(created = true, 14)
    f.write("/a.txt", "one\ntwo\nthree\n").created shouldBe false
    backend.read(p("/a.txt"), 1, 5, f.ctx).value shouldBe FileLines(Vector("two", "three"), 1, 3)
    backend.read(p("/a.txt"), 0, 1, f.ctx).value shouldBe FileLines(Vector("one"), 0, 3)
    f.state.get(backend.files).value(p("/a.txt")).modified shouldBe clock.instant()
  }

  it should "refuse an edit whose result would pass the limit before building it" in {
    val (backend, f) = fixture()
    f.write("/big.txt", "a" * 100000)
    // 100 000 occurrences each grown by 99 999 characters: about 10^10, which no JVM here could allocate
    val refusal = backend.edit(p("/big.txt"), "a", "b" * 100000, replaceAll = true, f.ctx).left.value
    refusal shouldBe a[BackendRefused]
    refusal.message should include("larger than the limit")
  }

  "Text.edited" should "size the result exactly, growing or shrinking, against the bound" in {
    val path = p("/t")
    org.llm4s.agent.harness.Text.edited(path, "abcb", "b", "xyz", replaceAll = true, 8).value shouldBe ("axyzcxyz", 2)
    org.llm4s.agent.harness.Text
      .edited(path, "abcb", "b", "xyz", replaceAll = true, 7)
      .left
      .value
      .message should include("at least 8")
    org.llm4s.agent.harness.Text.edited(path, "abcd", "b", "xyz", replaceAll = false, 6).value shouldBe ("axyzcd", 1)
    org.llm4s.agent.harness.Text.edited(path, "abcb", "bc", "", replaceAll = false, 2).value shouldBe ("ab", 1)
    org.llm4s.agent.harness.Text
      .edited(path, "a" * 3, "a", "b" * 1000, replaceAll = true, Long.MaxValue)
      .value
      ._1
      .length shouldBe 3000
  }

  "Text.utf8Bytes and Text.jsonValueBytes" should "count what getBytes and ujson.write produce, without producing it" in {
    for (s <- Seq("", "ascii", "\u00e9", "\u20ac", "\uD83D\uDE00", "lone \uD800 surrogate"))
      org.llm4s.agent.harness.Text
        .utf8Bytes(s) shouldBe s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
    val value = ujson.Obj(
      "content" -> "a \"quoted\" \\ line\n\tand \u0001 \u20ac",
      "n"       -> ujson.Arr(1, 2.5, -3, ujson.Null, ujson.True, ujson.False, ujson.Obj()),
      "empty"   -> ujson.Arr()
    )
    org.llm4s.agent.harness.Text.jsonValueBytes(value, Long.MaxValue) shouldBe
      ujson.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
    org.llm4s.agent.harness.Text
      .jsonValueBytes(ujson.Arr.from(Seq.fill(1000)(ujson.Str("x" * 100))), 10) should be > 10L
    // a wide value: its least size is over the bound before any child is visited
    val wide = ujson.Arr.from(Iterator.fill(2000000)(ujson.Null))
    org.llm4s.agent.harness.Text.jsonValueBytes(wide, 1000) should be > 1000L
    org.llm4s.agent.harness.Text
      .jsonValueBytes(ujson.Obj.from(Seq.tabulate(1000)(i => s"k$i" -> ujson.Null)), 10) should be > 10L
  }

  "Text.depthWithin" should "walk a wide value one level at a time and stop at the first level too deep" in {
    val wide = ujson.Arr.from(Iterator.fill(2000000)(ujson.Arr(ujson.Null)))
    org.llm4s.agent.harness.Text.depthWithin(wide, 2) shouldBe true
    org.llm4s.agent.harness.Text.depthWithin(wide, 1) shouldBe false
    val deep = (1 to 100).foldLeft[ujson.Value](ujson.Null)((v, _) => ujson.Obj("a" -> v))
    org.llm4s.agent.harness.Text.depthWithin(deep, 100) shouldBe true
    org.llm4s.agent.harness.Text.depthWithin(deep, 99) shouldBe false
  }

  it should "take Int.MaxValue as a limit, for listings and reads alike, without overflow" in {
    val (backend, f) = fixture()
    f.write("/a.txt", "one\ntwo\nthree")
    f.write("/b.txt", "x")
    backend.ls(VPath.root, Int.MaxValue, f.ctx).value shouldBe Listing(
      backend.ls(VPath.root, 10, f.ctx).value.items,
      false
    )
    backend.ls(VPath.root, Int.MaxValue, f.ctx).value.items should have size 2
    backend.glob(Glob("*"), VPath.root, Int.MaxValue, f.ctx).value.items should have size 2
    backend.grep("o", VPath.root, None, Int.MaxValue, f.ctx).value.items should have size 2
    backend.read(p("/a.txt"), 1, Int.MaxValue, f.ctx).value shouldBe FileLines(Vector("two", "three"), 1, 3)
    Listing.of(Iterator(1, 2), Int.MaxValue) shouldBe Listing(Vector(1, 2), truncated = false)
  }

  it should "refuse reading a missing file, a directory, or with a bad offset or limit" in {
    val (backend, f) = fixture()
    f.write("/d/a.txt", "x")
    backend.read(p("/nope"), 0, 1, f.ctx).left.value shouldBe a[BackendRefused]
    backend.read(p("/d"), 0, 1, f.ctx).left.value.message should include("is a directory")
    backend.read(p("/d/a.txt"), -1, 1, f.ctx).left.value shouldBe a[BackendRefused]
    backend.read(p("/d/a.txt"), 0, 0, f.ctx).left.value shouldBe a[BackendRefused]
  }

  it should "list implicit directories, in path order, cut at the limit" in {
    val (backend, f) = fixture()
    f.write("/b.txt", "bb")
    f.write("/a/x.txt", "x")
    f.write("/a/y/z.txt", "z")
    val listing = backend.ls(VPath.root, 10, f.ctx).value
    listing.items.map(i => (i.path.value, i.isDirectory)) shouldBe Vector("/a" -> true, "/b.txt" -> false)
    listing.items(1).size shouldBe 2
    backend.ls(p("/a"), 1, f.ctx).value shouldBe Listing(
      Vector(FileInfo(p("/a/x.txt"), false, 1, Some(clock.instant()))),
      true
    )
    backend.ls(p("/b.txt"), 10, f.ctx).left.value.message should include("not a directory")
    backend.ls(p("/c"), 10, f.ctx).left.value.message should include("not found")
  }

  it should "refuse a write over a directory, beneath a file, at the root, or of binary content" in {
    val (backend, f) = fixture()
    f.write("/a/x.txt", "x")
    backend.write(p("/a"), "y", f.ctx).left.value.message should include("is a directory")
    backend.write(p("/a/x.txt/z"), "y", f.ctx).left.value.message should include("beneath a file")
    backend.write(VPath.root, "y", f.ctx).left.value shouldBe a[BackendRefused]
    backend.write(p("/bin"), "a\u0000b", f.ctx).left.value.message should include("binary")
    backend.write(p("/bad"), "\ud800", f.ctx).left.value.message should include("not valid Unicode")
  }

  it should "edit one occurrence, every occurrence with replaceAll, and refuse an absent or ambiguous old" in {
    val (backend, f) = fixture()
    f.write("/a.txt", "x y x")
    backend.edit(p("/a.txt"), "x", "z", replaceAll = false, f.ctx).left.value.message should include("occurs 2 times")
    backend.edit(p("/a.txt"), "q", "z", replaceAll = false, f.ctx).left.value.message should include("not found")
    backend.edit(p("/a.txt"), "", "z", replaceAll = false, f.ctx).left.value shouldBe a[BackendRefused]
    val (edited, update) = backend.edit(p("/a.txt"), "x", "z", replaceAll = true, f.ctx).value
    edited shouldBe FileEdited(2)
    f.commit(update)
    backend.read(p("/a.txt"), 0, 10, f.ctx).value.lines shouldBe Vector("z y z")
    val (one, update2) = backend.edit(p("/a.txt"), "y", "w", replaceAll = false, f.ctx).value
    one shouldBe FileEdited(1)
    f.commit(update2)
    backend.read(p("/a.txt"), 0, 10, f.ctx).value.lines shouldBe Vector("z w z")
  }

  it should "delete a file, and refuse a directory or a missing path" in {
    val (backend, f) = fixture()
    f.write("/d/a.txt", "x")
    backend.delete(p("/d"), f.ctx).left.value.message should include("is a directory")
    backend.delete(p("/nope"), f.ctx).left.value.message should include("not found")
    val (deleted, update) = backend.delete(p("/d/a.txt"), f.ctx).value
    deleted shouldBe FileDeleted(wasDirectory = false)
    f.commit(update)
    f.state.get(backend.files).value shouldBe empty
  }

  it should "glob relative to `under` and grep by name or relative path" in {
    val (backend, f) = fixture()
    f.write("/src/a.scala", "val x = 1\nval y = 2")
    f.write("/src/sub/b.scala", "val yy = 3")
    f.write("/src/c.txt", "val y = 4")
    backend.glob(Glob("**/*.scala"), p("/src"), 10, f.ctx).value.items shouldBe Vector(
      p("/src/a.scala"),
      p("/src/sub/b.scala")
    )
    backend.glob(Glob("*.scala"), p("/src"), 10, f.ctx).value.items shouldBe Vector(p("/src/a.scala"))
    backend.grep("val y", VPath.root, Some(Glob("*.scala")), 10, f.ctx).value.items shouldBe Vector(
      GrepMatch(p("/src/a.scala"), 2, "val y = 2"),
      GrepMatch(p("/src/sub/b.scala"), 1, "val yy = 3")
    )
    backend.grep("val y", p("/src"), Some(Glob("sub/*.scala")), 10, f.ctx).value.items.map(_.path) shouldBe Vector(
      p("/src/sub/b.scala")
    )
    backend.grep("val", VPath.root, None, 2, f.ctx).value.truncated shouldBe true
    backend.grep("(", VPath.root, None, 2, f.ctx).left.value shouldBe a[BackendRefused]
  }

  it should "refuse a catastrophic or deeply recursing pattern as a tool error, never a thrown error" in {
    val (backend, f) = fixture()
    f.write("/a.txt", "a" * 50000)
    backend.grep("(a|aa)*b", VPath.root, None, 10, f.ctx).left.value shouldBe a[BackendRefused]
    backend.grep("(a+)+b", VPath.root, None, 10, f.ctx).left.value shouldBe a[BackendRefused]
    // a pattern past the pre-screen that still recurses: the overflow is contained as a refusal
    val recursing = backend.grep("(?:a(?=a)|a)*b", VPath.root, None, 10, f.ctx)
    recursing.isLeft shouldBe true
    recursing.left.value shouldBe a[BackendRefused]
  }

  it should "stop a grep whose thread is interrupted, as a cancellation" in {
    val (backend, f) = fixture()
    f.write("/a.txt", "a" * 30)
    Thread.currentThread().interrupt()
    val result = backend.grep("a", VPath.root, None, 10, f.ctx)
    Thread.interrupted() shouldBe true
    result.left.value shouldBe a[org.llm4s.error.CancelledError]
  }

  it should "bound the thread's bytes and files, counting paths, and let a replacement shrink back under" in {
    val (backend, f) = fixture(StateFileBackend(maxBytes = 20, maxFiles = 2, clock = clock))
    f.write("/a", "0123456789") // 2 + 10
    backend.write(p("/b"), "0123456", f.ctx).left.value.message should include("over this thread's limit of 20")
    f.write("/b", "012345") // 2 + 6 = 20 in all
    backend.write(p("/c"), "", f.ctx).left.value.message should include("over this thread's limit")
    f.write("/a", "") // shrinks
    backend.write(p("/c"), "", f.ctx).left.value.message should include("3 files")
  }

  it should "count what a checkpoint stores: content as a JSON string, escapes included" in {
    val (backend, f) = fixture(StateFileBackend(maxBytes = 20, clock = clock))
    // 2 path bytes + 3 control characters at 6 encoded bytes each = 20
    f.write("/a", "\u0001\u0001\u0001")
    backend.write(p("/b"), "", f.ctx).left.value.message should include("22 bytes")
  }

  it should "check several operations of one call against each other, the quota included" in {
    val (backend, f) = fixture(StateFileBackend(maxFiles = 1, clock = clock))
    val ctx          = f.ctx // one call
    val (_, first)   = backend.write(p("/a"), "x", ctx).value
    backend.write(p("/b"), "y", ctx).left.value.message should include("2 files")
    backend.read(p("/a"), 0, 1, ctx).value.lines shouldBe Vector("x") // the call sees its own write
    f.commit(first)
    f.state.get(backend.files).value.keySet shouldBe Set(p("/a"))
  }

  it should "lock every mutation on one backend-wide key, distinct per key id" in {
    val a = StateFileBackend()
    a.locks(p("/x")) shouldBe Set(LockKey("files/state/files"))
    a.locks(p("/x")) shouldBe a.locks(p("/y"))
    StateFileBackend(keyId = "scratch").locks(p("/x")) shouldBe Set(LockKey("files/state/scratch"))
    a.writes shouldBe Set(a.files)
  }

  "Stored files another writer of the key left invalid" should
    "be refused as Corrupt by every operation but delete, which repairs them" in {
      def corrupt[A](r: org.llm4s.types.Result[A], problem: String): Unit =
        r.left.value match {
          case b: BackendRefused =>
            b.kind shouldBe BackendRefused.Kind.Corrupt
            b.message should include(problem)
          case other => fail(s"expected a Corrupt refusal, got $other")
        }
      val small = StateFileBackend(maxFiles = 2, maxBytes = 60, clock = clock)
      val cases = Seq(
        // (the shape, the backend, what another writer stored, the entry to delete, what the refusal says)
        ("too many files", small, Seq("/a" -> "x", "/b" -> "x", "/c" -> "x"), "/c", "more than the limit of 2"),
        ("a file over the bound", small, Seq("/big" -> "x" * 70), "/big", "larger than the limit"),
        ("files over the bound together", small, Seq("/a" -> "x" * 40, "/b" -> "x" * 40), "/b", "limit"),
        ("binary content", StateFileBackend(clock = clock), Seq("/nul" -> "a\u0000b"), "/nul", "binary"),
        ("an unpaired surrogate", StateFileBackend(clock = clock), Seq("/s" -> "a\uD800b"), "/s", "not valid Unicode"),
        (
          "a file that is also a directory",
          StateFileBackend(clock = clock),
          Seq("/a" -> "x", "/a/b" -> "y"),
          "/a",
          "both a file and a directory"
        )
      )
      for ((shape, backend, stored, offending, problem) <- cases) withClue(shape) {
        val f = new Fixture(backend)
        stored.foreach((path, text) =>
          f.commit(StateUpdate.update(backend.files, FileUpdate.Put(p(path), FileEntry(text, clock.instant()))))
        )
        corrupt(backend.ls(VPath.root, 10, f.ctx), problem)
        corrupt(backend.read(p(stored.head._1), 0, 1, f.ctx), problem)
        corrupt(backend.write(p("/new"), "y", f.ctx), problem)
        corrupt(backend.edit(p(stored.head._1), "x", "y", replaceAll = false, f.ctx), problem)
        corrupt(backend.glob(Glob("**"), VPath.root, 10, f.ctx), problem)
        corrupt(backend.grep("x", VPath.root, None, 10, f.ctx), problem)
        // a delete reads the key as it is: the named entry goes, and the tree is valid again
        val (_, update) = backend.delete(p(offending), f.ctx).value
        f.commit(update)
        backend.ls(VPath.root, 10, f.ctx).isRight shouldBe true
      }
    }

  it should "refuse oversized content by its size before scanning it, in state and store alike" in {
    val (backend, f) = fixture(StateFileBackend(maxBytes = 100, clock = clock))
    // NUL past the bound: refused for its size, which is checked first
    backend.write(p("/big"), "x" * 200 + "\u0000", f.ctx).left.value.message should include("larger than")
    val store = StoreFileBackend(InMemoryLongTermStore(org.llm4s.agent.harness.StoreLimits(maxItemBytes = 100))).value
    store.write(p("/big"), "x" * 200 + "\u0000", f.ctx).left.value.message should include("larger than")
    // a value's size is counted only as far as the cap
    org.llm4s.agent.harness.Text.jsonValueBytes(ujson.Str("x" * 10000000), 100) should be <= 103L
  }

  it should "encode its key's values and updates, and refuse an invalid path on decode" in {
    val entry = FileEntry("x", clock.instant())
    upickle.default.read[FileEntry](upickle.default.write(entry)) shouldBe entry
    val put: FileUpdate = FileUpdate.Put(p("/a"), entry)
    upickle.default.read[FileUpdate](upickle.default.write(put)) shouldBe put
    an[Exception] should be thrownBy upickle.default.read[FileUpdate](
      upickle.default.write(put).replace("/a", "a/../b")
    )
  }

  // Through the agent loop: calls of one message under the backend's lock.

  /** `write_file` with text `path|content`; `edit_file` with text `path|old|new`; `read_file` with a path. */
  private def tools(backend: FileBackend) = {
    def lockOf(path: String) = VPath(path).fold(_ => Set(LockKey("")), backend.locks)
    def outcome[A](r: org.llm4s.types.Result[(A, StateUpdate)]): ToolOutcome =
      r.fold(BackendRefused.toolOutcome, (a, u) => ToolOutcome.Success(ujson.Str(a.toString), u))
    val write =
      AgentTool(SpecTools.spec("write_file").withLocks(t => lockOf(t.text.takeWhile(_ != '|'))), backend.writes) {
        (args, context) =>
          val Array(path, content) = args.text.split('|')
          outcome(backend.write(p(path), content, FileContext(context)))
      }
    val edit =
      AgentTool(SpecTools.spec("edit_file").withLocks(t => lockOf(t.text.takeWhile(_ != '|'))), backend.writes) {
        (args, context) =>
          val Array(path, old, next) = args.text.split('|')
          outcome(backend.edit(p(path), old, next, replaceAll = false, FileContext(context)))
      }
    val delete =
      AgentTool(SpecTools.spec("delete_file").withLocks(t => lockOf(t.text)), backend.writes) { (args, context) =>
        outcome(backend.delete(p(args.text), FileContext(context)))
      }
    val read = AgentTool(SpecTools.spec("read_file")) { (args, context) =>
      backend
        .read(p(args.text), 0, 100, FileContext(context))
        .fold(BackendRefused.toolOutcome, lines => ToolOutcome.Success(ujson.Str(lines.lines.mkString("\n"))))
    }
    SpecTools.set(write, edit, delete, read)
  }

  private def toolResults(result: AgentResult): Vector[String] =
    result.messages.collect { case t: ToolMessage => t.content }.toVector

  "Two edits of one path in one batch" should "both apply, the second against the first's result" in {
    val backend = StateFileBackend(clock = clock)
    val client = ScriptedLLMClient.of(
      calling(call("w", "write_file", "/a.txt|one two")),
      calling(call("e1", "edit_file", "/a.txt|one|1"), call("e2", "edit_file", "/a.txt|two|2")),
      calling(call("r", "read_file", "/a.txt")),
      CompletionFixture.simple("done")
    )
    val agent = built(Agent.builder("assistant", client).withTools(tools(backend)))
    toolResults(agent.run("go").value).last shouldBe "1 2"
  }

  "A parallel batch over quota" should "refuse exactly the write that overflows" in {
    val backend = StateFileBackend(maxBytes = 30, clock = clock)
    val client = ScriptedLLMClient.of(
      calling(
        call("a", "write_file", "/a|0123456789"), // 12
        call("b", "write_file", "/b|0123456789"), // 24
        call("c", "write_file", "/c|0123456789")  // 36: over
      ),
      CompletionFixture.simple("done")
    )
    val agent   = built(Agent.builder("assistant", client).withTools(tools(backend)))
    val results = toolResults(agent.run("go").value)
    results.take(2).foreach(_ should include("FileWritten(true,10)"))
    results(2) should include("over this thread's limit of 30")
  }

  // Graph evolution (C5, §4.29): adding file tools adds `files` and `tool-queue`.

  private def withStore(store: org.llm4s.agent.graph.InMemoryCheckpointer)(builder: org.llm4s.agent.AgentBuilder) =
    built(builder.withRuntime(org.llm4s.agent.graph.GraphRuntime(store)))

  "An agent's thread at rest" should "continue once state file tools are added, its files starting empty" in {
    val store  = new org.llm4s.agent.graph.InMemoryCheckpointer
    val thread = org.llm4s.agent.graph.ThreadId("upgraded")
    withStore(store)(Agent.builder("assistant", ScriptedLLMClient.of(CompletionFixture.simple("hi"))))
      .run(thread, "q")
      .value
    store.latest(thread).value.value.checkpoint.snapshot.state.keySet should not contain "files"

    val backend = StateFileBackend(clock = clock)
    val client =
      ScriptedLLMClient.of(calling(call("w", "write_file", "/a.txt|after")), CompletionFixture.simple("done"))
    val agent = withStore(store)(Agent.builder("assistant", client).withTools(tools(backend)))
    agent.run(thread, "write").value.answer shouldBe Some("done")
    val state = agent.loop.graph.restore(store.latest(thread).value.value.checkpoint.snapshot).value.state
    state.get(backend.files).value.keySet shouldBe Set(p("/a.txt"))
  }

  it should "be refused by an agent that dropped the tools while its files hold content, and not once they are deleted" in {
    val store   = new org.llm4s.agent.graph.InMemoryCheckpointer
    val backend = StateFileBackend(clock = clock)
    def withFiles(replies: org.llm4s.llmconnect.model.Completion*) =
      withStore(store)(Agent.builder("assistant", ScriptedLLMClient.of(replies*)).withTools(tools(backend)))
    def without = withStore(store)(Agent.builder("assistant", ScriptedLLMClient.of(CompletionFixture.simple("plain"))))

    val holding = org.llm4s.agent.graph.ThreadId("holding")
    withFiles(calling(call("w", "write_file", "/a.txt|x")), CompletionFixture.simple("done")).run(holding, "w").value
    val refused = without.run(holding, "again").left.value
    refused shouldBe a[org.llm4s.agent.graph.GraphError.RestoreRejected]
    refused.message should include("'files'")

    // the last delete removes the key's entry, so the thread no longer depends on the backend
    val emptied = org.llm4s.agent.graph.ThreadId("emptied")
    withFiles(
      calling(call("w", "write_file", "/a.txt|x")),
      calling(call("d", "delete_file", "/a.txt")),
      CompletionFixture.simple("done")
    ).run(emptied, "w").value
    store.latest(emptied).value.value.checkpoint.snapshot.state.keySet should not contain "files"
    without.run(emptied, "again").value.answer shouldBe Some("plain")
  }
}
