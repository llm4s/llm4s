package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.toolapi.{ SafeParameterExtractor, ToolHandlerError, ToolParameterError }
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import scala.jdk.CollectionConverters._
import scala.util.Using

/** #3049: in the read tools an optional argument is its default when absent or null, and refused when mistyped. */
class FileToolArgumentsSpec extends AnyFlatSpec with Matchers with EitherValues with BeforeAndAfterEach {

  private var dir: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit = {
    super.beforeEach()
    dir = Files.createTempDirectory("llm4s-file-args-")
    Files.writeString(dir.resolve("a.txt"), "one\ntwo\nthree")
    Files.writeString(dir.resolve(".hidden"), "h")
  }

  override def afterEach(): Unit = {
    Using.resource(Files.walk(dir))(_.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p)))
    super.afterEach()
  }

  private def config = FileConfig(allowedPaths = Some(Seq(dir.toString)), blockedPaths = Seq.empty)

  private def read(params: ujson.Obj): Either[ToolHandlerError, ReadFileResult] =
    ReadFileTool.createSafe(config).fold(e => fail(e.formatted), _.handler(SafeParameterExtractor(params)))

  private def list(params: ujson.Obj): Either[ToolHandlerError, ListDirectoryResult] =
    ListDirectoryTool.createSafe(config).fold(e => fail(e.formatted), _.handler(SafeParameterExtractor(params)))

  private def file = dir.resolve("a.txt").toString

  "read_file" should "refuse a mistyped max_lines rather than read the whole file" in {
    read(ujson.Obj("path" -> file, "max_lines" -> "1")).left.value shouldBe a[ToolParameterError.TypeMismatch]
  }

  it should "refuse a mistyped encoding" in {
    read(ujson.Obj("path" -> file, "encoding" -> true)).left.value shouldBe a[ToolParameterError.TypeMismatch]
  }

  it should "take the defaults for absent or null optional arguments" in {
    read(ujson.Obj("path" -> file)).value.content shouldBe "one\ntwo\nthree"
    read(ujson.Obj("path" -> file, "max_lines" -> ujson.Null, "encoding" -> ujson.Null)).value.content shouldBe
      "one\ntwo\nthree"
  }

  it should "honour a well-typed max_lines" in {
    read(ujson.Obj("path" -> file, "max_lines" -> 1)).value.truncated shouldBe true
  }

  "list_directory" should "refuse a mistyped max_entries or include_hidden" in {
    list(ujson.Obj("path" -> dir.toString, "max_entries" -> "5")).left.value shouldBe
      a[ToolParameterError.TypeMismatch]
    list(ujson.Obj("path" -> dir.toString, "include_hidden" -> "true")).left.value shouldBe
      a[ToolParameterError.TypeMismatch]
  }

  it should "take the defaults for absent or null optional arguments" in {
    list(ujson.Obj("path" -> dir.toString)).value.entries.map(_.name) shouldBe Seq("a.txt")
    list(ujson.Obj("path" -> dir.toString, "max_entries" -> ujson.Null, "include_hidden" -> ujson.Null)).value.entries
      .map(_.name) shouldBe Seq("a.txt")
  }

  it should "honour a well-typed include_hidden" in {
    list(ujson.Obj("path" -> dir.toString, "include_hidden" -> true)).value.entries.map(_.name).toSet shouldBe
      Set("a.txt", ".hidden")
  }
}
