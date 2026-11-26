package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.toolapi.{ SafeParameterExtractor, ToolHandlerError, ToolParameterError }
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/** #3049: `write_file` never destroys or silently corrupts a file. */
class WriteFileToolSpec extends AnyFlatSpec with Matchers with EitherValues with BeforeAndAfterEach {

  private var dir: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit = {
    super.beforeEach()
    dir = Files.createTempDirectory("llm4s-write-file-")
  }

  override def afterEach(): Unit = {
    Using.resource(Files.walk(dir))(_.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p)))
    super.afterEach()
  }

  private def config(maxFileSize: Long = 10 * 1024 * 1024, allowOverwrite: Boolean = true) =
    WriteConfig(allowedPaths = Seq(dir.toString), maxFileSize = maxFileSize, allowOverwrite = allowOverwrite)

  private def write(config: WriteConfig, params: ujson.Obj): Either[ToolHandlerError, WriteFileResult] =
    WriteFileTool.createSafe(config).fold(e => fail(e.formatted), _.handler(SafeParameterExtractor(params)))

  private def existing(name: String, content: String): Path =
    Files.writeString(dir.resolve(name), content)

  private def names: Set[String] =
    Using.resource(Files.list(dir))(_.iterator().asScala.map(_.getFileName.toString).toSet)

  /** Runs `thunk` with this thread interrupted, as a `ToolExecutor` timeout leaves the worker, then clears it. */
  private def interrupted[A](thunk: => A): A = {
    Thread.currentThread().interrupt()
    val result = Try(thunk)
    Thread.interrupted()
    result.get
  }

  "write_file" should "leave the original whole when an overwrite is interrupted" in {
    val target = existing("data.txt", "ORIGINAL")
    val result = interrupted(write(config(), ujson.Obj("path" -> target.toString, "content" -> "replacement")))
    result.isLeft shouldBe true
    Files.readString(target) shouldBe "ORIGINAL"
    names shouldBe Set("data.txt")
  }

  it should "replace an existing file with the new content and leave no temporary file" in {
    val target = existing("data.txt", "a much longer original content")
    write(config(), ujson.Obj("path" -> target.toString, "content" -> "short")).value.created shouldBe false
    Files.readString(target) shouldBe "short"
    names shouldBe Set("data.txt")
  }

  it should "keep the original's POSIX permissions when it replaces it" in {
    val target = existing("script.sh", "#!/bin/sh")
    assume(Try(Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-x---"))).isSuccess)
    write(config(), ujson.Obj("path" -> target.toString, "content" -> "#!/bin/sh\necho hi")).isRight shouldBe true
    PosixFilePermissions.toString(Files.getPosixFilePermissions(target)) shouldBe "rwxr-x---"
  }

  it should "not overwrite a file whose append argument is mistyped" in {
    val target = existing("log.txt", "KEEP")
    Seq[ujson.Value](ujson.Str("true"), ujson.Num(1)).foreach { mistyped =>
      val result = write(config(), ujson.Obj("path" -> target.toString, "content" -> "x", "append" -> mistyped))
      result.left.value shouldBe a[ToolParameterError.TypeMismatch]
      Files.readString(target) shouldBe "KEEP"
    }
  }

  it should "refuse a mistyped encoding rather than use UTF-8" in {
    val target = dir.resolve("new.txt")
    write(config(), ujson.Obj("path" -> target.toString, "content" -> "x", "encoding" -> 8)).left.value shouldBe
      a[ToolParameterError.TypeMismatch]
    Files.exists(target) shouldBe false
  }

  it should "take the defaults for a null append and encoding" in {
    val target = existing("data.txt", "old")
    val params =
      ujson.Obj("path" -> target.toString, "content" -> "né", "append" -> ujson.Null, "encoding" -> ujson.Null)
    write(config(), params).value.appended shouldBe false
    Files.readAllBytes(target) shouldBe "né".getBytes(StandardCharsets.UTF_8)
  }

  it should "refuse an append that would grow the file past maxFileSize" in {
    val target = existing("log.txt", "12345678")
    val params = ujson.Obj("path" -> target.toString, "content" -> "abc", "append" -> true)
    write(config(maxFileSize = 10), params).left.value.message should include("File would be too large")
    Files.readString(target) shouldBe "12345678"
  }

  it should "allow an append that ends exactly at maxFileSize" in {
    val target = existing("log.txt", "1234567")
    val params = ujson.Obj("path" -> target.toString, "content" -> "abc", "append" -> true)
    write(config(maxFileSize = 10), params).value.appended shouldBe true
    Files.readString(target) shouldBe "1234567abc"
  }

  it should "refuse text the encoding cannot represent instead of writing ?" in {
    Seq("café", "日本語").foreach { text =>
      val target = dir.resolve("ascii.txt")
      val result = write(config(), ujson.Obj("path" -> target.toString, "content" -> text, "encoding" -> "US-ASCII"))
      result.left.value.message should include("cannot be encoded as US-ASCII")
      Files.exists(target) shouldBe false
    }
  }

  it should "refuse an unpaired surrogate rather than write a replacement" in {
    val target = dir.resolve("broken.txt")
    write(config(), ujson.Obj("path" -> target.toString, "content" -> "a\ud800b")).left.value.message should
      include("cannot be encoded as UTF-8")
    Files.exists(target) shouldBe false
  }

  it should "write text the encoding can represent" in {
    val target = dir.resolve("latin1.txt")
    write(
      config(),
      ujson.Obj("path" -> target.toString, "content" -> "café", "encoding" -> "ISO-8859-1")
    ).value.bytesWritten shouldBe 4
    Files.readAllBytes(target) shouldBe "café".getBytes(StandardCharsets.ISO_8859_1)
  }

  it should "remove a new file whose write is interrupted" in {
    val target = dir.resolve("new.txt")
    interrupted(write(config(), ujson.Obj("path" -> target.toString, "content" -> "x"))).isLeft shouldBe true
    names shouldBe empty
  }

  it should "create the staging file owner-only, before any content is written" in {
    val target = existing("private.txt", "secret")
    val temp   = WriteFileTool.stagingPath(target)
    Using.resource(WriteFileTool.openStaging(temp)) { _ =>
      assume(Try(Files.getPosixFilePermissions(temp)).isSuccess, "POSIX permissions only")
      PosixFilePermissions.toString(Files.getPosixFilePermissions(temp)) shouldBe "rw-------"
    }
    Files.deleteIfExists(temp)
  }

  it should "keep a private original private when it replaces it" in {
    val target = existing("private.txt", "secret")
    assume(Try(Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))).isSuccess)
    write(config(), ujson.Obj("path" -> target.toString, "content" -> "new secret")).isRight shouldBe true
    PosixFilePermissions.toString(Files.getPosixFilePermissions(target)) shouldBe "rw-------"
  }

  it should "refuse the replace, keeping the original, when its access rights cannot be copied" in {
    val target = existing("data.txt", "ORIGINAL")
    val result = WriteFileTool.replace(
      target,
      "new".getBytes(StandardCharsets.UTF_8),
      (_, _) => throw new java.nio.file.AccessDeniedException("permissions")
    )
    result.isFailure shouldBe true
    Files.readString(target) shouldBe "ORIGINAL"
    names shouldBe Set("data.txt")
  }

  it should "stage under a bounded name, so a target with a long name can be replaced" in {
    val name   = "n" * 250 + ".txt"
    val target = existing(name, "ORIGINAL")
    WriteFileTool.stagingPath(target).getFileName.toString.length should be < 64
    write(config(), ujson.Obj("path" -> target.toString, "content" -> "replaced")).isRight shouldBe true
    Files.readString(target) shouldBe "replaced"
    names shouldBe Set(name)
  }

  it should "refuse to replace a directory" in {
    val target = Files.createDirectory(dir.resolve("sub"))
    write(config(), ujson.Obj("path" -> target.toString, "content" -> "x")).isLeft shouldBe true
    Files.isDirectory(target) shouldBe true
  }
}
