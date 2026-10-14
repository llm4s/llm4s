package org.llm4s.agent.harness

import org.llm4s.agent.graph.GraphTestSupport.testRunContext
import org.llm4s.agent.graph.HarnessTestSupport.stateOf
import org.llm4s.agent.graph.{
  FencingToken,
  GraphError,
  IdempotencyKey,
  ReplaySupport,
  RunConfig,
  StateUpdate,
  TenantId,
  ToolCallId
}
import org.llm4s.agent.graph.tool.{ Glob, LockKey, ToolContext }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneOffset }
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

class StoreBackendsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC)

  private def p(s: String): VPath = VPath(s).value

  private def call(tenant: Option[String] = None): FileContext = {
    val config = RunConfig().withTenantId(tenant.map(TenantId(_)))
    FileContext(
      ToolContext(testRunContext(config), ToolCallId("c"), IdempotencyKey(UUID.randomUUID().toString), stateOf())
    )
  }

  private def done[A](r: Result[(A, StateUpdate)]): A = r.value._1

  private val ns = Namespace(None, Vector("notes"))

  "InMemoryLongTermStore" should "put, get, list by prefix, search and delete, through the guard" in {
    val store = InMemoryLongTermStore(clock = clock)
    store.replaySupport shouldBe ReplaySupport.Transactional
    store.put(ns, "a/1", ujson.Obj("text" -> "Scala is nice"), call()).value shouldBe true
    store.put(ns, "a/1", ujson.Obj("text" -> "Scala is great"), call()).value shouldBe false
    store.put(ns, "b/1", ujson.Str("other"), call()).value shouldBe true
    store.get(ns, "a/1").value.map(_.value("text").str) shouldBe Some("Scala is great")
    store.get(ns, "a/1").value.map(_.updated) shouldBe Some(clock.instant())
    store.list(ns, "a/", 10).value.items.map(_.key) shouldBe Vector("a/1")
    store.search(ns, "GREAT", 10).value.items.map(_.key) shouldBe Vector("a/1")
    store.search(ns, "b/", 10).value.items.map(_.key) shouldBe Vector("b/1")
    store.delete(ns, "a/1", call()).value shouldBe true
    store.delete(ns, "a/1", call()).value shouldBe false
    store.appliedKeys shouldBe 0
  }

  it should "keep namespaces apart, the tenant included" in {
    val store = InMemoryLongTermStore()
    val nsA   = Namespace(Some(TenantId("a")), Vector("notes"))
    store.put(nsA, "k", ujson.Str("a's"), call()).value
    store.get(ns, "k").value shouldBe None
    store.get(Namespace(Some(TenantId("b")), Vector("notes")), "k").value shouldBe None
    nsA.render should not be ns.render
    Namespace(Some(TenantId("x")), Vector.empty).render should not be Namespace(None, Vector("x")).render
  }

  it should "bound items and namespaces inside the transaction, as refusals" in {
    val store = InMemoryLongTermStore(StoreLimits(maxItemBytes = 20, maxItems = 2, maxBytes = 30))
    store.put(ns, "k", ujson.Str("x" * 30), call()).left.value.message should include("over the limit of 20")
    store.put(ns, "a", ujson.Str("0123456789"), call()).value // 1 + 12
    store.put(ns, "b", ujson.Str("0123456789"), call()).value // 26
    store.put(ns, "c", ujson.Str(""), call()).left.value.message should include("3 items")
    store.put(ns, "b", ujson.Str("0123456789012345"), call()).left.value.message should include(
      "bytes, over its limit of 30"
    )
    store.get(ns, "c").value shouldBe None
    an[IllegalArgumentException] should be thrownBy StoreLimits(maxItemBytes = 2 << 20)
  }

  it should "return a recorded outcome for an applied key, writing nothing, even after another writer" in {
    val store = InMemoryLongTermStore()
    val same  = call()
    val key   = same.effectKey("test.put", "k")
    val runs  = new AtomicInteger()
    def change(view: StoreView) = {
      runs.incrementAndGet(); Right(StoreChange.Put(ujson.Str("first"), view.current.isEmpty))
    }
    store.modify[Boolean](ns, "k", key, FencingToken(0))(change).value shouldBe true
    store.put(ns, "k", ujson.Str("someone else"), call()).value
    store.modify[Boolean](ns, "k", key, FencingToken(0))(change).value shouldBe true
    runs.get shouldBe 1
    store.get(ns, "k").value.map(_.value.str) shouldBe Some("someone else")
    store.appliedKeys shouldBe 1
    store.forget(key, FencingToken(0)).value
    store.appliedKeys shouldBe 0
  }

  it should "refuse an older claim's token per thread, changing nothing, for modify and forget alike" in {
    val store = InMemoryLongTermStore()
    val ctx   = call()
    val key   = ctx.effectKey("test.put", "k")
    def put(token: Long, value: String) =
      store.modify[Unit](ns, "k", key, FencingToken(token))(_ => Right(StoreChange.Put(ujson.Str(value), ())))
    put(5, "new claimant").value
    store.forget(key, FencingToken(5)).value
    put(3, "stale").left.value.message should include("stale claim")
    store.get(ns, "k").value.map(_.value.str) shouldBe Some("new claimant")
    store.forget(key, FencingToken(3)).left.value.message should include("stale claim")
    // another thread's keys have a scope of their own
    val other = FileContext(org.llm4s.agent.graph.HarnessTestSupport.runOn("another thread"), stateOf())
      .effectKey("test.put", "k")
    store.modify[Unit](ns, "k2", other, FencingToken(1))(_ => Right(StoreChange.Keep(()))).isRight shouldBe true
  }

  it should "match case-insensitively whatever the JVM's locale, and refuse a key that is not valid Unicode" in {
    val store = InMemoryLongTermStore()
    store.put(ns, "title", ujson.Str("istanbul"), call()).value
    val previous = java.util.Locale.getDefault
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
    val hits = store.search(ns, "ISTANBUL", 10)
    java.util.Locale.setDefault(previous)
    hits.value.items.map(_.key) shouldBe Vector("title")
    store.put(ns, "bad\ud800", ujson.Str("x"), call()).left.value.message should include("not valid Unicode")
  }

  it should "refuse an outcome over the replay cap, writing and recording nothing" in {
    val store = InMemoryLongTermStore()
    val key   = call().effectKey("test.put", "k")
    store
      .modify[String](ns, "k", key, FencingToken(0))(_ => Right(StoreChange.Keep("x" * (2 << 20))))
      .left
      .value
      .message should include("replay cap")
    store.appliedKeys shouldBe 0
  }

  it should "refuse a value nested deeper than MaxDepth, without overflowing the stack" in {
    val store = InMemoryLongTermStore()
    val deep  = (1 to 100000).foldLeft[ujson.Value](ujson.Num(1))((v, _) => ujson.Arr(v))
    store.put(ns, "deep", deep, call()).left.value.message should include("nested deeper")
    val ok = (1 to LongTermStore.MaxDepth - 1).foldLeft[ujson.Value](ujson.Num(1))((v, _) => ujson.Arr(v))
    store.put(ns, "ok", ok, call()).value shouldBe true
    store.appliedKeys shouldBe 0
  }

  it should "record nothing for a refused change" in {
    val store = InMemoryLongTermStore()
    val key   = call().effectKey("test.put", "k")
    store
      .modify[Boolean](ns, "k", key, FencingToken(0))(_ => Left(BackendRefused("put", "k", "no")))
      .left
      .value shouldBe a[BackendRefused]
    store.appliedKeys shouldBe 0
  }

  "StoreFileBackend" should "keep files in the run's tenant's namespace" in {
    val store = InMemoryLongTermStore(clock = clock)
    val files = StoreFileBackend(store)
    done(files.write(p("/notes.md"), "a's note", call(Some("a")))) shouldBe FileWritten(created = true, 8)
    files.read(p("/notes.md"), 0, 10, call(Some("a"))).value.lines shouldBe Vector("a's note")
    files.read(p("/notes.md"), 0, 10, call(Some("b"))).left.value.message should include("not found")
    files.read(p("/notes.md"), 0, 10, call()).left.value.message should include("not found")
    store.get(Namespace(Some(TenantId("a")), Vector("memories")), "/notes.md").value.isDefined shouldBe true
  }

  it should "refuse a store item with NUL in it as binary, and skip it in grep" in {
    val store = InMemoryLongTermStore()
    val files = StoreFileBackend(store)
    val key = org.llm4s.agent.graph.HarnessTestSupport
      .runOn("another writer")
      .effectKey("seed", "/nul.md")
    store
      .modify[Unit](Namespace(None, Vector("memories")), "/nul.md", key, FencingToken(0))(_ =>
        Right(StoreChange.Put(ujson.Obj("content" -> "a\u0000b find"), ()))
      )
      .value
    files.read(p("/nul.md"), 0, 10, call()).left.value.message should include("binary")
    files.grep("find", VPath.root, None, 10, call()).value.items shouldBe empty
  }

  it should "shape its tree by files only: an item that is not a file neither makes a directory nor is overwritten" in {
    val store = InMemoryLongTermStore()
    val files = StoreFileBackend(store)
    val ns    = Namespace(None, Vector("memories"))
    val key   = org.llm4s.agent.graph.HarnessTestSupport.runOn("another writer").effectKey("seed", "meta")
    store
      .modify[Unit](ns, "/docs/meta", key, FencingToken(0))(_ =>
        Right(StoreChange.Put(ujson.Obj("kind" -> "meta"), ()))
      )
      .value
    done(files.write(p("/docs"), "a file, as /docs holds no file beneath it", call())).created shouldBe true
    files.write(p("/docs/meta"), "x", call()).left.value.message should include("not a file")
    store.get(ns, "/docs/meta").value.map(_.value("kind").str) shouldBe Some("meta")
    val key2 = org.llm4s.agent.graph.HarnessTestSupport.runOn("another writer").effectKey("seed", "other")
    store.modify[Unit](ns, "/other", key2, FencingToken(0))(_ => Right(StoreChange.Put(ujson.Num(1), ()))).value
    files.write(p("/other"), "x", call()).left.value.message should include("not a file")
    store.get(ns, "/other").value.map(_.value.num) shouldBe Some(1)
  }

  it should "edit inside the transaction, list implicit directories, glob and grep" in {
    val files = StoreFileBackend(InMemoryLongTermStore(clock = clock))
    done(files.write(p("/user/prefs.md"), "likes scala\nlikes tea", call()))
    done(files.edit(p("/user/prefs.md"), "tea", "coffee", replaceAll = false, call())) shouldBe FileEdited(1)
    files.edit(p("/user/prefs.md"), "tea", "x", replaceAll = false, call()).left.value.message should include(
      "not found"
    )
    files.edit(p("/nope"), "a", "b", replaceAll = false, call()).left.value.message should include("not found")
    files.ls(VPath.root, 10, call()).value.items.map(i => (i.path.value, i.isDirectory)) shouldBe Vector(
      "/user" -> true
    )
    files.glob(Glob("**/*.md"), VPath.root, 10, call()).value.items shouldBe Vector(p("/user/prefs.md"))
    files.grep("coffee", VPath.root, None, 10, call()).value.items shouldBe Vector(
      GrepMatch(p("/user/prefs.md"), 2, "likes coffee")
    )
    files.write(p("/user"), "x", call()).left.value.message should include("is a directory")
    files.delete(p("/user"), call()).left.value.message should include("is a directory")
    done(files.delete(p("/user/prefs.md"), call())) shouldBe FileDeleted(wasDirectory = false)
    files.delete(p("/user/prefs.md"), call()).left.value.message should include("not found")
  }

  it should "return a replayed edit's first result, not \"`old` was not found\"" in {
    val files = StoreFileBackend(InMemoryLongTermStore())
    done(files.write(p("/a.md"), "x y", call()))
    val same = call().tool.get
    done(files.edit(p("/a.md"), "x", "z", replaceAll = false, FileContext(same))) shouldBe FileEdited(1)
    done(files.edit(p("/a.md"), "x", "z", replaceAll = false, FileContext(same))) shouldBe FileEdited(1)
    files.read(p("/a.md"), 0, 1, call()).value.lines shouldBe Vector("z y")
  }

  it should "decide the tree's shape in the transaction: of a file and a path beneath it written at once, one wins" in {
    (1 to 50).foreach { _ =>
      val files = StoreFileBackend(InMemoryLongTermStore())
      val start = new java.util.concurrent.CyclicBarrier(2)
      val pool  = java.util.concurrent.Executors.newFixedThreadPool(2)
      val writes = Vector("/a", "/a/b").map(path =>
        pool.submit { () =>
          start.await(); files.write(p(path), "x", call())
        }
      )
      val results = writes.map(_.get(10, java.util.concurrent.TimeUnit.SECONDS))
      pool.shutdown()
      results.count(_.isRight) shouldBe 1
      results.collect { case Left(e) => e }.head shouldBe a[BackendRefused]
    }
  }

  "InMemoryLongTermStore" should "copy values in and out, so no caller can change what it stores" in {
    val store = InMemoryLongTermStore()
    val input = ujson.Obj("n" -> 1)
    store.put(ns, "k", input, call()).value
    input("n") = 2
    store.get(ns, "k").value.get.value("n").num shouldBe 1
    store.get(ns, "k").value.get.value("n") = 3
    store.list(ns, "", 10).value.items.head.item.value("n") = 4
    store.search(ns, "k", 10).value.items.head.item.value("n") = 5
    store.get(ns, "k").value.get.value("n").num shouldBe 1
    val key = call().effectKey("test.put", "k")
    store
      .modify[Unit](ns, "k", key, FencingToken(0)) { view =>
        view.current.get.value("n") = 6
        Left(BackendRefused("put", "k", "no"))
      }
      .left
      .value shouldBe a[BackendRefused]
    store.get(ns, "k").value.get.value("n").num shouldBe 1
  }

  it should "refuse a write over the store's bounds as a tool error, and lock by path" in {
    val files = StoreFileBackend(InMemoryLongTermStore(StoreLimits(maxItemBytes = 40)), Vector("mem"))
    val error = files.write(p("/big.md"), "x" * 50, call()).left.value
    BackendRefused.toolOutcome(error) shouldBe a[org.llm4s.agent.graph.tool.ToolOutcome.Error]
    files.locks(p("/a/b.md")) shouldBe Set(LockKey("store/mem/a/b.md"))
  }

  // Sandbox

  final private class CountingRunner(output: String = "ok") extends SandboxRunner {
    val runs                                = new AtomicInteger()
    def locks(workdir: VPath): Set[LockKey] = Set(LockKey("workspace", "w"))
    def run(command: String, workdir: VPath, timeout: FiniteDuration, ctx: FileContext): Result[ExecResult] = {
      runs.incrementAndGet()
      Right(ExecResult(0, output, s"$command in ${workdir.value}"))
    }
  }

  "SandboxBackend" should "run through the guard, replaying a completed command's result without running it" in {
    val runner  = new CountingRunner()
    val sandbox = SandboxBackend(runner)
    sandbox.replaySupport shouldBe ReplaySupport.Unverifiable
    sandbox.locks(p("/src")) shouldBe Set(LockKey("workspace/w"))
    val same = call().tool.get
    sandbox.execute("ls", p("/src"), 1.second, FileContext(same)).value shouldBe ExecResult(0, "ok", "ls in /src")
    sandbox.execute("ls", p("/src"), 1.second, FileContext(same)).value shouldBe ExecResult(0, "ok", "ls in /src")
    runner.runs.get shouldBe 1
    sandbox.execute("ls", p("/src"), 1.second, call()).value
    runner.runs.get shouldBe 2
  }

  it should "cut each stream at maxOutputBytes on a character boundary before recording it" in {
    val sandbox = SandboxBackend(new CountingRunner("é" * 10), maxOutputBytes = 5)
    val result  = sandbox.execute("cmd", VPath.root, 1.second, call()).value
    result.stdout shouldBe "éé"
    result.stderr shouldBe "cmd i"
    result.truncated shouldBe true
    an[IllegalArgumentException] should be thrownBy SandboxBackend(new CountingRunner(), maxOutputBytes = 1 << 20)
  }

  it should "bound each stream's encoded size, so an output of control characters still fits a record" in {
    val sandbox = SandboxBackend(new CountingRunner("\u0001" * 100), maxOutputBytes = 60)
    val result  = sandbox.execute("cmd", VPath.root, 1.second, call()).value
    result.stdout shouldBe "\u0001" * 10 // 6 encoded bytes each
    result.truncated shouldBe true
    Text.jsonBytes(result.stdout) should be <= 60L
    val worst = SandboxBackend(new CountingRunner("\u0001" * (1 << 20)), maxOutputBytes = SandboxBackend.MaxOutputBytes)
    val recorded = upickle.default.write(worst.execute("x" * 1000, VPath.root, 1.second, call()).value)
    recorded.length should be < org.llm4s.agent.graph.ReplayGuard.DefaultMaxOutcomeBytes
  }

  it should "fail before recording an intent when its runner cannot be resolved, so a retry runs" in {
    val runner    = new CountingRunner()
    var available = false
    val sandbox = SandboxBackend.resolved(_ => Set(LockKey("sandbox")))(_ =>
      if available then Right(runner) else Left(org.llm4s.error.ProcessingError("sandbox", "unavailable"))
    )
    val same = call().tool.get
    sandbox.execute("ls", VPath.root, 1.second, FileContext(same)).left.value.message should include("unavailable")
    available = true
    sandbox.execute("ls", VPath.root, 1.second, FileContext(same)).value.stdout shouldBe "ok"
    runner.runs.get shouldBe 1
  }

  it should "resolve its runner per call" in {
    val runnerA = new CountingRunner("a")
    val sandbox = SandboxBackend.resolved(_ => Set(LockKey("sandbox")))(ctx =>
      ctx.run.config.tenantId
        .map(_.value)
        .filter(_ == "a")
        .map(_ => runnerA)
        .toRight(BackendRefused("execute", "x", "no sandbox"))
    )
    sandbox.execute("ls", VPath.root, 1.second, call(Some("a"))).value.stdout shouldBe "a"
    sandbox.execute("ls", VPath.root, 1.second, call(Some("b"))).left.value shouldBe a[BackendRefused]
  }

  it should "encode its result" in {
    val r = ExecResult(2, "out", "err", truncated = true)
    upickle.default.read[ExecResult](upickle.default.write(r)) shouldBe r
  }

  "BackendRefused.toolOutcome" should "make refusals tool errors and anything else fatal" in {
    import org.llm4s.agent.graph.tool.ToolOutcome
    BackendRefused.toolOutcome(BackendRefused("a", "b", "c")) shouldBe ToolOutcome.Error("a b: c")
    BackendRefused.toolOutcome(GraphError.EffectReplayRefused("op", "why")) shouldBe a[ToolOutcome.Error]
    BackendRefused.toolOutcome(org.llm4s.error.ProcessingError("x", "y")) shouldBe a[ToolOutcome.Fatal]
  }
}
