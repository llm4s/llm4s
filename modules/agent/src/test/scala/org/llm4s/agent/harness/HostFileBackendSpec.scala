package org.llm4s.agent.harness

import org.llm4s.agent.graph.GraphTestSupport.testRunContext
import org.llm4s.agent.graph.HarnessTestSupport.stateOf
import org.llm4s.agent.graph.{ IdempotencyKey, ReplaySupport, StateUpdate, ToolCallId }
import org.llm4s.agent.graph.tool.{ Glob, ToolContext }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{ Files, Path }
import java.util.UUID
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import scala.jdk.CollectionConverters._

class HostFileBackendSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with org.scalatest.OptionValues
    with BeforeAndAfterEach {

  private var dir: Path                = _
  private var locks: Path              = _
  private var backend: HostFileBackend = _

  /** Links and POSIX permissions need a POSIX file system: Windows reports no link count. */
  private val posix = java.nio.file.FileSystems.getDefault.supportedFileAttributeViews.contains("unix")

  /**
   * Whether this machine's temporary volume makes 8.3 short names (a Windows runner may), which `open`
   * refuses whatever is vouched for. The cases then open with the probe stubbed, so everything else still
   * runs on that platform; the refusal itself is the injected-probe cases'.
   */
  private lazy val shortNames: Boolean = {
    val probed = Files.createTempDirectory("llm4s-host-probe")
    val found  = HostFileBackend.probeAliases(probed).exists(_.nonEmpty)
    Files.deleteIfExists(probed)
    found
  }

  private def defaultProbe: Path => Result[Vector[String]] =
    if (shortNames) _ => Right(Vector.empty) else HostFileBackend.probeAliases

  override def beforeEach(): Unit = {
    dir = Files.createTempDirectory("llm4s-host-backend")
    locks = Files.createTempDirectory("llm4s-host-locks")
    backend = null
    reopen().left.foreach(e => fail(e.message))
  }

  /** Children and threads a case started, stopped after it whether it passed or not. */
  private val cleanups = scala.collection.mutable.ListBuffer.empty[() => Unit]

  override def afterEach(): Unit =
    Option(backend).foreach(_.close())
    cleanups.foreach(c => scala.util.Try(c()))
    cleanups.clear()
    Seq(dir, locks).foreach(d =>
      Files.walk(d).iterator().asScala.toVector.reverse.foreach(q => scala.util.Try(Files.deleteIfExists(q)))
    )

  /**
   * Closes the case's backend and opens `dir` again as given, which becomes the case's backend when it
   * opens. Windows reports no hard-link counts, so there the case vouches for its temporary root unless it
   * says otherwise.
   */
  private def reopen(
    maxFileBytes: Long = HostFileBackend.DefaultMaxFileBytes,
    maxScanEntries: Int = HostFileBackend.DefaultMaxScanEntries,
    allowUncheckedIdentity: Boolean = !posix,
    probe: Path => Result[Vector[String]] = defaultProbe,
    countsLinks: Path => Boolean = HostFileBackend.reportsLinkCounts,
    fileSystemId: Path => String = HostFileBackend.fileSystemIdOf,
    mountsBeneath: Path => Result[Vector[Path]] = HostFileBackend.mountPointsBeneath
  ): Result[HostFileBackend] = {
    Option(backend).foreach(_.close())
    val opened = HostFileBackend.openWith(
      dir,
      locks,
      maxFileBytes,
      maxScanEntries,
      allowUncheckedIdentity,
      probe,
      countsLinks,
      fileSystemId,
      mountsBeneath
    )
    opened.foreach(b => backend = b)
    opened
  }

  /** Opens `root` over the case's lock directory, as another backend of this JVM would. */
  private def another(root: Path): Result[HostFileBackend] =
    HostFileBackend.openWith(root, locks, allowUncheckedIdentity = !posix, probe = defaultProbe)

  /** A child JVM opening `root` over the case's lock directory; destroyed after the case. */
  private def child(root: Path): (Process, String) = {
    val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val process = new ProcessBuilder(
      javaBin,
      "-cp",
      System.getProperty("java.class.path"),
      "org.llm4s.agent.harness.HoldRoot",
      root.toString,
      locks.toString,
      if (shortNames) "unprobed" else "probed"
    )
      .redirectErrorStream(true)
      .start()
    cleanups += (() => process.destroyForcibly(): Unit)
    val first = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream)).readLine()
    (process, first)
  }

  /** Lets a child holding a root close its backend and exit. */
  private def release(process: Process): Unit = {
    process.getOutputStream.close()
    process.waitFor(30, TimeUnit.SECONDS) shouldBe true
  }

  /** A single daemon thread, shut down after the case. */
  private def worker(): java.util.concurrent.ExecutorService = {
    val pool = Executors.newSingleThreadExecutor { r =>
      val t = new Thread(r); t.setDaemon(true); t
    }
    cleanups += (() => pool.shutdownNow(): Unit)
    pool
  }

  private def p(s: String): VPath = VPath(s).value

  /** A fresh tool call: its own key, so its own effects. */
  private def call(): FileContext =
    FileContext(ToolContext(testRunContext(), ToolCallId("c"), IdempotencyKey(UUID.randomUUID().toString), stateOf()))

  private def onDisk(rel: String): String = new String(Files.readAllBytes(dir.resolve(rel)), UTF_8)

  private def written[A](r: Result[(A, StateUpdate)]): A = {
    val (a, update) = r.value
    update.isEmpty shouldBe true
    a
  }

  private def mode(rel: String): String =
    java.nio.file.attribute.PosixFilePermissions.toString(
      Files.getPosixFilePermissions(dir.resolve(rel), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    )

  private def inUse[A](r: Result[A]): HostRootInUse.Conflict =
    r.left.value match {
      case refused: HostRootInUse => refused.conflict
      case other                  => fail(s"expected HostRootInUse, got $other")
    }

  private def closedRefusal[A](r: Result[A]) =
    r.left.value match {
      case refused: BackendRefused => refused.message should include("closed")
      case other                   => fail(s"expected a refusal, got $other")
    }

  "HostFileBackend.open" should "resolve the root to its real path and refuse a missing or non-directory root" in {
    backend.root shouldBe dir.toRealPath()
    backend.replaySupport shouldBe ReplaySupport.Verifiable
    HostFileBackend.open(dir.resolve("missing"), locks).left.value shouldBe a[ValidationError]
    Files.writeString(dir.resolve("f"), "x")
    HostFileBackend.open(dir.resolve("f"), locks).left.value shouldBe a[ValidationError]
    reopen(maxFileBytes = 0).left.value shouldBe a[ValidationError]
    reopen(maxScanEntries = 0).left.value shouldBe a[ValidationError]
  }

  // ---- the single-writer lock

  "The root lock" should "refuse a second open of the same root, in this JVM, until the first is closed" in {
    inUse(another(dir)) shouldBe HostRootInUse.Conflict.SameRoot
    backend.close()
    val again = another(dir).value
    inUse(reopen()) shouldBe HostRootInUse.Conflict.SameRoot
    again.close()
    reopen().isRight shouldBe true
  }

  it should "refuse a root inside an open one, and one containing an open one" in {
    val sub = Files.createDirectories(dir.resolve("sub/deeper"))
    inUse(another(sub)) shouldBe HostRootInUse.Conflict.WithinOpenRoot
    another(sub).left.value.message should include(dir.toRealPath().toString)
    backend.close()
    val inner = another(sub).value
    inUse(reopen()) shouldBe HostRootInUse.Conflict.ContainsOpenRoot
    inner.close()
    reopen().isRight shouldBe true
  }

  it should "let siblings open together, and keep a shared ancestor locked while either is open" in {
    backend.close()
    val a = another(Files.createDirectories(dir.resolve("a"))).value
    val b = another(Files.createDirectories(dir.resolve("b"))).value
    inUse(reopen()) shouldBe HostRootInUse.Conflict.ContainsOpenRoot
    a.close()
    inUse(reopen()) shouldBe HostRootInUse.Conflict.ContainsOpenRoot
    b.close()
    reopen().isRight shouldBe true
  }

  it should "know a root by what it is, not the path it was given: a symbolic link to it is the same root" in {
    assume(posix)
    val link = Files.createTempDirectory("llm4s-host-link").resolve("to-root")
    cleanups += (() => { Files.deleteIfExists(link); Files.deleteIfExists(link.getParent): Unit })
    Files.createSymbolicLink(link, dir)
    inUse(another(link)) shouldBe HostRootInUse.Conflict.SameRoot
    Files.createDirectories(dir.resolve("sub"))
    inUse(another(link.resolve("sub"))) shouldBe HostRootInUse.Conflict.WithinOpenRoot
  }

  it should "refuse a lock directory inside the root, and allow a root inside the lock directory" in {
    backend.close()
    HostFileBackend.open(dir, dir.resolve(".locks")).left.value.message should include("inside the root")
    HostFileBackend.open(dir, dir).left.value.message should include("inside the root")
    val ws    = Files.createDirectories(dir.resolve("ws"))
    val below = HostFileBackend.open(ws, dir, allowUncheckedIdentity = !posix).value
    // the lock files are in the lock directory itself, not in the root beneath it
    below.ls(VPath.root, 10, call()).value.items shouldBe empty
    below.close()
  }

  it should "never write its lock files into the root" in {
    written(backend.write(p("/a.txt"), "x", call()))
    Files.list(dir).iterator().asScala.map(_.getFileName.toString).toVector shouldBe Vector("a.txt")
    Files.list(locks).iterator().asScala.map(_.getFileName.toString).filter(_.endsWith(".lock")) should not be empty
  }

  it should "refuse, in another process, an open of the same, an enclosing or an inner root" in {
    val sub              = Files.createDirectories(dir.resolve("sub"))
    val (same, sameSays) = child(dir)
    sameSays should startWith("refused")
    sameSays should include("has it open")
    val (inner, innerSays) = child(sub)
    innerSays should startWith("refused")
    innerSays should include("inside")
    backend.close()
    val (outer, outerSays) = child(dir)
    outerSays shouldBe "open"
    release(outer)
    Seq(same, inner).foreach(_.waitFor(30, TimeUnit.SECONDS) shouldBe true)
  }

  it should "refuse here a root another process holds, or one inside or around it, until that process lets go" in {
    backend.close()
    val sub                  = Files.createDirectories(dir.resolve("sub"))
    val (holder, holderSays) = child(sub)
    holderSays shouldBe "open"
    inUse(another(sub)) shouldBe HostRootInUse.Conflict.SameRoot
    inUse(another(dir)) shouldBe HostRootInUse.Conflict.ContainsOpenRoot
    Files.createDirectories(sub.resolve("deeper"))
    inUse(another(sub.resolve("deeper"))) shouldBe HostRootInUse.Conflict.WithinOpenRoot
    release(holder)
    reopen().isRight shouldBe true
  }

  it should "keep holding its locks when another backend of this JVM closes, or an open of this JVM is refused" in {
    // on POSIX, closing any channel of a lock file drops every lock the process holds on it; the backend
    // shares one channel per file, so neither a sibling's close nor a refused open releases another's lock
    backend.close()
    val a = another(Files.createDirectories(dir.resolve("a"))).value
    val b = another(Files.createDirectories(dir.resolve("b"))).value
    a.close()
    inUse(another(dir.resolve("b"))) shouldBe HostRootInUse.Conflict.SameRoot
    val (around, aroundSays) = child(dir)
    aroundSays should startWith("refused")
    val (same, sameSays) = child(dir.resolve("b"))
    sameSays should startWith("refused")
    b.close()
    Seq(around, same).foreach(_.waitFor(30, TimeUnit.SECONDS) shouldBe true)
    reopen().isRight shouldBe true
  }

  it should "release its lock if a check after it refuses the open" in {
    backend.close()
    reopen(probe = _ => Right(Vector("8.3 short names"))).isLeft shouldBe true
    reopen().isRight shouldBe true
  }

  // ---- close

  "close" should "refuse every operation that starts after it, and be idempotent" in {
    written(backend.write(p("/a.txt"), "x", call()))
    backend.close()
    backend.close()
    closedRefusal(backend.read(p("/a.txt"), 0, 1, call()))
    closedRefusal(backend.ls(VPath.root, 10, call()))
    closedRefusal(backend.write(p("/b.txt"), "y", call()))
    closedRefusal(backend.edit(p("/a.txt"), "x", "y", replaceAll = false, call()))
    closedRefusal(backend.delete(p("/a.txt"), call()))
    closedRefusal(backend.glob(Glob("*"), VPath.root, 10, call()))
    closedRefusal(backend.grep("x", VPath.root, None, 10, call()))
    onDisk("a.txt") shouldBe "x"
  }

  it should "return at once, keeping the root locked until an operation already running has finished" in {
    written(backend.write(p("/a.txt"), "x\n", call()))
    val entered = new CountDownLatch(1)
    val proceed = new CountDownLatch(1)
    val running = backend
    val search = worker().submit(() =>
      running.search(
        VPath.root,
        java.util.regex.Pattern.compile("x"),
        _ => { entered.countDown(); proceed.await(30, TimeUnit.SECONDS); true },
        10,
        call()
      )
    )
    entered.await(30, TimeUnit.SECONDS) shouldBe true
    running.close() // returns: it never waits
    closedRefusal(running.read(p("/a.txt"), 0, 1, call()))
    inUse(another(dir)) shouldBe HostRootInUse.Conflict.SameRoot
    proceed.countDown()
    search.get(30, TimeUnit.SECONDS).value.items.map(_.path) shouldBe Vector(p("/a.txt"))
    another(dir).value.close()
  }

  it should "keep a mutation already running whole: it lands, and the root is free only after" in {
    val entered         = new CountDownLatch(1)
    val proceed         = new CountDownLatch(1)
    @volatile var armed = false
    // the hold is inside the write, at its first mount check, once it is admitted
    val running = reopen(mountsBeneath = _ => {
      if (armed) { armed = false; entered.countDown(); proceed.await(30, TimeUnit.SECONDS) }
      Right(Vector.empty)
    }).value
    armed = true
    val write = worker().submit(() => running.write(p("/a.txt"), "y", call()))
    entered.await(30, TimeUnit.SECONDS) shouldBe true
    running.close()
    inUse(another(dir)) shouldBe HostRootInUse.Conflict.SameRoot
    proceed.countDown()
    written(write.get(30, TimeUnit.SECONDS)).created shouldBe true
    onDisk("a.txt") shouldBe "y"
    another(dir).value.close()
  }

  it should "not deadlock when called from inside one of the backend's own operations" in {
    Files.writeString(dir.resolve("a.txt"), "x\n")
    val running = backend
    val found   = running.find(VPath.root, _ => { running.close(); true }, 10, call())
    found.value.items shouldBe Vector(p("/a.txt"))
    closedRefusal(running.read(p("/a.txt"), 0, 1, call()))
    another(dir).value.close()
  }

  "HostFileBackend" should "lock every mutation of one backend under one key, below the file backends' lock root" in {
    val keys = Seq("/a", "/A", "/b/c").map(s => backend.locks(p(s)))
    keys.distinct should have size 1
    FileBackend.withinLockRoot(keys.head.head) shouldBe true
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
    val small = reopen(maxFileBytes = 4, maxScanEntries = 3).value
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
    reopen().value.glob(Glob("*"), VPath.root, 100, call()).value.truncated shouldBe false
  }

  it should "list the first names in order however large the directory, unlisted entries not counting" in {
    assume(posix)
    val capped = reopen(maxScanEntries = 3).value
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
    reopen(maxFileBytes = Long.MaxValue).left.value.message should include("at most")
  }

  it should "bound what it reads, not a size checked before reading" in {
    val small = reopen(maxFileBytes = 4).value
    Files.writeString(dir.resolve("exact"), "1234")
    Files.writeString(dir.resolve("over"), "12345")
    small.read(p("/exact"), 0, 1, call()).value.lines shouldBe Vector("1234")
    small.read(p("/over"), 0, 1, call()).left.value.message should include("larger than the limit")
    small.grep("1", VPath.root, None, 10, call()).value.items.map(_.path) shouldBe Vector(p("/exact"))
  }

  it should "take Int.MaxValue as a limit and as its scan bound, without overflow" in {
    val unbounded = reopen(maxScanEntries = Int.MaxValue).value
    Seq("a/x", "b").foreach(rel => written(unbounded.write(p(s"/$rel"), "one\ntwo", call())))
    unbounded.glob(Glob("**"), VPath.root, Int.MaxValue, call()).value shouldBe Listing(
      Vector(p("/a/x"), p("/b")),
      false
    )
    unbounded.ls(VPath.root, Int.MaxValue, call()).value.items.map(_.path) shouldBe Vector(p("/a"), p("/b"))
    unbounded.read(p("/b"), 1, Int.MaxValue, call()).value shouldBe FileLines(Vector("two"), 1, 2)
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

  it should "list and walk only regular files and directories, a FIFO not counting" in {
    assume(posix)
    val fifo = dir.resolve("a-fifo")
    assume(new ProcessBuilder("mkfifo", fifo.toString).start().waitFor() == 0)
    Files.writeString(dir.resolve("b.txt"), "b")
    val capped = reopen(maxScanEntries = 1).value
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
    val aliasing = reopen(probe = _ => Right(Vector("8.3 short names")))
    aliasing.left.value.message should (include("8.3 short names").and(include("StoreFileBackend")))
  }

  it should "refuse a file system that reports no link counts, unless the caller vouches for it (no probe of links)" in {
    val noLinks = reopen(probe = _ => Right(Vector.empty), countsLinks = _ => false, allowUncheckedIdentity = false)
    noLinks.left.value.message should include("hard-link counts")
    val vouched = reopen(probe = _ => Right(Vector.empty), countsLinks = _ => false, allowUncheckedIdentity = true)
    vouched.isRight shouldBe true
    vouched.value.close()
  }

  it should "refuse a root it cannot probe, unless the caller vouches for its file system" in {
    val unprobed = (_: Path) => Left(ValidationError("root", "cannot be probed"))
    reopen(probe = unprobed, countsLinks = _ => true, allowUncheckedIdentity = false).left.value.message should include(
      "cannot be probed"
    )
    reopen(probe = unprobed, allowUncheckedIdentity = true).isRight shouldBe true
  }

  it should "probe a local file system and find nothing, leaving no probe file" in {
    assume(!shortNames)
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
    val small = reopen(maxFileBytes = 4).value
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

  it should "refuse a mutation the host file system denies, for the model to see, never as a failure" in {
    assume(posix)
    val locked = Files.createDirectories(dir.resolve("locked"))
    Files.writeString(locked.resolve("a.txt"), "x")
    Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"))
    cleanups += (() =>
      Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")): Unit
    )
    assume(!Files.isWritable(locked)) // a superuser writes it anyway
    def deniedRefusal[A](r: Result[A]) =
      r.left.value match {
        case refused: BackendRefused => refused.message should include("permission denied")
        case other                   => fail(s"expected a refusal, got $other")
      }
    deniedRefusal(backend.write(p("/locked/new.txt"), "y", call()))
    deniedRefusal(backend.write(p("/locked/deeper/new.txt"), "y", call()))
    deniedRefusal(backend.edit(p("/locked/a.txt"), "x", "y", replaceAll = false, call()))
    deniedRefusal(backend.delete(p("/locked/a.txt"), call()))
    onDisk("locked/a.txt") shouldBe "x"
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

  "A file system mounted inside the root" should "never be entered: refused by path, and left out of listings and walks" in {
    Files.createDirectories(dir.resolve("mnt"))
    Files.writeString(dir.resolve("mnt/f.txt"), "needle\n")
    Files.writeString(dir.resolve("top.txt"), "needle\n")
    backend.close()
    val real = dir.toRealPath()
    // the injected identity puts `mnt` and everything under it on another file system
    reopen(fileSystemId = q => if q.startsWith(real.resolve("mnt")) then "other" else "root").value
    backend.read(p("/mnt/f.txt"), 0, 1, call()).left.value.message should include("mounted inside the root")
    backend.write(p("/mnt/g.txt"), "x", call()).left.value.message should include("mounted inside the root")
    backend.ls(p("/mnt"), 10, call()).left.value.message should include("mounted inside the root")
    backend.ls(VPath.root, 10, call()).value.items.map(_.path) shouldBe Vector(p("/top.txt"))
    backend.grep("needle", VPath.root, None, 10, call()).value.items.map(_.path) shouldBe Vector(p("/top.txt"))
    backend.glob(Glob("**"), VPath.root, 10, call()).value.items shouldBe Vector(p("/top.txt"))
    Files.exists(dir.resolve("mnt/g.txt")) shouldBe false
  }

  it should "be found by the mount table too, as a bind mount of the root's own file system has its device" in {
    val root = Path.of("/w/root")
    val lines = Iterator(
      "22 1 8:1 / / rw - ext4 /dev/sda1 rw",
      "40 22 8:1 /w/root /w/root rw - ext4 /dev/sda1 rw",
      "41 40 8:1 /data /w/root/sub\\040dir rw - ext4 /dev/sda1 rw",
      "42 22 8:1 /data /w/rootless rw - ext4 /dev/sda1 rw"
    )
    HostFileBackend.mountPointsIn(lines, root) shouldBe Vector(Path.of("/w/root/sub dir"))
  }

  it should "refuse a root with a mount beneath it at open, and a mutation once one appears after open" in {
    backend.close()
    val sub = dir.toRealPath().resolve("sub")
    reopen(mountsBeneath = _ => Right(Vector(sub))).left.value.message should include(
      "mounted beneath it"
    )
    @volatile var mounted = Vector.empty[Path]
    reopen(mountsBeneath = _ => Right(mounted)).value
    written(backend.write(p("/a.txt"), "x", call()))
    mounted = Vector(sub)
    backend.write(p("/a.txt"), "y", call()).left.value.message should include("mounted beneath it")
    onDisk("a.txt") shouldBe "x"
  }

  it should "refuse a root whose mount table cannot be read, unless identity checks are waived" in {
    backend.close()
    val unreadable: Path => org.llm4s.types.Result[Vector[Path]] =
      _ => Left(ValidationError("root", "the mount table cannot be read"))
    reopen(
      mountsBeneath = unreadable,
      countsLinks = _ => true,
      allowUncheckedIdentity = false
    ).left.value.message should include("mount table")
    reopen(mountsBeneath = unreadable, allowUncheckedIdentity = true).value
    written(backend.write(p("/a.txt"), "x", call()))
    // a mount found is never waived
    backend.close()
    val found: Path => org.llm4s.types.Result[Vector[Path]] = r => Right(Vector(r.resolve("m")))
    reopen(mountsBeneath = found, allowUncheckedIdentity = true).left.value.message should
      include("mounted beneath it")
    reopen().value
  }

  "A replaced file's DOS flags" should "be kept beside its POSIX permissions, where the file system has both" in {
    val f = dir.resolve("hidden.txt")
    Files.writeString(f, "x")
    val dos = Option(Files.getFileAttributeView(f, classOf[java.nio.file.attribute.DosFileAttributeView]))
    assume(dos.isDefined && scala.util.Try(dos.get.setHidden(true)).isSuccess)
    written(backend.write(p("/hidden.txt"), "y", call()))
    Files.readAttributes(f, classOf[java.nio.file.attribute.DosFileAttributes]).isHidden shouldBe true
  }

  "A root replaced after open" should "be refused, by a mount over it or a directory moved into its place" in {
    assume(posix)
    written(backend.write(p("/a.txt"), "x", call()))
    // a directory moved into its place: same path, another file key
    val aside = Files.createTempDirectory("llm4s-host-aside").resolve("old")
    cleanups += (() =>
      Files.walk(aside.getParent).iterator().asScala.toVector.reverse.foreach(q => Files.deleteIfExists(q)): Unit
    )
    Files.move(dir, aside)
    Files.createDirectories(dir)
    backend.write(p("/b.txt"), "y", call()).left.value.message should include("was replaced")
    backend.read(p("/a.txt"), 0, 1, call()).left.value.message should include("was replaced")
    Files.exists(dir.resolve("b.txt")) shouldBe false
    backend.close()
    Files.delete(dir)
    Files.move(aside, dir)
    // a mount over it: the same directory, now reported on another file system
    @volatile var mounted = false
    reopen(fileSystemId = _ => if (mounted) "over" else "root").value
    written(backend.write(p("/a.txt"), "z", call()))
    mounted = true
    backend.write(p("/c.txt"), "w", call()).left.value.message should include("was replaced")
  }

  "A mount found when a write's effect runs" should "refuse it before any parent directory is created" in {
    backend.close()
    val calls = new java.util.concurrent.atomic.AtomicInteger()
    // the first look is open's: nothing; every later one, the effect's: a mount has appeared
    reopen(
      mountsBeneath = r => Right(if (calls.getAndIncrement() == 0) Vector.empty else Vector(r.resolve("m")))
    ).value
    backend.write(p("/new/deep/f.txt"), "x", call()).left.value.message should include("mounted beneath it")
    Files.exists(dir.resolve("new")) shouldBe false
  }

  "A mount appearing beneath the root after open" should "refuse reads, listings and walks as well as mutations" in {
    Files.writeString(dir.resolve("a.txt"), "x\n")
    backend.close()
    @volatile var mounted = false
    reopen(mountsBeneath = r => Right(if (mounted) Vector(r.resolve("m")) else Vector.empty)).value
    backend.read(p("/a.txt"), 0, 1, call()).value.lines shouldBe Vector("x")
    mounted = true
    backend.read(p("/a.txt"), 0, 1, call()).left.value.message should include("mounted beneath it")
    backend.ls(VPath.root, 10, call()).left.value.message should include("mounted beneath it")
    backend.glob(Glob("**"), VPath.root, 10, call()).left.value.message should include("mounted beneath it")
    backend.grep("x", VPath.root, None, 10, call()).left.value.message should include("mounted beneath it")
  }
}
