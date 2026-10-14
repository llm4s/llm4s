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
  private var backend: HostFileBackend = _

  override def beforeEach(): Unit = {
    dir = Files.createTempDirectory("llm4s-host-backend")
    backend = HostFileBackend.open(dir).value
  }

  override def afterEach(): Unit =
    Files.walk(dir).iterator().asScala.toVector.reverse.foreach(p => Files.deleteIfExists(p))

  private def p(s: String): VPath = VPath(s).value

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
    HostFileBackend.open(dir.resolve("missing")).left.value shouldBe a[ValidationError]
    Files.writeString(dir.resolve("f"), "x")
    HostFileBackend.open(dir.resolve("f")).left.value shouldBe a[ValidationError]
    HostFileBackend.open(dir, maxFileBytes = 0).left.value shouldBe a[ValidationError]
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

  it should "leave no temporary file behind, and never list one" in {
    written(backend.write(p("/a.txt"), "x", call()))
    Files.list(dir).iterator().asScala.map(_.getFileName.toString).toVector shouldBe Vector("a.txt")
    Files.writeString(dir.resolve(s".a.txt.llm4s-${UUID.randomUUID()}.tmp"), "torn")
    backend.ls(VPath.root, 10, call()).value.items.map(_.path) shouldBe Vector(p("/a.txt"))
    backend.glob(Glob("**"), VPath.root, 10, call()).value.items shouldBe Vector(p("/a.txt"))
  }

  it should "refuse every symbolic link, inside the root or out, at any component" in {
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

  it should "refuse binary content and a file that is not UTF-8, and skip it in grep" in {
    backend.write(p("/bin"), "a\u0000b", call()).left.value.message should include("binary")
    Files.write(dir.resolve("latin1.txt"), Array[Byte](0x63, 0xe9.toByte, 0x0a))
    backend.read(p("/latin1.txt"), 0, 10, call()).left.value.message should include("not UTF-8")
    backend.edit(p("/latin1.txt"), "c", "d", replaceAll = false, call()).left.value.message should include("not UTF-8")
    backend.grep("c", VPath.root, None, 10, call()).value.items shouldBe empty
  }

  it should "bound files by maxFileBytes and walks by maxScanEntries" in {
    val small = HostFileBackend.open(dir, maxFileBytes = 4, maxScanEntries = 3).value
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
    val nested = HostFileBackend.open(dir.resolve("sub")).value
    val real   = dir.toRealPath().toString
    backend.locks(p("/sub/a.txt")) shouldBe Set(LockKey("host", real, "sub/a.txt"))
    backend.locks(p("/sub/a.txt")).head.conflictsWith(nested.locks(p("/a.txt")).head) shouldBe true
    backend.locks(p("/sub")).head.conflictsWith(backend.locks(p("/sub/a.txt")).head) shouldBe true
    backend.locks(p("/a")).head.conflictsWith(backend.locks(p("/ab")).head) shouldBe false
  }

  // Replay, through C4's verifiable guard.

  "A replayed edit whose first attempt landed" should "return its first result, not \"`old` was not found\"" in {
    written(backend.write(p("/a.txt"), "x y", call()))
    val same = call()
    written(backend.edit(p("/a.txt"), "x", "z", replaceAll = false, same)) shouldBe FileEdited(1)
    written(backend.edit(p("/a.txt"), "x", "z", replaceAll = false, same)) shouldBe FileEdited(1)
    onDisk("a.txt") shouldBe "z y"
  }

  "A replayed write" should "not write again over a later writer's content" in {
    val same = call()
    written(backend.write(p("/a.txt"), "first", same))
    Files.writeString(dir.resolve("a.txt"), "someone else")
    written(backend.write(p("/a.txt"), "first", same)) shouldBe FileWritten(created = true, 5)
    onDisk("a.txt") shouldBe "someone else"
  }

  "Concurrent edits of one file from different threads" should "never lose one" in {
    written(backend.write(p("/log.txt"), "end", call()))
    val n     = 24
    val pool  = Executors.newFixedThreadPool(n)
    val start = new CountDownLatch(1)
    val done = (1 to n).map { i =>
      pool.submit { () =>
        start.await()
        backend.edit(p("/log.txt"), "end", s"$i\nend", replaceAll = false, call())
      }
    }
    start.countDown()
    done.foreach(_.get(30, TimeUnit.SECONDS).isRight shouldBe true)
    pool.shutdown()
    onDisk("log.txt").split("\n").toSet shouldBe ((1 to n).map(_.toString).toSet + "end")
  }
}
