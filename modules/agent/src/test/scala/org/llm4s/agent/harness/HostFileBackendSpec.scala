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

  private var dir: Path                = _
  private val locks: Path              = Files.createTempDirectory("llm4s-host-locks")
  private var backend: HostFileBackend = _

  override def beforeEach(): Unit = {
    dir = Files.createTempDirectory("llm4s-host-backend")
    backend = HostFileBackend.open(dir, locks).value
  }

  override def afterEach(): Unit =
    Files.walk(dir).iterator().asScala.toVector.reverse.foreach(p => Files.deleteIfExists(p))

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

  it should "refuse a path named as its temporary files, which no listing would show" in {
    val tmp = s".a.txt.llm4s-${UUID.randomUUID()}.tmp"
    backend.write(p(s"/$tmp"), "x", call()).left.value.message should include("temporary files")
    backend.write(p(s"/$tmp/inner.txt"), "x", call()).left.value.message should include("temporary files")
    Files.exists(dir.resolve(tmp)) shouldBe false
  }

  it should "leave no temporary file behind, and never list one" in {
    written(backend.write(p("/a.txt"), "x", call()))
    Files.list(dir).iterator().asScala.map(_.getFileName.toString).toVector.sorted shouldBe Vector("a.txt")
    Files.writeString(dir.resolve(s".a.txt.llm4s-${UUID.randomUUID()}.tmp"), "torn")
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
    Files.writeString(dir.resolve(s".a.llm4s-${UUID.randomUUID()}.tmp"), "torn")
    val listed = capped.ls(VPath.root, 10, call()).value
    listed.items.map(_.path.value) shouldBe Vector("/b", "/c", "/d")
    listed.truncated shouldBe true
    capped.glob(Glob("*"), VPath.root, 10, call()).value shouldBe Listing(
      Vector(p("/b"), p("/c"), p("/d")),
      truncated = true
    )
    backend.ls(VPath.root, 2, call()).value.items.map(_.path.value) shouldBe Vector("/b", "/c")
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

  it should "lock the physical path, so backends over nested roots order writes to one file" in {
    Files.createDirectories(dir.resolve("sub"))
    val nested = HostFileBackend.open(dir.resolve("sub"), locks).value
    val real   = HostFileBackend.identity(dir.toRealPath().toString.replace('\\', '/'))
    backend.locks(p("/sub/a.txt")) shouldBe Set(LockKey("host", real, "sub/a.txt"))
    backend.locks(p("/sub/a.txt")).head.conflictsWith(nested.locks(p("/a.txt")).head) shouldBe true
    backend.locks(p("/sub")).head.conflictsWith(backend.locks(p("/sub/a.txt")).head) shouldBe true
    backend.locks(p("/a")).head.conflictsWith(backend.locks(p("/ab")).head) shouldBe false
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

  it should "share one lock file per target with every backend on an overlapping root, and keep it out of reach" in {
    Files.createDirectories(dir.resolve("sub"))
    val shared = Files.createTempDirectory("llm4s-locks")
    val outer  = HostFileBackend.open(dir, shared).value
    val nested = HostFileBackend.open(dir.resolve("sub"), shared).value
    outer.lockFileOf(p("/sub/a.txt")).value shouldBe nested.lockFileOf(p("/a.txt")).value
    outer.lockFileOf(p("/sub/a.txt")).value.getParent shouldBe shared.toRealPath()
    written(outer.write(p("/a.txt"), "x", call()))
    Files.list(dir).iterator().asScala.map(_.getFileName.toString).toVector.sorted shouldBe Vector("a.txt", "sub")
    // a lock directory inside the root: its lock files are neither reachable nor listed, the rest is
    val inside = HostFileBackend.open(dir, dir.resolve(".locks")).value
    written(inside.write(p("/b.txt"), "y", call()))
    val stripe = inside.lockFileOf(p("/b.txt")).value.getFileName.toString
    inside.read(p(s"/.locks/$stripe"), 0, 1, call()).left.value.message should include("lock files")
    inside.read(p(s"/.LOCKS/${stripe.toUpperCase}"), 0, 1, call()).left.value.message should include("lock files")
    inside.write(p("/.locks/stripe-7.lock"), "x", call()).left.value.message should include("lock files")
    written(inside.write(p("/.locks/notes.txt"), "mine", call())).created shouldBe true
    inside.ls(p("/.locks"), 100, call()).value.items.map(_.path.value) shouldBe Vector("/.locks/notes.txt")
    // a lock directory above the root reserves nothing in it
    Files.createDirectories(dir.resolve("ws"))
    val below = HostFileBackend.open(dir.resolve("ws"), dir).value
    written(below.write(p("/stripe-0.lock"), "not a lock file of mine", call())).created shouldBe true
    below.read(p("/stripe-0.lock"), 0, 1, call()).value.lines shouldBe Vector("not a lock file of mine")
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
    val lockFile = backend.lockFileOf(p("/a.txt")).value
    val javaBin  = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val child = new ProcessBuilder(
      javaBin,
      "-cp",
      System.getProperty("java.class.path"),
      "org.llm4s.agent.harness.HoldLock",
      lockFile.toString
    )
      .redirectErrorStream(true)
      .start()
    val out = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream))
    out.readLine() shouldBe "locked"
    val pool = Executors.newSingleThreadExecutor()
    val edit = pool.submit(() => backend.edit(p("/a.txt"), "x", "y", replaceAll = false, call()))
    Thread.sleep(500)
    edit.isDone shouldBe false
    onDisk("a.txt") shouldBe "x"
    child.getOutputStream.close() // the other process releases its lock and exits
    edit.get(10, TimeUnit.SECONDS).isRight shouldBe true
    onDisk("a.txt") shouldBe "y"
    child.waitFor(10, TimeUnit.SECONDS) shouldBe true
    pool.shutdown()
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

}
