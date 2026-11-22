package org.llm4s.rag.loader

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.util.Comparator
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * `DirectoryLoader` stays inside its root by default and reads only regular files (#2724). Links are
 * platform-sensitive: creating one needs a privilege on Windows, so the link tests cancel where it is refused.
 */
class DirectoryLoaderConfinementSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var base: Path    = _
  private var root: Path    = _
  private var outside: Path = _

  override def beforeEach(): Unit = {
    base = Files.createTempDirectory("dirloader-confine")
    root = Files.createDirectory(base.resolve("root"))
    outside = Files.createDirectory(base.resolve("outside"))
    Files.writeString(root.resolve("inside.txt"), "inside")
    Files.writeString(outside.resolve("secret.txt"), "secret")
    Files.createDirectory(outside.resolve("dir"))
    Files.writeString(outside.resolve("dir").resolve("deep-secret.txt"), "deep secret")
  }

  override def afterEach(): Unit =
    Files.walk(base).sorted(Comparator.reverseOrder()).iterator().asScala.foreach(p => Try(Files.delete(p)))

  private def link(at: Path, target: Path): Path = {
    val made = Try(Files.createSymbolicLink(at, target))
    assume(made.isSuccess, s"symbolic links are not available here: ${made.failed.toOption.map(_.getMessage)}")
    made.get
  }

  private def contents(loader: DirectoryLoader): Seq[String] =
    loader.load().collect { case LoadResult.Success(doc) => doc.content.trim }.toSeq.sorted

  "DirectoryLoader" should "not follow a file link whose target is outside the root" in {
    link(root.resolve("leak.txt"), outside.resolve("secret.txt"))
    contents(DirectoryLoader(root)) shouldBe Seq("inside")
    DirectoryLoader(root).estimatedCount shouldBe Some(1)
  }

  it should "not descend into a directory link whose target is outside the root" in {
    link(root.resolve("escape"), outside.resolve("dir"))
    contents(DirectoryLoader(root)) shouldBe Seq("inside")
  }

  it should "follow links that stay inside the root" in {
    val sub = Files.createDirectory(root.resolve("sub"))
    Files.writeString(sub.resolve("nested.txt"), "nested")
    link(root.resolve("alias.txt"), root.resolve("inside.txt"))
    link(root.resolve("subalias"), sub)
    // `sub` is listed once, through whichever path reaches it first
    contents(DirectoryLoader(root)) shouldBe Seq("inside", "inside", "nested")
  }

  it should "not let a link past maxDepth hide the directory it points at" in {
    val a = Files.createDirectory(root.resolve("a"))
    val z = Files.createDirectory(root.resolve("z"))
    Files.writeString(z.resolve("doc.txt"), "z doc")
    link(a.resolve("link"), z) // reached first, at depth 2, beyond maxDepth 1
    contents(DirectoryLoader(root).withMaxDepth(1)) shouldBe Seq("inside", "z doc")
  }

  it should "not loop on a link back to the root" in {
    link(root.resolve("loop"), root)
    contents(DirectoryLoader(root)) shouldBe Seq("inside")
  }

  it should "follow outside links when confineToRoot is off" in {
    link(root.resolve("leak.txt"), outside.resolve("secret.txt"))
    link(root.resolve("escape"), outside.resolve("dir"))
    val loader = DirectoryLoader(root).withConfineToRoot(false)
    loader.confineToRoot shouldBe false
    contents(loader) shouldBe Seq("deep secret", "inside", "secret")
  }

  it should "confine a root that is itself reached through a link to the link's target" in {
    val viaLink = link(base.resolve("root-link"), root)
    link(root.resolve("leak.txt"), outside.resolve("secret.txt"))
    contents(DirectoryLoader(viaLink)) shouldBe Seq("inside")
  }

  it should "skip a dangling link" in {
    link(root.resolve("dangling.txt"), base.resolve("missing.txt"))
    contents(DirectoryLoader(root)) shouldBe Seq("inside")
  }

  it should "read only regular files, skipping a FIFO" in {
    val fifo = root.resolve("pipe.txt")
    val made = Try(new ProcessBuilder("mkfifo", fifo.toString).start().waitFor()).toOption.contains(0)
    assume(made && Files.exists(fifo), "mkfifo is not available here")
    // Reading a FIFO with no writer would block forever: the test finishing is the check
    contents(DirectoryLoader(root)) shouldBe Seq("inside")
  }

  it should "default to confining traversal" in {
    DirectoryLoader(root).confineToRoot shouldBe true
  }
}
