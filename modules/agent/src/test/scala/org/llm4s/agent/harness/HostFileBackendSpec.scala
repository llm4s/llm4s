package org.llm4s.agent.harness

import org.llm4s.agent.graph.GraphTestSupport.testRunContext
import org.llm4s.agent.graph.HarnessTestSupport.stateOf
import org.llm4s.agent.graph.{ IdempotencyKey, ReplaySupport, StateUpdate, ToolCallId }
import org.llm4s.agent.graph.tool.{ Glob, LockKey, ToolContext }
import org.llm4s.error.ValidationError
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, Path }
import java.util.UUID
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import scala.jdk.CollectionConverters._

class HostFileBackendSpec extends AnyFlatSpec with Matchers with EitherValues with BeforeAndAfterEach {

  private var dir: Path                   = _
  private val locks: Path                 = Files.createTempDirectory("llm4s-host-locks")
  private var backend: HostFileBackend    = _
  private var unsupported: Option[String] = None

  // (beforeEach has run when this does, as BeforeAndAfterEach wraps it)
  override def withFixture(test: NoArgTest) =
    unsupported match
      // only the cases with an injected probe need no supported file system
      case Some(why) if !test.name.contains("probe") =>
        org.scalatest.Canceled(s"unsupported file system: $why")
      case _ => super.withFixture(test)

  override def beforeEach(): Unit = {
    dir = Files.createTempDirectory("llm4s-host-backend")
    // the backend refuses a file system with aliases it cannot see (a runner's volume with 8.3 names on):
    // there is nothing of it to test on such a volume, so its cases are cancelled (withFixture), not failed
    HostFileBackend.open(dir, locks) match
      case Right(b) => backend = b; unsupported = None
      case Left(e) if e.message.contains("aliases") || e.message.contains("link counts") =>
        unsupported = Some(e.message)
      case Left(e) => fail(e.message)
  }

  /** Children and threads a case started, stopped after it whether it passed or not, so none outlives the JVM's tests. */
  private val cleanups = scala.collection.mutable.ListBuffer.empty[() => Unit]

  override def afterEach(): Unit =
    Option(backend).foreach(_.close())
    cleanups.foreach(c => scala.util.Try(c()))
    cleanups.clear()
    Files.walk(dir).iterator().asScala.toVector.reverse.foreach(p => Files.deleteIfExists(p))

  /** A child JVM holding the OS lock of `file` until its input closes; destroyed after the case. */
  private def holding(file: Path): Process =
    val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val child = new ProcessBuilder(
      javaBin,
      "-cp",
      System.getProperty("java.class.path"),
      "org.llm4s.agent.harness.HoldLock",
      file.toString
    )
      .redirectErrorStream(true)
      .start()
    cleanups += (() => child.destroyForcibly(): Unit)
    new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream)).readLine() shouldBe "locked"
    child

  /** A single daemon thread, shut down after the case. */
  private def worker(): java.util.concurrent.ExecutorService =
    val pool = Executors.newSingleThreadExecutor { r =>
      val t = new Thread(r); t.setDaemon(true); t
    }
    cleanups += (() => pool.shutdownNow(): Unit)
    pool

  private def p(s: String): VPath = VPath(s).value

  /** Links need a POSIX file system: Windows creates symbolic links only with a privilege, and reports no link count. */
  private val posix = java.nio.file.FileSystems.getDefault.supportedFileAttributeViews.contains("unix")

  /** A fresh tool call: its own key, so its own effects. */
  private def call(): FileContext =
    FileContext(ToolContext(testRunContext(), ToolCallId("c"), IdempotencyKey(UUID.randomUUID().toString), stateOf()))

  private def onDisk(rel: String): String = new String(Files.readAllBytes(dir.resolve(rel)), UTF_8)

  private def written[A](r: org.llm4s.types.Result[(A, StateUpdate)]): A = {
    val (a, update) = r.value
    update.isEmpty shouldBe true
    a
  }

  "HostFileBackend.open" should "resolve the root to its real path and refuse a missing or non-directory root" in {
    backend.root shouldBe dir.toRealPath()
    HostFileBackend.open(dir.resolve("missing"), locks).left.value shouldBe a[ValidationError]
    Files.writeString(dir.resolve("f"), "x")
    HostFileBackend.open(dir.resolve("f"), locks).left.value shouldBe a[ValidationError]
    HostFileBackend.open(dir, locks, maxFileBytes = 0).left.value shouldBe a[ValidationError]
    backend.replaySupport shouldBe ReplaySupport.Verifiable
  }

  "HostFileBackend" should "write (creating parents), read, edit and delete, as external effects" in {
    written(backend.write(p("/src/a.txt"), "one\ntwo\n", call())) shouldBe FileWritten(created = true, 8)
    onDisk("src/a.txt") shouldBe "one\ntwo\n"
    written(backend.write(p("/src/a.txt"), "one\ntwo\n", call())).created shouldBe false
    backend.read(p("/src/a.txt"), 1, 10, call()).value shouldBe FileLines(Vector("two"), 1, 2)
    written(backend.edit(p("/src/a.txt"), "two", "2", replaceAll = false, call())) shouldBe FileEdited(1)
    onDisk("src/a.txt") shouldBe "one\n2\n"
    written(backend.delete(p("/src/a.txt"), call())) shouldBe FileDeleted(wasDirectory = false)
    Files.exists(dir.resolve("src/a.txt")) shouldBe false
    written(backend.delete(p("/src"), call())) shouldBe FileDeleted(wasDirectory = true)
  }

  it should "write a file whose name is near the file system's limit" in {
    val long = "n" * 250
    written(backend.write(p(s"/$long"), "x", call())).created shouldBe true
    written(backend.edit(p(s"/$long"), "x", "y", replaceAll = false, call())) shouldBe FileEdited(1)
    onDisk(long) shouldBe "y"
  }

  it should "refuse a path named as its temporary files, which no listing would show" in {
    val tmp = s".llm4s-${UUID.randomUUID()}.tmp"
    backend.write(p(s"/$tmp"), "x", call()).left.value.message should include("temporary files")
    backend.write(p(s"/$tmp/inner.txt"), "x", call()).left.value.message should include("temporary files")
    Files.exists(dir.resolve(tmp)) shouldBe false
    // a case alias of a temporary name is one too, as a case-insensitive file system would resolve it
    backend.read(p(s"/${tmp.toUpperCase}"), 0, 1, call()).left.value.message should include("temporary files")
    backend.delete(p(s"/${tmp.toUpperCase}"), call()).left.value.message should include("temporary files")
  }

  it should "leave no temporary file behind, and never list one" in {
    written(backend.write(p("/a.txt"), "x", call()))
    Files.list(dir).iterator().asScala.map(_.getFileName.toString).toVector.sorted shouldBe Vector("a.txt")
    Files.writeString(dir.resolve(s".llm4s-${UUID.randomUUID()}.tmp"), "torn")
    backend.ls(VPath.root, 10, call()).value.items.map(_.path) shouldBe Vector(p("/a.txt"))
    backend.glob(Glob("**"), VPath.root, 10, call()).value.items shouldBe Vector(p("/a.txt"))
  }

  it should "refuse every symbolic link, inside the root or out, at any component" in {
    assume(posix)
    val outside = Files.createTempDirectory("llm4s-outside")
    Files.writeString(outside.resolve("secret.txt"), "secret")
    Files.createDirectories(dir.resolve("real"))
    Files.writeString(dir.resolve("real/inside.txt"), "inside")
    Files.createSymbolicLink(dir.resolve("out"), outside)
    Files.createSymbolicLink(dir.resolve("alias"), dir.resolve("real"))
    Files.createSymbolicLink(dir.resolve("file-link.txt"), dir.resolve("real/inside.txt"))

    backend.read(p("/out/secret.txt"), 0, 10, call()).left.value.message should include("symbolic link")
    backend.write(p("/out/new.txt"), "x", call()).left.value.message should include("symbolic link")
    backend.read(p("/alias/inside.txt"), 0, 10, call()).left.value.message should include("symbolic link")
    backend.edit(p("/file-link.txt"), "inside", "x", replaceAll = false, call()).left.value.message should include(
      "symbolic link"
    )
    backend.delete(p("/file-link.txt"), call()).left.value.message should include("symbolic link")
    Files.exists(outside.resolve("new.txt")) shouldBe false
    onDisk("real/inside.txt") shouldBe "inside"

    backend.ls(VPath.root, 10, call()).value.items.map(_.path) shouldBe Vector(p("/real"))
    backend.glob(Glob("**"), VPath.root, 10, call()).value.items shouldBe Vector(p("/real/inside.txt"))
    backend.grep("secret|inside", VPath.root, None, 10, call()).value.items.map(_.path) shouldBe Vector(
      p("/real/inside.txt")
    )
    Files.delete(outside.resolve("secret.txt"))
    Files.delete(outside)
  }

  it should "refuse a write beneath a file or over a directory, and a delete of a non-empty directory" in {
    written(backend.write(p("/d/a.txt"), "x", call()))
    backend.write(p("/d/a.txt/b"), "x", call()).left.value.message should include("beneath a file")
    backend.write(p("/d"), "x", call()).left.value.message should include("is a directory")
    backend.delete(p("/d"), call()).left.value.message should include("not empty")
    backend.delete(VPath.root, call()).left.value shouldBe a[BackendRefused]
    backend.delete(p("/nope"), call()).left.value.message should include("not found")
  }

  it should "refuse a host file with NUL in it as binary, as a write would, and skip it in grep" in {
    Files.write(dir.resolve("nul.txt"), "a\u0000b\nfind me".getBytes(UTF_8))
    backend.read(p("/nul.txt"), 0, 10, call()).left.value.message should include("binary")
    backend.edit(p("/nul.txt"), "a", "c", replaceAll = false, call()).left.value.message should include("binary")
    backend.grep("find", VPath.root, None, 10, call()).value.items shouldBe empty
  }

  it should "refuse binary content and a file that is not UTF-8, and skip it in grep" in {
    backend.write(p("/bin"), "a\u0000b", call()).left.value.message should include("binary")
    Files.write(dir.resolve("latin1.txt"), Array[Byte](0x63, 0xe9.toByte, 0x0a))
    backend.read(p("/latin1.txt"), 0, 10, call()).left.value.message should include("not UTF-8")
    backend.edit(p("/latin1.txt"), "c", "d", replaceAll = false, call()).left.value.message should include("not UTF-8")
    backend.grep("c", VPath.root, None, 10, call()).value.items shouldBe empty
  }

  it should "bound files by maxFileBytes and walks by maxScanEntries" in {
    val small = HostFileBackend.open(dir, locks, maxFileBytes = 4, maxScanEntries = 3).value
    small.write(p("/big"), "12345", call()).left.value.message should include("larger than the limit of 4")
    written(small.write(p("/ok"), "1234", call()))
    small.edit(p("/ok"), "1", "11", replaceAll = false, call()).left.value.message should include(
      "larger than the limit"
    )
    Files.writeString(dir.resolve("huge"), "1234567")
    small.read(p("/huge"), 0, 1, call()).left.value.message should include("larger than the limit")
    small.grep("1", VPath.root, None, 10, call()).value.items.map(_.path) shouldBe Vector(p("/ok"))
    (1 to 5).foreach(i => Files.writeString(dir.resolve(s"f$i"), "x"))
    small.glob(Glob("*"), VPath.root, 100, call()).value.truncated shouldBe true
    small.ls(VPath.root, 100, call()).value.truncated shouldBe true
    backend.glob(Glob("*"), VPath.root, 100, call()).value.truncated shouldBe false
  }

  it should "list the first names in order however large the directory, unlisted entries not counting" in {
    assume(posix)
    val capped = HostFileBackend.open(dir, locks, maxScanEntries = 3).value
    Seq("f", "e", "d", "c", "b").foreach(n => Files.writeString(dir.resolve(n), n))
    Seq("a1", "a2", "a3", "a4").foreach(n => Files.createSymbolicLink(dir.resolve(n), dir.resolve("b")))
    Files.writeString(dir.resolve(s".llm4s-${UUID.randomUUID()}.tmp"), "torn")
    val listed = capped.ls(VPath.root, 10, call()).value
    listed.items.map(_.path.value) shouldBe Vector("/b", "/c", "/d")
    listed.truncated shouldBe true
    capped.glob(Glob("*"), VPath.root, 10, call()).value shouldBe Listing(
      Vector(p("/b"), p("/c"), p("/d")),
      truncated = true
    )
    backend.ls(VPath.root, 2, call()).value.items.map(_.path.value) shouldBe Vector("/b", "/c")
  }

  "HostFileBackend.readAtMost" should "read at most one byte past the cap, however large the file" in {
    var taken = 0L
    val big = new java.nio.channels.ReadableByteChannel:
      private var open    = true
      def isOpen: Boolean = open
      def close(): Unit   = open = false
      def read(dst: java.nio.ByteBuffer): Int =
        val n = dst.remaining
        dst.position(dst.position + n)
        taken += n
        n
    HostFileBackend.readAtMost(big, 4) shouldBe None
    taken shouldBe 5L
    taken = 0
    val small = java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream("1234".getBytes(UTF_8)))
    HostFileBackend.readAtMost(small, 4).map(new String(_, UTF_8)) shouldBe Some("1234")
    // a cap that the read window cannot overflow: the largest allowed, over a small channel
    val tiny = java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream("ab".getBytes(UTF_8)))
    HostFileBackend.readAtMost(tiny, Long.MaxValue).map(new String(_, UTF_8)) shouldBe Some("ab")
    HostFileBackend.open(dir, locks, maxFileBytes = Long.MaxValue).left.value.message should include("at most")
  }

  it should "bound what it reads, not a size checked before reading" in {
    val small = HostFileBackend.open(dir, locks, maxFileBytes = 4).value
    Files.writeString(dir.resolve("exact"), "1234")
    Files.writeString(dir.resolve("over"), "12345")
    small.read(p("/exact"), 0, 1, call()).value.lines shouldBe Vector("1234")
    small.read(p("/over"), 0, 1, call()).left.value.message should include("larger than the limit")
    small.grep("1", VPath.root, None, 10, call()).value.items.map(_.path) shouldBe Vector(p("/exact"))
  }

  it should "take Int.MaxValue as a limit and as its scan bound, without overflow" in {
    val unbounded = HostFileBackend.open(dir, locks, maxScanEntries = Int.MaxValue).value
    Seq("a/x", "b").foreach(rel => written(unbounded.write(p(s"/$rel"), "one\ntwo", call())))
    unbounded.glob(Glob("**"), VPath.root, Int.MaxValue, call()).value shouldBe Listing(
      Vector(p("/a/x"), p("/b")),
      false
    )
    unbounded.ls(VPath.root, Int.MaxValue, call()).value.items.map(_.path) shouldBe Vector(p("/a"), p("/b"))
    unbounded.read(p("/b"), 1, Int.MaxValue, call()).value shouldBe FileLines(Vector("two"), 1, 2)
  }

  it should "treat names a case-insensitive file system takes for one file as one: locks and stripes" in {
    backend.locks(p("/A.txt")) shouldBe backend.locks(p("/a.txt"))
    backend.locks(p("/caf\u00e9")) shouldBe backend.locks(p("/cafe\u0301")) // NFC and NFD
    backend.lockFileOf(p("/A.txt")).value shouldBe backend.lockFileOf(p("/a.txt")).value
  }

  it should "end a search interrupted while reading a file as cancelled, not as an empty success" in {
    Files.writeString(dir.resolve("only.txt"), "needle")
    Thread.currentThread().interrupt()
    val result = backend.grep("needle", p("/only.txt"), None, 10, call())
    Thread.interrupted() shouldBe true
    result.left.value shouldBe a[org.llm4s.error.CancelledError]
  }

  it should "walk in the order of the paths it returns, a directory's entries after a sibling that sorts before '/'" in {
    Seq("a/x", "a-foo", "a.b", "b").foreach(rel => written(backend.write(p(s"/$rel"), "z", call())))
    backend.glob(Glob("**"), VPath.root, 10, call()).value.items shouldBe
      Vector("/a-foo", "/a.b", "/a/x", "/b").map(p).sorted
    backend.glob(Glob("**"), VPath.root, 1, call()).value.items shouldBe Vector(p("/a-foo"))
    backend.grep("z", VPath.root, None, 10, call()).value.items.map(_.path) shouldBe Vector(
      "/a-foo",
      "/a.b",
      "/a/x",
      "/b"
    ).map(p)
  }

  it should "stripe by the directory the file system resolves, so aliases of a directory share a stripe" in {
    Files.createDirectories(dir.resolve("real/sub"))
    backend.lockFileOf(p("/real/sub/a.txt")).value shouldBe backend.lockFileOf(p("/real/sub/b.txt")).value
    if posix then
      Files.createDirectories(dir.resolve("alias-target"))
      // two different directories may still share a stripe by hash; the point is aliases never differ
      backend.lockFileOf(p("/real/sub/new/deeper.txt")).value shouldBe backend.lockFileOf(p("/real/sub/x.txt")).value
  }

  it should "list and walk only regular files and directories, a FIFO not counting" in {
    assume(posix)
    val fifo = dir.resolve("a-fifo")
    assume(new ProcessBuilder("mkfifo", fifo.toString).start().waitFor() == 0)
    Files.writeString(dir.resolve("b.txt"), "b")
    val capped = HostFileBackend.open(dir, locks, maxScanEntries = 1).value
    capped.ls(VPath.root, 10, call()).value.items.map(_.path) shouldBe Vector(p("/b.txt"))
    capped.glob(Glob("*"), VPath.root, 10, call()).value.items shouldBe Vector(p("/b.txt"))
  }

  it should "drop a name it cannot list before it counts against the limit" in {
    assume(posix)
    Files.writeString(dir.resolve("a\nbad"), "x")
    Files.writeString(dir.resolve("z"), "z")
    backend.ls(VPath.root, 1, call()).value shouldBe Listing(
      backend.ls(VPath.root, 10, call()).value.items,
      truncated = false
    )
    backend.ls(VPath.root, 1, call()).value.items.map(_.path) shouldBe Vector(p("/z"))
  }

  it should "walk in sorted order, so a cut-off result is deterministic" in {
    Seq("c", "a/z", "a/b", "b").foreach(rel => written(backend.write(p(s"/$rel"), rel, call())))
    backend.glob(Glob("**"), VPath.root, 10, call()).value.items.map(_.value) shouldBe Vector(
      "/a/b",
      "/a/z",
      "/b",
      "/c"
    )
    backend.glob(Glob("**"), VPath.root, 2, call()).value shouldBe Listing(
      Vector(p("/a/b"), p("/a/z")),
      truncated = true
    )
  }

  it should "lock the folded physical path" in {
    val real = HostFileBackend.identity(dir.toRealPath().toString.replace('\\', '/'))
    backend.locks(p("/sub")).head.conflictsWith(backend.locks(p("/sub/a.txt")).head) shouldBe true
    if posix then
      backend.locks(p("/sub/a.txt")) shouldBe Set(LockKey("host", real, "sub/a.txt"))
      backend.locks(p("/a")).head.conflictsWith(backend.locks(p("/ab")).head) shouldBe false
    else
      // Windows aliases (8.3 names, trailing dots) cannot be folded lexically: one lock for the root
      backend.locks(p("/a")) shouldBe Set(LockKey("host", real))
      backend.locks(p("/a")) shouldBe backend.locks(p("/ab"))
  }

  it should "refuse every operation once closed, and close idempotently" in {
    written(backend.write(p("/a.txt"), "x", call()))
    backend.close()
    backend.close()
    def closed[A](r: org.llm4s.types.Result[A]) =
      r.left.value match
        case refused: BackendRefused => refused.kind shouldBe BackendRefused.Kind.Closed
        case other                   => fail(s"expected a refusal, got $other")
    closed(backend.read(p("/a.txt"), 0, 1, call()))
    closed(backend.ls(VPath.root, 10, call()))
    closed(backend.write(p("/b.txt"), "y", call()))
    closed(backend.edit(p("/a.txt"), "x", "y", replaceAll = false, call()))
    closed(backend.delete(p("/a.txt"), call()))
    closed(backend.glob(Glob("*"), VPath.root, 10, call()))
    closed(backend.grep("x", VPath.root, None, 10, call()))
    onDisk("a.txt") shouldBe "x"
    backend = HostFileBackend.open(dir, locks).value
  }

  it should "refuse, in this JVM, a root overlapping an open one, and share the same real root" in {
    Files.createDirectories(dir.resolve("sub"))
    HostFileBackend.open(dir.resolve("sub"), locks).left.value.message should include("overlaps")
    // and one containing an open one: a root whose child is open
    val base  = Files.createTempDirectory("llm4s-overlap")
    val child = HostFileBackend.open(Files.createDirectories(base.resolve("child")), locks).value
    HostFileBackend.open(base, locks).left.value.message should include("overlaps")
    child.close()
    HostFileBackend.open(base, locks).value.close()
    val again = HostFileBackend.open(dir, locks).value // the same real root: shared
    again.lockFileOf(p("/a.txt")).value shouldBe backend.lockFileOf(p("/a.txt")).value
    again.close()
    HostFileBackend.open(dir.resolve("sub"), locks).left.value.message should include("overlaps")
    backend.close()
    val nested = HostFileBackend.open(dir.resolve("sub"), locks).value // released: now it may open
    nested.close()
    backend = HostFileBackend.open(dir, locks).value
  }

  // Replay, through C4's verifiable guard.

  "A replayed edit whose first attempt landed" should "return its first result, not \"`old` was not found\"" in {
    written(backend.write(p("/a.txt"), "x y", call()))
    val same = call().tool.get // the call, run again with a context of its own each time
    written(backend.edit(p("/a.txt"), "x", "z", replaceAll = false, FileContext(same))) shouldBe FileEdited(1)
    written(backend.edit(p("/a.txt"), "x", "z", replaceAll = false, FileContext(same))) shouldBe FileEdited(1)
    onDisk("a.txt") shouldBe "z y"
  }

  "Two mutations of one path in one call" should "each be an effect of its own, and each replay as itself" in {
    val run  = testRunContext()
    val key  = IdempotencyKey(UUID.randomUUID().toString)
    def ctx  = FileContext(ToolContext(run, ToolCallId("c"), key, stateOf()))
    val once = ctx
    written(backend.write(p("/x"), "first", once)) shouldBe FileWritten(created = true, 5)
    written(backend.write(p("/x"), "second", once)) shouldBe FileWritten(created = false, 6)
    onDisk("x") shouldBe "second"
    // the call run again, with a context of its own: both effects replay, in order, and nothing is rewritten
    Files.writeString(dir.resolve("x"), "second")
    val again = ctx
    written(backend.write(p("/x"), "first", again)) shouldBe FileWritten(created = true, 5)
    written(backend.write(p("/x"), "second", again)) shouldBe FileWritten(created = false, 6)
    onDisk("x") shouldBe "second"
  }

  "A completed write replayed after its target became a link" should "return its recorded outcome" in {
    assume(posix)
    val same = call().tool.get
    written(backend.write(p("/a.txt"), "first", FileContext(same))) shouldBe FileWritten(created = true, 5)
    Files.delete(dir.resolve("a.txt"))
    Files.writeString(dir.resolve("b.txt"), "b")
    Files.createSymbolicLink(dir.resolve("a.txt"), dir.resolve("b.txt"))
    written(backend.write(p("/a.txt"), "first", FileContext(same))) shouldBe FileWritten(created = true, 5)
  }

  "A replayed write" should "not write again over a later writer's content" in {
    val same = call().tool.get
    written(backend.write(p("/a.txt"), "first", FileContext(same)))
    Files.writeString(dir.resolve("a.txt"), "someone else")
    written(backend.write(p("/a.txt"), "first", FileContext(same))) shouldBe FileWritten(created = true, 5)
    onDisk("a.txt") shouldBe "someone else"
  }

  it should "refuse a file with other hard links, inside the root or out, and never list one" in {
    assume(posix)
    val outside = Files.createTempDirectory("llm4s-outside")
    Files.writeString(outside.resolve("secret.txt"), "secret")
    Files.createLink(dir.resolve("out.txt"), outside.resolve("secret.txt"))
    Files.writeString(dir.resolve("a.txt"), "a")
    Files.createLink(dir.resolve("b.txt"), dir.resolve("a.txt"))
    backend.read(p("/out.txt"), 0, 10, call()).left.value.message should include("hard links")
    backend.write(p("/out.txt"), "x", call()).left.value.message should include("hard links")
    backend.edit(p("/b.txt"), "a", "x", replaceAll = false, call()).left.value.message should include("hard links")
    backend.delete(p("/a.txt"), call()).left.value.message should include("hard links")
    new String(Files.readAllBytes(outside.resolve("secret.txt")), UTF_8) shouldBe "secret"
    backend.ls(VPath.root, 10, call()).value.items shouldBe empty
    backend.grep("secret|a", VPath.root, None, 10, call()).value.items shouldBe empty
    Files.delete(dir.resolve("out.txt"))
    Files.delete(outside.resolve("secret.txt"))
    Files.delete(outside)
  }

  it should "keep its lock files outside the root" in {
    written(backend.write(p("/a.txt"), "x", call()))
    backend.lockFileOf(p("/a.txt")).value.getParent shouldBe locks.toRealPath()
    Files.list(dir).iterator().asScala.map(_.getFileName.toString).toVector.sorted shouldBe Vector("a.txt")
    backend.close()
    // a lock directory inside the root is refused: no path of the backend may reach a lock file
    HostFileBackend.open(dir, dir.resolve(".locks")).left.value.message should include("inside the root")
    HostFileBackend.open(dir, dir).left.value.message should include("inside the root")
    // one above the root is outside it
    Files.createDirectories(dir.resolve("ws"))
    val below = HostFileBackend.open(dir.resolve("ws"), dir).value
    written(below.write(p("/stripe-0.lock"), "not a lock file of mine", call())).created shouldBe true
    below.close()
    backend = HostFileBackend.open(dir, locks).value
  }

  "HostFileBackend.probeAliasesWith" should "refuse the probe when a lookup fails, rather than count it as no alias" in {
    val probe =
      HostFileBackend.probeAliasesWith(
        dir,
        "ABC123",
        _ => throw new java.io.IOException("lookup failed"),
        (_, _) => false
      )
    probe.left.value.message should include("cannot be probed")
    Files.list(dir).iterator().asScala.map(_.getFileName.toString).filter(_.endsWith(".probe")).toList shouldBe empty
  }

  "HostFileBackend.probeAliasesWith" should "refuse to guess when another entry already has the probe's short-name prefix" in {
    val taken = dir.resolve("ABC123~1.PRO")
    val probe = HostFileBackend.probeAliasesWith(dir, "ABC123", p => p == taken, (_, _) => false)
    probe.left.value.message should include("ABC123")
  }

  it should "report an alias found at any of ~1 to ~4, and leave no probe file" in {
    val probe =
      HostFileBackend.probeAliasesWith(dir, "ABC123", p => p.getFileName.toString == "ABC123~3.PRO", (_, _) => true)
    probe.value should have size 1
    Files.list(dir).iterator().asScala.toVector shouldBe empty
  }

  "HostFileBackend.open" should "refuse a file system whose probe finds aliasing it cannot see" in {
    val aliasing = HostFileBackend.open(dir, locks, probe = _ => Right(Vector("8.3 short names")))
    aliasing.left.value.message should (include("8.3 short names").and(include("StoreFileBackend")))
  }

  it should "refuse a file system that reports no link counts, unless the caller vouches for it (no probe of links)" in {
    val noLinks = HostFileBackend.open(dir, locks, probe = _ => Right(Vector.empty), countsLinks = _ => false)
    noLinks.left.value.message should include("hard-link counts")
    val vouched = HostFileBackend
      .open(dir, locks, probe = _ => Right(Vector.empty), countsLinks = _ => false, allowUncheckedIdentity = true)
    vouched.isRight shouldBe true
    vouched.value.close()
  }

  it should "refuse a root it cannot probe, unless the caller vouches for its file system" in {
    val unprobed = (_: Path) => Left(ValidationError("root", "cannot be probed"))
    HostFileBackend.open(dir, locks, probe = unprobed).left.value.message should include("cannot be probed")
    HostFileBackend.open(dir, locks, probe = unprobed, allowUncheckedIdentity = true).isRight shouldBe true
  }

  it should "probe a local file system and find nothing, leaving no probe file" in {
    assume(posix)
    HostFileBackend.probeAliases(dir).value shouldBe empty
    Files.list(dir).iterator().asScala.toVector shouldBe empty
  }

  "HostFileBackend" should "hide and reserve the file a failed alias check left behind" in {
    val left = s"ABC123-llm4s-probe-${UUID.randomUUID()}.probe"
    Files.writeString(dir.resolve(left), "")
    backend.ls(VPath.root, 10, call()).value.items shouldBe empty
    backend.read(p(s"/$left"), 0, 1, call()).left.value.message should include("temporary files")
  }

  it should "refuse a name ending in a dot or a space, which Windows reads as another" in {
    backend.write(p("/a."), "x", call()).left.value.message should include("ending in a dot or a space")
    backend.write(p("/d /a.txt"), "x", call()).left.value.message should include("ending in a dot or a space")
  }

  it should "replace or delete a file over maxFileBytes, though it cannot read or edit one" in {
    val small = HostFileBackend.open(dir, locks, maxFileBytes = 4).value
    Files.writeString(dir.resolve("big"), "123456789")
    Files.writeString(dir.resolve("big2"), "123456789")
    small.read(p("/big"), 0, 1, call()).left.value.message should include("larger than the limit")
    small.edit(p("/big"), "1", "2", replaceAll = false, call()).left.value.message should include(
      "larger than the limit"
    )
    written(small.write(p("/big"), "ok", call())) shouldBe FileWritten(created = false, 2)
    onDisk("big") shouldBe "ok"
    written(small.delete(p("/big2"), call())) shouldBe FileDeleted(wasDirectory = false)
    Files.exists(dir.resolve("big2")) shouldBe false
  }

  "A mutation" should "wait while another process holds the OS lock of its file's stripe" in {
    written(backend.write(p("/a.txt"), "x", call()))
    val child = holding(backend.lockFileOf(p("/a.txt")).value)
    val edit  = worker().submit(() => backend.edit(p("/a.txt"), "x", "y", replaceAll = false, call()))
    Thread.sleep(500)
    edit.isDone shouldBe false
    onDisk("a.txt") shouldBe "x"
    child.getOutputStream.close() // the other process releases its lock and exits
    edit.get(10, TimeUnit.SECONDS).isRight shouldBe true
    onDisk("a.txt") shouldBe "y"
    child.waitFor(10, TimeUnit.SECONDS) shouldBe true
  }

  it should "keep its root through close until an operation already running has finished" in {
    written(backend.write(p("/a.txt"), "x", call()))
    Files.createDirectories(dir.resolve("sub"))
    val child = holding(backend.lockFileOf(p("/a.txt")).value)
    val edit  = worker().submit(() => backend.edit(p("/a.txt"), "x", "y", replaceAll = false, call()))
    Thread.sleep(300)
    val closing = worker().submit(new java.util.concurrent.Callable[Unit] { def call(): Unit = backend.close() })
    Thread.sleep(300)
    closing.isDone shouldBe false
    // an operation that starts now is refused, and the root is still held against an overlapping one
    backend.read(p("/a.txt"), 0, 1, call()).left.value match
      case refused: BackendRefused => refused.kind shouldBe BackendRefused.Kind.Closed
      case other                   => fail(s"expected a refusal, got $other")
    HostFileBackend.open(dir.resolve("sub"), locks).left.value.message should include("overlaps")
    child.getOutputStream.close()
    edit.get(10, TimeUnit.SECONDS).isRight shouldBe true
    closing.get(10, TimeUnit.SECONDS)
    onDisk("a.txt") shouldBe "y"
    HostFileBackend.open(dir.resolve("sub"), locks).value.close()
    child.waitFor(10, TimeUnit.SECONDS) shouldBe true
    backend = HostFileBackend.open(dir, locks).value
  }

  it should "end as cancelled, not as a lock failure, when interrupted while another process holds the lock" in {
    // POSIX only: on Windows a thread blocked in LockFileEx is not woken by an interrupt
    assume(posix)
    written(backend.write(p("/a.txt"), "x", call()))
    val child = holding(backend.lockFileOf(p("/a.txt")).value)
    val pool  = worker()
    val edit  = pool.submit(() => backend.edit(p("/a.txt"), "x", "y", replaceAll = false, call()))
    Thread.sleep(300)
    pool.shutdownNow() // interrupts the waiting edit
    edit.get(10, TimeUnit.SECONDS).left.value shouldBe a[org.llm4s.error.CancelledError]
    onDisk("a.txt") shouldBe "x"
    child.getOutputStream.close()
    child.waitFor(10, TimeUnit.SECONDS) shouldBe true
  }

  "Concurrent edits of one file from different threads" should "never lose one: each applies or is refused" in {
    written(backend.write(p("/log.txt"), "end", call()))
    val n     = 24
    val pool  = Executors.newFixedThreadPool(n)
    val start = new CountDownLatch(1)
    val done = (1 to n).map { i =>
      pool.submit { () =>
        start.await()
        i -> backend.edit(p("/log.txt"), "end", s"$i\nend", replaceAll = false, call())
      }
    }
    start.countDown()
    val results = done.map(_.get(30, TimeUnit.SECONDS))
    pool.shutdown()
    // a refusal is the compare-and-set seeing another edit land since its plan: never a silent loss
    results
      .collect { case (_, Left(e)) => e }
      .foreach(_ shouldBe a[org.llm4s.agent.graph.GraphError.EffectReplayRefused])
    val applied = results.collect { case (i, Right(_)) => i.toString }.toSet
    applied should not be empty
    onDisk("log.txt").split("\n").toSet shouldBe (applied + "end")
  }

  private def mode(rel: String): String =
    java.nio.file.attribute.PosixFilePermissions.toString(
      Files.getPosixFilePermissions(dir.resolve(rel), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    )

  it should "keep a replaced file's permissions on a write and an edit: 0600 stays private, 0755 executable" in {
    assume(posix)
    for (perms <- Seq("rw-------", "rwxr-xr-x")) {
      val rel = s"keep-$perms.txt"
      Files.writeString(dir.resolve(rel), "one\n")
      Files.setPosixFilePermissions(dir.resolve(rel), java.nio.file.attribute.PosixFilePermissions.fromString(perms))
      written(backend.write(p(s"/$rel"), "two\n", call()))
      mode(rel) shouldBe perms
      written(backend.edit(p(s"/$rel"), "two", "three", replaceAll = false, call()))
      mode(rel) shouldBe perms
      onDisk(rel) shouldBe "three\n"
    }
  }

  it should "give a new file what any file created in its directory gets" in {
    assume(posix)
    Files.createFile(dir.resolve("plain.txt"))
    written(backend.write(p("/new.txt"), "x", call()))
    mode("new.txt") shouldBe mode("plain.txt")
  }

  it should "refuse to replace a file whose setuid bit a replacement would drop, changing nothing" in {
    assume(posix)
    Files.writeString(dir.resolve("tool"), "#!/bin/sh\n")
    new ProcessBuilder("chmod", "4755", dir.resolve("tool").toString).start().waitFor() shouldBe 0
    val refusal = backend.write(p("/tool"), "replaced", call()).left.value
    refusal shouldBe a[BackendRefused]
    refusal.message should include("setuid")
    onDisk("tool") shouldBe "#!/bin/sh\n"
    Files.list(dir).iterator().asScala.map(_.getFileName.toString).filter(_.endsWith(".tmp")).toList shouldBe empty
  }

  it should "refuse an edit whose result would pass the limit before building it" in {
    Files.writeString(dir.resolve("big.txt"), "a" * 100000)
    // 100 000 occurrences each grown by 99 999 characters: about 10^10, which no JVM here could allocate
    val refusal =
      backend.edit(p("/big.txt"), "a", "b" * 100000, replaceAll = true, call()).left.value
    refusal shouldBe a[BackendRefused]
    refusal.message should include("larger than the limit")
    onDisk("big.txt") shouldBe "a" * 100000
  }

  it should "refuse a search over a file it cannot read, rather than skip it, while still skipping binary" in {
    assume(posix)
    Files.writeString(dir.resolve("open.txt"), "needle\n")
    Files.write(dir.resolve("bin.dat"), Array[Byte](0, 1, 2))
    backend.grep("needle", VPath.root, None, 10, call()).value.items.map(_.path) shouldBe Vector(p("/open.txt"))
    Files.writeString(dir.resolve("locked.txt"), "needle\n")
    Files.setPosixFilePermissions(
      dir.resolve("locked.txt"),
      java.nio.file.attribute.PosixFilePermissions.fromString("---------")
    )
    cleanups += (() =>
      Files.setPosixFilePermissions(
        dir.resolve("locked.txt"),
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
      ): Unit
    )
    assume(!Files.isReadable(dir.resolve("locked.txt"))) // a superuser reads it anyway
    val refusal = backend.grep("needle", VPath.root, None, 10, call()).left.value
    refusal shouldBe a[BackendRefused]
    refusal.message should include("permission denied")
  }

}
