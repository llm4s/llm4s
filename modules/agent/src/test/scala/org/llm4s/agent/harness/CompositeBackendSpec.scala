package org.llm4s.agent.harness

import org.llm4s.agent.graph.GraphTestSupport.testRunContext
import org.llm4s.agent.graph.HarnessTestSupport.{ committed, stateOf }
import org.llm4s.agent.graph.{ StateUpdate, TenantId, ThreadState, RunConfig }
import org.llm4s.agent.graph.tool.{ Glob, LockKey }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CompositeBackendSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def p(s: String): VPath = VPath(s).value

  private val main    = StateFileBackend()
  private val scratch = StateFileBackend(keyId = "scratch")
  private val deep    = StateFileBackend(keyId = "deep")
  private val composite =
    CompositeBackend(main, p("/scratch") -> scratch, p("/scratch/deep") -> deep).value

  private var state: ThreadState = stateOf(main.files, scratch.files, deep.files)
  private def ctx                = FileContext(testRunContext(), state)
  private def commit[A](r: Result[(A, StateUpdate)]): A = {
    val (a, update) = r.value
    state = committed(state, update).value
    a
  }

  commit(composite.write(p("/a.txt"), "main a", ctx))
  commit(composite.write(p("/scratch/b.txt"), "scratch b", ctx))
  commit(composite.write(p("/scratch/deep/c.txt"), "deep c", ctx))

  "CompositeBackend" should "route by the longest mount, passing the path relative to it" in {
    state.get(main.files).value.keySet shouldBe Set(p("/a.txt"))
    state.get(scratch.files).value.keySet shouldBe Set(p("/b.txt"))
    state.get(deep.files).value.keySet shouldBe Set(p("/c.txt"))
    composite.read(p("/scratch/deep/c.txt"), 0, 10, ctx).value.lines shouldBe Vector("deep c")
  }

  it should "name the composite path in a routed backend's refusal" in {
    composite.read(p("/scratch/nope"), 0, 10, ctx).left.value.message should include("/scratch/nope")
  }

  it should "take writes from every backend and locks from the routed one" in {
    composite.writes shouldBe Set(main.files, scratch.files, deep.files)
    composite.locks(p("/scratch/x")) shouldBe Set(LockKey("state-files/scratch"))
    composite.locks(p("/scratchy")) shouldBe Set(LockKey("state-files/files"))
  }

  it should "list mounts as directories, even where the routed backend has nothing" in {
    composite.ls(VPath.root, 10, ctx).value.items.map(i => (i.path.value, i.isDirectory)) shouldBe
      Vector("/a.txt" -> false, "/scratch" -> true)
    composite.ls(p("/scratch"), 10, ctx).value.items.map(_.path.value) shouldBe Vector(
      "/scratch/b.txt",
      "/scratch/deep"
    )
  }

  it should "glob and grep across every backend beneath `under`, in path order" in {
    composite.glob(Glob("**/*.txt"), VPath.root, 10, ctx).value.items.map(_.value) shouldBe
      Vector("/a.txt", "/scratch/b.txt", "/scratch/deep/c.txt")
    composite.glob(Glob("deep/*.txt"), p("/scratch"), 10, ctx).value.items.map(_.value) shouldBe
      Vector("/scratch/deep/c.txt")
    composite.grep(" ", VPath.root, None, 10, ctx).value.items.map(m => (m.path.value, m.text)) shouldBe Vector(
      "/a.txt"              -> "main a",
      "/scratch/b.txt"      -> "scratch b",
      "/scratch/deep/c.txt" -> "deep c"
    )
    composite.grep(" ", VPath.root, None, 2, ctx).value.truncated shouldBe true
  }

  it should "never list a path of a backend that a deeper mount shadows" in {
    // scratch has its own /deep/hidden.txt, unreachable behind the /scratch/deep mount
    val (_, update) = scratch.write(p("/deep/hidden.txt"), "hidden", ctx).value
    val shadowed    = committed(state, update).value
    val sctx        = FileContext(testRunContext(), shadowed)
    composite
      .glob(Glob("**"), VPath.root, 10, sctx)
      .value
      .items
      .map(_.value) should not contain "/scratch/deep/hidden.txt"
    composite.ls(p("/scratch/deep"), 10, sctx).value.items.map(_.path.value) shouldBe Vector("/scratch/deep/c.txt")
  }

  it should "treat an ancestor of a mount as a directory, whatever the default holds there" in {
    // /scratch/deep is mounted; with only that route, /scratch would route to the default
    val onlyDeep = CompositeBackend(main, p("/scratch/deep") -> deep).value
    onlyDeep.write(p("/scratch"), "x", ctx).left.value.message should include("is a directory")
    onlyDeep.read(p("/scratch"), 0, 1, ctx).left.value.message should include("is a directory")
    onlyDeep.edit(p("/scratch"), "x", "y", replaceAll = false, ctx).left.value.message should include("is a directory")
    onlyDeep.delete(p("/scratch"), ctx).left.value.message should include("is a directory")
    // a file the default already holds there is never listed as one
    val (_, update) = main.write(p("/scratch"), "stale", ctx).value
    val sctx        = FileContext(testRunContext(), committed(state, update).value)
    onlyDeep.ls(VPath.root, 10, sctx).value.items.map(i => (i.path.value, i.isDirectory)) should contain(
      "/scratch" -> true
    )
    onlyDeep
      .ls(VPath.root, 10, sctx)
      .value
      .items
      .map(i => (i.path.value, i.isDirectory)) should not contain ("/scratch" -> false)
    onlyDeep.glob(Glob("**"), VPath.root, 10, sctx).value.items.map(_.value) should not contain "/scratch"
    onlyDeep.grep("stale", VPath.root, None, 10, sctx).value.items shouldBe empty
  }

  it should "refuse an invalid limit even where a mount makes the directory" in {
    composite.ls(VPath.root, 0, ctx).left.value.message should include("limit")
    CompositeBackend(main, p("/x/y") -> scratch).value.ls(p("/x"), 0, ctx).left.value.message should include("limit")
    CompositeBackend(main, p("/x/y") -> scratch).value
      .ls(p("/x"), 5, ctx)
      .value
      .items
      .map(_.path.value) shouldBe Vector("/x/y")
  }

  it should "refuse the root as a mount and a repeated mount" in {
    CompositeBackend(main, VPath.root -> scratch).left.value shouldBe a[ValidationError]
    CompositeBackend(main, p("/x") -> scratch, p("/x") -> deep).left.value shouldBe a[ValidationError]
  }

  "FileBackend.resolved" should "pick a backend per run, and lock in its own space" in {
    val forA = StateFileBackend(keyId = "tenant-a")
    val forB = StateFileBackend(keyId = "tenant-b")
    val resolved = FileBackend.resolved("by-tenant", writes = Set(forA.files, forB.files)) { ctx =>
      ctx.run.config.tenantId.map(_.value) match {
        case Some("a") => Right(forA)
        case Some("b") => Right(forB)
        case other     => Left(ValidationError("tenant", s"no backend for $other"))
      }
    }
    var s             = stateOf(forA.files, forB.files)
    def in(t: String) = FileContext(testRunContext(RunConfig().withTenantId(TenantId(t))), s)
    val (_, update)   = resolved.write(p("/x"), "for a", in("a")).value
    s = committed(s, update).value
    resolved.read(p("/x"), 0, 1, in("a")).value.lines shouldBe Vector("for a")
    resolved.read(p("/x"), 0, 1, in("b")).left.value shouldBe a[BackendRefused]
    resolved.read(p("/x"), 0, 1, FileContext(testRunContext(), s)).left.value shouldBe a[ValidationError]
    resolved.locks(p("/x/y")) shouldBe Set(LockKey("resolved/by-tenant/x/y"))
    resolved.writes shouldBe Set(forA.files, forB.files)
  }

  it should "fail, not refuse, when the resolved backend writes an undeclared key" in {
    val undeclared = FileBackend.resolved("bad")(_ => Right(StateFileBackend(keyId = "other")))
    val error      = undeclared.write(p("/x"), "y", ctx).left.value
    error shouldBe a[ValidationError]
    BackendRefused.toolOutcome(error) shouldBe a[org.llm4s.agent.graph.tool.ToolOutcome.Fatal]
  }
}
