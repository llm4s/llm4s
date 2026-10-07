package org.llm4s.toolapi.builtin

import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin.filesystem._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import scala.util.{ Failure, Success, Try }

/**
 * The file tools judge a path by where it really is: components, not characters, and the real location after
 * every symbolic link is resolved (issue #1408, findings F1 and F2).
 *
 * Every test works in a fresh temporary directory of dummy files. Tests that need a symbolic link are cancelled
 * where the platform does not allow creating one.
 */
class PathContainmentSpec extends AnyFlatSpec with Matchers {

  private def newRoot(): Path = Files.createTempDirectory("containment").toRealPath()

  private def file(path: Path, text: String): Path = {
    Files.createDirectories(path.getParent)
    Files.writeString(path, text)
    path
  }

  private def link(linkPath: Path, target: Path): Unit = {
    Files.createDirectories(linkPath.getParent)
    Try(Files.createSymbolicLink(linkPath, target)) match {
      case Success(_) => ()
      case Failure(e) => cancel(s"symbolic links cannot be created here: ${e.getClass.getSimpleName}")
    }
  }

  private def read(config: FileConfig, path: Path): Either[String, ReadFileResult] =
    ReadFileTool.createSafe(config).fold(e => fail(e.formatted), identity).handler(params(path))

  private def list(config: FileConfig, path: Path): Either[String, ListDirectoryResult] =
    ListDirectoryTool.createSafe(config).fold(e => fail(e.formatted), identity).handler(params(path))

  private def info(config: FileConfig, path: Path): Either[String, FileInfoResult] =
    FileInfoTool.createSafe(config).fold(e => fail(e.formatted), identity).handler(params(path))

  private def write(config: WriteConfig, path: Path, text: String): Either[String, WriteFileResult] =
    WriteFileTool
      .createSafe(config)
      .fold(e => fail(e.formatted), identity)
      .handler(SafeParameterExtractor(ujson.Obj("path" -> path.toString, "content" -> text)))

  private def params(path: Path): SafeParameterExtractor =
    SafeParameterExtractor(ujson.Obj("path" -> path.toString))

  private def allowing(root: Path, followSymlinks: Boolean = false): FileConfig =
    FileConfig(allowedPaths = Some(Seq(root.toString)), blockedPaths = Seq.empty, followSymlinks = followSymlinks)

  private def denied[A](result: Either[String, A]): Unit =
    result.left.toOption.getOrElse(fail(s"expected a refusal, got $result")) should include("Access denied")

  // ---- F1: whole components, not characters

  "The file tools" should "still reach a file inside the allowed directory" in {
    val t = newRoot()
    file(t.resolve("data/ok.txt"), "inside")

    read(allowing(t.resolve("data")), t.resolve("data/ok.txt")).map(_.content) shouldBe Right("inside")
  }

  it should "refuse a sibling directory that only shares the prefix of an allowed one, in every tool" in {
    val t = newRoot()
    file(t.resolve("data/ok.txt"), "inside")
    file(t.resolve("data-secret/x.txt"), "SECRET")
    val config = allowing(t.resolve("data"))

    denied(read(config, t.resolve("data-secret/x.txt")))
    denied(list(config, t.resolve("data-secret")))
    denied(info(config, t.resolve("data-secret/x.txt")))
    config.isPathAllowed(t.resolve("data-secret/x.txt")) shouldBe false
    config.isPathAllowed(t.resolve("data/ok.txt")) shouldBe true
  }

  it should "refuse a sibling that is reached by a .. segment from inside the allowed directory" in {
    val t = newRoot()
    file(t.resolve("data-secret/x.txt"), "SECRET")

    denied(read(allowing(t.resolve("data")), t.resolve("data/../data-secret/x.txt")))
  }

  it should "refuse to write into a sibling that only shares the prefix, and not create the file" in {
    val t = newRoot()
    Files.createDirectories(t.resolve("data"))

    denied(write(WriteConfig(allowedPaths = Seq(t.resolve("data").toString)), t.resolve("data-evil/pwn.txt"), "x"))
    Files.exists(t.resolve("data-evil/pwn.txt")) shouldBe false
  }

  it should "match a blocked entry by component too, so blocking /a does not block /ab" in {
    val t = newRoot()
    file(t.resolve("a/x.txt"), "blocked")
    file(t.resolve("ab/x.txt"), "fine")
    val config = FileConfig(allowedPaths = None, blockedPaths = Seq(t.resolve("a").toString))

    denied(read(config, t.resolve("a/x.txt")))
    read(config, t.resolve("ab/x.txt")).map(_.content) shouldBe Right("fine")
  }

  // ---- F2: symbolic links

  it should "refuse a directory link that leads out of the allowed directory, in every tool, links not followed" in {
    val t = newRoot()
    file(t.resolve("outside/secret.txt"), "SECRET")
    Files.createDirectories(t.resolve("data"))
    link(t.resolve("data/dlink"), t.resolve("outside"))
    val config = allowing(t.resolve("data"))

    denied(read(config, t.resolve("data/dlink/secret.txt")))
    denied(list(config, t.resolve("data/dlink")))
    denied(info(config, t.resolve("data/dlink/secret.txt")))
  }

  it should "still refuse a directory link out of the allowed directory when links are followed" in {
    val t = newRoot()
    file(t.resolve("outside/secret.txt"), "SECRET")
    Files.createDirectories(t.resolve("data"))
    link(t.resolve("data/dlink"), t.resolve("outside"))
    val config = allowing(t.resolve("data"), followSymlinks = true)

    denied(read(config, t.resolve("data/dlink/secret.txt")))
    denied(list(config, t.resolve("data/dlink")))
  }

  it should "refuse to write through a directory link that leads outside, and create nothing there" in {
    val t = newRoot()
    Files.createDirectories(t.resolve("outside"))
    Files.createDirectories(t.resolve("data"))
    link(t.resolve("data/dlink"), t.resolve("outside"))

    denied(write(WriteConfig(allowedPaths = Seq(t.resolve("data").toString)), t.resolve("data/dlink/pwn.txt"), "x"))
    Files.exists(t.resolve("outside/pwn.txt")) shouldBe false
  }

  it should "refuse to overwrite, through a file link, a file outside the allowed directory" in {
    val t      = newRoot()
    val target = file(t.resolve("outside/target.txt"), "ORIGINAL")
    Files.createDirectories(t.resolve("data"))
    link(t.resolve("data/flink"), target)
    val config = WriteConfig(allowedPaths = Seq(t.resolve("data").toString), allowOverwrite = true)

    denied(write(config, t.resolve("data/flink"), "OVERWRITTEN"))
    Files.readString(target) shouldBe "ORIGINAL"
    Files.isSymbolicLink(t.resolve("data/flink")) shouldBe true
  }

  it should "write through a link that stays inside the allowed directory, to its real target" in {
    val t    = newRoot()
    val real = file(t.resolve("data/real.txt"), "before")
    link(t.resolve("data/alias.txt"), real)
    val config = WriteConfig(allowedPaths = Seq(t.resolve("data").toString), allowOverwrite = true)

    write(config, t.resolve("data/alias.txt"), "after").map(_.bytesWritten) shouldBe Right(5)
    Files.readString(real) shouldBe "after"
    Files.isSymbolicLink(t.resolve("data/alias.txt")) shouldBe true
  }

  it should "read through a directory link that stays inside the allowed directory, whatever followSymlinks says" in {
    val t = newRoot()
    file(t.resolve("data/sub/f.txt"), "inner")
    link(t.resolve("data/inlink"), t.resolve("data/sub"))

    read(allowing(t.resolve("data")), t.resolve("data/inlink/f.txt")).map(_.content) shouldBe Right("inner")
    read(allowing(t.resolve("data"), followSymlinks = true), t.resolve("data/inlink/f.txt"))
      .map(_.content) shouldBe Right("inner")
  }

  it should "follow a link that is the final component only when followSymlinks is true" in {
    val t = newRoot()
    file(t.resolve("data/f.txt"), "target")
    link(t.resolve("data/flink-in"), t.resolve("data/f.txt"))

    read(allowing(t.resolve("data")), t.resolve("data/flink-in")).left.toOption
      .getOrElse(fail("expected a refusal")) should include("Not a regular file")
    read(allowing(t.resolve("data"), followSymlinks = true), t.resolve("data/flink-in"))
      .map(_.content) shouldBe Right("target")
  }

  it should "refuse a link whose target does not exist, since where it leads is unknown" in {
    val t = newRoot()
    Files.createDirectories(t.resolve("data"))
    Files.createDirectories(t.resolve("outside"))
    link(t.resolve("data/dangling"), t.resolve("outside/missing.txt"))

    denied(write(WriteConfig(allowedPaths = Seq(t.resolve("data").toString)), t.resolve("data/dangling"), "x"))
    Files.exists(t.resolve("outside/missing.txt")) shouldBe false
  }

  it should "accept an allowed entry that is itself a link, for the files behind it" in {
    val t = newRoot()
    file(t.resolve("real/ok.txt"), "behind-link")
    link(t.resolve("entry"), t.resolve("real"))
    val config = allowing(t.resolve("entry"))

    read(config, t.resolve("entry/ok.txt")).map(_.content) shouldBe Right("behind-link")
    read(config, t.resolve("real/ok.txt")).map(_.content) shouldBe Right("behind-link")
  }

  it should "treat a blocked entry that is a link as blocking the directory it leads to" in {
    val t = newRoot()
    file(t.resolve("secret/x.txt"), "SECRET")
    link(t.resolve("blocked-entry"), t.resolve("secret"))
    val config = FileConfig(allowedPaths = None, blockedPaths = Seq(t.resolve("blocked-entry").toString))

    denied(read(config, t.resolve("secret/x.txt")))
  }

  it should "list an entry that links out of the allowed directory as a link, without following it" in {
    val t = newRoot()
    file(t.resolve("outside/secret.txt"), "SECRET-THAT-HAS-A-SIZE")
    file(t.resolve("data/ok.txt"), "ok")
    link(t.resolve("data/out.txt"), t.resolve("outside/secret.txt"))

    val entries = list(allowing(t.resolve("data"), followSymlinks = true), t.resolve("data"))
      .fold(e => fail(e), _.entries)

    val outLink = entries.find(_.name == "out.txt").getOrElse(fail("the link is not listed"))
    outLink.isSymlink shouldBe true
    outLink.size shouldBe 0L
    entries.find(_.name == "ok.txt").map(_.size) shouldBe Some(2L)
  }

  it should "report the path it was given, not the resolved one" in {
    val t = newRoot()
    file(t.resolve("data/ok.txt"), "x")

    read(allowing(t.resolve("data")), t.resolve("data/./ok.txt")).map(_.path) shouldBe
      Right(t.resolve("data/ok.txt").toString)
  }
}
