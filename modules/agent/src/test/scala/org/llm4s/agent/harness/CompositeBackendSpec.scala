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

  /** A backend whose `ls` refuses with `kind`; nothing else is asked of it. */
  final private class Refusing(kind: BackendRefused.Kind, typed: Boolean = false) extends FileBackend {
    private def no =
      Left(
        if (typed) BackendRefused.at("ls", VPath.root, s"refused ($kind)", kind)
        else BackendRefused("ls", "/", s"refused ($kind)", kind)
      )
    def writes                                                                                     = Set.empty
    def locks(path: VPath): Set[LockKey]                                                           = Set.empty
    def ls(path: VPath, limit: Int, ctx: FileContext)                                              = no
    def read(path: VPath, offset: Int, limit: Int, ctx: FileContext)                               = no
    def write(path: VPath, content: String, ctx: FileContext)                                      = no
    def edit(path: VPath, old: String, replacement: String, replaceAll: Boolean, ctx: FileContext) = no
    def delete(path: VPath, ctx: FileContext)                                                      = no
    def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext)                 = no
    def search(
      under: VPath,
      regex: java.util.regex.Pattern,
      accept: VPath => Boolean,
      limit: Int,
      ctx: FileContext
    ) = no
  }

  it should "stand a mount in only for a path-shape refusal, propagating every other refusal unchanged" in {
    for (kind <- Seq(BackendRefused.Kind.NotFound, BackendRefused.Kind.NotADirectory)) {
      val c = CompositeBackend(new Refusing(kind, typed = true), p("/m") -> main).value
      c.ls(VPath.root, 10, ctx).value.items.map(_.path) shouldBe Vector(p("/m"))
    }
    for (kind <- Seq(BackendRefused.Kind.Refused)) {
      val c       = CompositeBackend(new Refusing(kind, typed = true), p("/m") -> main).value
      val refusal = c.ls(VPath.root, 10, ctx).left.value
      refusal shouldBe a[BackendRefused]
      refusal.asInstanceOf[BackendRefused].kind shouldBe kind
    }
  }

  it should "refuse an over-long glob or grep pattern before using it, quoting only a prefix" in {
    // 20M characters: a glob compiled at construction would build an automaton of that many states
    val long = "a" * 20000000
    val g    = composite.glob(Glob(long), VPath.root, 10, ctx).left.value
    g.message should include("longer than 1000")
    g.message.length should be < 400
    val r = composite.grep(long, VPath.root, None, 10, ctx).left.value
    r.message should include("longer than 1000")
    r.message.length should be < 400
    composite.grep("x", VPath.root, Some(Glob(long)), 10, ctx).left.value.message should include("longer than 1000")
  }

  it should "name the composite path in a routed backend's refusal" in {
    composite.read(p("/scratch/nope"), 0, 10, ctx).left.value.message should include("/scratch/nope")
  }

  it should "take writes from every backend and locks from the routed one" in {
    composite.writes shouldBe Set(main.files, scratch.files, deep.files)
    composite.locks(p("/scratch/x")) shouldBe Set(LockKey("files/state/scratch"))
    composite.locks(p("/scratchy")) shouldBe Set(LockKey("files/state/files"))
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
    composite
      .grep("[a-z] [a-z]", VPath.root, None, 10, ctx)
      .value
      .items
      .map(m => (m.path.value, m.text)) shouldBe Vector(
      "/a.txt"              -> "main a",
      "/scratch/b.txt"      -> "scratch b",
      "/scratch/deep/c.txt" -> "deep c"
    )
    composite.grep("[a-z] [a-z]", VPath.root, None, 2, ctx).value.truncated shouldBe true
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

  it should "list a directory a mount makes only past a refusal of its shape, never past another" in {
    val denying = new FileBackend {
      def writes                                      = main.writes
      def locks(path: VPath)                          = main.locks(path)
      def ls(path: VPath, limit: Int, c: FileContext) = Left(BackendRefused("ls", path.value, "permission denied"))
      def read(path: VPath, offset: Int, limit: Int, c: FileContext)               = main.read(path, offset, limit, c)
      def write(path: VPath, content: String, c: FileContext)                      = main.write(path, content, c)
      def edit(path: VPath, old: String, r: String, all: Boolean, c: FileContext)  = main.edit(path, old, r, all, c)
      def delete(path: VPath, c: FileContext)                                      = main.delete(path, c)
      def find(under: VPath, accept: VPath => Boolean, limit: Int, c: FileContext) = main.find(under, accept, limit, c)
      def search(under: VPath, regex: java.util.regex.Pattern, accept: VPath => Boolean, limit: Int, c: FileContext) =
        main.search(under, regex, accept, limit, c)
    }
    val c = CompositeBackend(denying, p("/x/y") -> scratch).value
    c.ls(p("/x"), 5, ctx).left.value.message should include("permission denied")
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
    // backend-wide, whatever the resolved backend: a state backend's quota needs every mutation ordered
    resolved.locks(p("/x/y")) shouldBe Set(FileBackend.LockRoot)
    resolved.locks(p("/x/y")) shouldBe resolved.locks(p("/z"))
    resolved.writes shouldBe Set(forA.files, forB.files)
  }

  it should "fail, not refuse, when the resolved backend writes an undeclared key" in {
    val undeclared = FileBackend.resolved("bad")(_ => Right(StateFileBackend(keyId = "other")))
    val error      = undeclared.write(p("/x"), "y", ctx).left.value
    error shouldBe a[ValidationError]
    BackendRefused.toolOutcome(error) shouldBe a[org.llm4s.agent.graph.tool.ToolOutcome.Fatal]
  }

  it should "order its mutations with every other route to the same backend, another wrapper or a mount" in {
    val shared                                     = StateFileBackend(keyId = "shared")
    val one                                        = FileBackend.resolved("one", Set(shared.files))(_ => Right(shared))
    val two                                        = FileBackend.resolved("two", Set(shared.files))(_ => Right(shared))
    val direct                                     = CompositeBackend(main, p("/s") -> shared).value
    def conflict(a: Set[LockKey], b: Set[LockKey]) = a.exists(x => b.exists(_.conflictsWith(x)))
    conflict(one.locks(p("/x")), two.locks(p("/x"))) shouldBe true
    conflict(one.locks(p("/x")), direct.locks(p("/s/x"))) shouldBe true
    conflict(one.locks(p("/x")), shared.locks(p("/x"))) shouldBe true
  }

  it should "fail when the resolved backend locks outside the file lock root" in {
    final class Outside extends FileBackend {
      private val inner                                                = StateFileBackend()
      def writes                                                       = inner.writes
      def locks(path: VPath): Set[LockKey]                             = Set(LockKey("elsewhere"))
      def ls(path: VPath, limit: Int, ctx: FileContext)                = inner.ls(path, limit, ctx)
      def read(path: VPath, offset: Int, limit: Int, ctx: FileContext) = inner.read(path, offset, limit, ctx)
      def write(path: VPath, content: String, ctx: FileContext)        = inner.write(path, content, ctx)
      def edit(path: VPath, old: String, replacement: String, replaceAll: Boolean, ctx: FileContext) =
        inner.edit(path, old, replacement, replaceAll, ctx)
      def delete(path: VPath, ctx: FileContext) = inner.delete(path, ctx)
      def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext) =
        inner.find(under, accept, limit, ctx)
      def search(
        under: VPath,
        regex: java.util.regex.Pattern,
        accept: VPath => Boolean,
        limit: Int,
        ctx: FileContext
      ) = inner.search(under, regex, accept, limit, ctx)
    }
    val outside = FileBackend.resolved("outside", Set(main.files))(_ => Right(new Outside))
    outside.write(p("/x"), "y", ctx).left.value.message should include("locks outside files")
  }

  it should "name a mounted backend's descendant in its refusal by its composite path" in {
    commit(composite.write(p("/scratch/long.txt"), "a" * 50000, ctx))
    // a pattern past the pre-screen whose match is stopped on the mounted file
    val refusal = composite.grep("(?:a(?=a)|a)*b", p("/scratch"), None, 10, ctx).left.value
    refusal shouldBe a[BackendRefused]
    refusal.asInstanceOf[BackendRefused].target shouldBe "/scratch/long.txt"
  }

  it should "rebase only a refusal that names a path, never a key or pattern that looks like one" in {
    val untyped = CompositeBackend(main, p("/m") -> new Refusing(BackendRefused.Kind.Refused)).value
    untyped.read(p("/m/x"), 0, 1, ctx).left.value.asInstanceOf[BackendRefused].target shouldBe "/"
    val typed   = CompositeBackend(main, p("/m") -> new Refusing(BackendRefused.Kind.Refused, typed = true)).value
    val refusal = typed.read(p("/m/x"), 0, 1, ctx).left.value.asInstanceOf[BackendRefused]
    refusal.target shouldBe "/m"
    refusal.path shouldBe Some(p("/m"))
  }

  it should "name the composite path for every refusal a mounted backend makes of a path" in {
    val small       = StateFileBackend(keyId = "small", maxBytes = 40)
    val store       = InMemoryLongTermStore(StoreLimits(maxItemBytes = 60))
    val mem         = StoreFileBackend(store).value
    val c           = CompositeBackend(main, p("/s") -> small, p("/mem") -> mem).value
    var st          = stateOf(main.files, small.files)
    def in          = FileContext(testRunContext(), st)
    val (_, update) = c.write(p("/s/f"), "x", in).value
    st = committed(st, update).value
    // Text.edited's projection, past the state backend's bound
    c.edit(p("/s/f"), "x", "y" * 100, replaceAll = false, in)
      .left
      .value
      .asInstanceOf[BackendRefused]
      .target shouldBe "/s/f"
    // the store's own item bound, refused by the store under its key
    c.write(p("/mem/g"), "z" * 100, in).left.value.asInstanceOf[BackendRefused].target shouldBe "/mem/g"
    c.read(p("/s/f"), 0, 0, in).left.value.asInstanceOf[BackendRefused].target shouldBe "/s/f"
  }

  it should "walk the mounts beneath a root its routed backend finds absent, and only for that refusal" in {
    for (kind <- Seq(BackendRefused.Kind.NotFound, BackendRefused.Kind.NotADirectory)) {
      val c = CompositeBackend(new Refusing(kind, typed = true), p("/y") -> main).value
      c.glob(Glob("**"), VPath.root, 10, ctx).value.items shouldBe Vector(p("/y/a.txt"))
      c.grep("main", VPath.root, None, 10, ctx).value.items.map(_.path) shouldBe Vector(p("/y/a.txt"))
    }
    val refused = CompositeBackend(new Refusing(BackendRefused.Kind.Refused, typed = true), p("/y") -> main).value
    refused.glob(Glob("**"), VPath.root, 10, ctx).left.value shouldBe a[BackendRefused]
  }

  it should "leave a store's namespace-wide Corrupt refusal untyped, so no mount rebases it onto a file" in {
    val store    = InMemoryLongTermStore()
    val memories = Namespace(None, Vector("memories"))
    val effect   = org.llm4s.agent.graph.HarnessTestSupport.runOn("seed").effectKey("seed", "notes")
    store
      .modify[Unit](memories, "notes", effect, org.llm4s.agent.graph.FencingToken(0))(_ =>
        Right(StoreChange.Put(ujson.Obj("content" -> "x"), ()))
      )
      .value
    val c       = CompositeBackend(main, p("/mem") -> StoreFileBackend(store).value).value
    val refusal = c.write(p("/mem/f"), "y", ctx).left.value.asInstanceOf[BackendRefused]
    refusal.kind shouldBe BackendRefused.Kind.Corrupt
    refusal.path shouldBe None
    refusal.target shouldBe "files"
  }

  it should "name no path for a refusal of an entry a deeper mount shadows, and say so" in {
    val base = StateFileBackend(keyId = "base")
    val over = StateFileBackend(keyId = "over")
    val c    = CompositeBackend(base, p("/scratch") -> over).value
    var st   = stateOf(base.files, over.files)
    def in   = FileContext(testRunContext(), st)
    // another writer stored /scratch in the default, as a file with NUL: corrupt, and shadowed by the mount
    st = committed(
      st,
      StateUpdate.update(base.files, FileUpdate.Put(p("/scratch"), FileEntry("a\u0000b", java.time.Instant.EPOCH)))
    ).value
    val refusal = c.ls(VPath.root, 10, in).left.value.asInstanceOf[BackendRefused]
    refusal.kind shouldBe BackendRefused.Kind.Corrupt
    refusal.path shouldBe None
    refusal.message should include("shadowed")
  }

  it should "name no path for a refusal of an entry whose composite path would be too long" in {
    val base = StateFileBackend(keyId = "base2")
    val over = StateFileBackend(keyId = "over2")
    val c    = CompositeBackend(base, p("/scratch") -> over).value
    val long = p("/" + Seq.fill(5)("d" * 203).mkString("/")) // 1020 bytes: 1028 under /scratch
    var st   = stateOf(base.files, over.files)
    st = committed(
      st,
      StateUpdate.update(over.files, FileUpdate.Put(long, FileEntry("a\u0000b", java.time.Instant.EPOCH)))
    ).value
    val refusal = c.ls(p("/scratch"), 10, FileContext(testRunContext(), st)).left.value.asInstanceOf[BackendRefused]
    refusal.kind shouldBe BackendRefused.Kind.Corrupt
    refusal.path shouldBe None
    refusal.message should include("longer than a path may be")
  }
}
