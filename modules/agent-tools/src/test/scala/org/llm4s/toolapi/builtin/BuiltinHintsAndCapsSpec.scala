package org.llm4s.toolapi.builtin

import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin.filesystem._
import org.llm4s.toolapi.builtin.http._
import org.llm4s.toolapi.builtin.shell.ShellConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import scala.concurrent.duration._

/**
 * The built-in tools declare what they do, so `ApprovalMiddleware.unlessReadOnly` can tell `read_file` from
 * `write_file`, and hand the model a bounded result (#3273).
 */
class BuiltinHintsAndCapsSpec extends AnyFlatSpec with Matchers {

  private def ok[A](r: org.llm4s.types.Result[A]): A = r.fold(e => fail(e.formatted), identity)

  private def hintsOf(registry: ToolRegistry, name: String): Option[ToolHints] = registry match {
    case source: ToolHintSource => source.toolHints(name)
    case _                      => fail("the registry reports no hints")
  }

  "BuiltinTools.registry" should "report each built-in tool's hints, from its configuration" in {
    val dir   = Files.createTempDirectory("hints").toString
    val tools = ok(BuiltinTools.developmentSafe(Some(dir), Seq(dir)))
    val reg   = ok(BuiltinTools.registry(tools))
    Seq("get_current_datetime", "calculator", "generate_uuid", "json_tool", "read_file", "list_directory", "file_info")
      .foreach(name => withClue(name)(hintsOf(reg, name).map(_.readOnly) shouldBe Some(true)))
    hintsOf(reg, "write_file").map(_.readOnly) shouldBe Some(false)
    hintsOf(reg, "shell_command") shouldBe Some(ToolHints.default)
    hintsOf(reg, "http_request").map(h => (h.readOnly, h.openWorld)) shouldBe Some((true, true))
  }

  it should "make http_request read-only only when every method it allows is safe" in {
    val posting =
      ok(BuiltinTools.registry(Seq(ok(HTTPTool.createSafe(HttpConfig(allowedMethods = Seq("GET", "POST")))))))
    hintsOf(posting, "http_request").map(_.readOnly) shouldBe Some(false)
  }

  it should "give a tool of one's own that shares a built-in name no hints" in {
    val mine = ok(
      ToolBuilder[String]("read_file", "mine", Schema.`object`[Map[String, Any]]("p"))
        .withHandler(_ => Right("x"))
        .build()
    )
    hintsOf(ok(BuiltinTools.registry(Seq(mine))), "read_file") shouldBe None
  }

  "read_file" should "hand the model at most maxResultChars characters, and say where the next page starts" in {
    val dir  = Files.createTempDirectory("cap")
    val file = Files.writeString(dir.resolve("big.txt"), ("x" * 99 + "\n") * 1000)
    val tool = ok(ReadFileTool.createSafe(FileConfig(blockedPaths = Seq.empty), maxResultChars = 5000))
    val read =
      tool.handler(SafeParameterExtractor(ujson.Obj("path" -> file.toString))).fold(e => fail(e.message), identity)
    read.content.length shouldBe 5000
    read.truncated shouldBe true
    read.nextOffset shouldBe Some(5000)
    read.size shouldBe 100000L
    val default = ok(ReadFileTool.createSafe(FileConfig(blockedPaths = Seq.empty)))
    default.handler(SafeParameterExtractor(ujson.Obj("path" -> file.toString))).map(_.content.length) shouldBe
      Right(BuiltinTools.DefaultMaxResultChars)
  }

  it should "read a whole 100k-character file across pages, with no gap or overlap, multi-byte text and CRLF included" in {
    val dir = Files.createTempDirectory("pages")
    // CRLF line ends, and a surrogate pair and an accented letter on every line, so cuts fall inside them
    val line   = "ab\ud83d\ude00c\u00e9" + "z" * 93
    val file   = Files.writeString(dir.resolve("crlf.txt"), (line + "\r\n") * 1000)
    val config = FileConfig(blockedPaths = Seq.empty, maxFileSize = 10L * 1024 * 1024)
    def call(tool: ToolFunction[ReadFileResult], offset: Option[Int]) =
      tool
        .handler(
          SafeParameterExtractor(
            ujson.Obj.from(Seq("path" -> ujson.Str(file.toString)) ++ offset.map(o => "offset" -> ujson.Num(o)))
          )
        )
        .fold(e => fail(e.message), identity)
    val whole = call(ok(ReadFileTool.createSafe(config, maxResultChars = Int.MaxValue)), None).content
    whole.length should be > 99000
    Seq(7001, 4096, 2).foreach { cap =>
      val tool = ok(ReadFileTool.createSafe(config, maxResultChars = cap))
      val pages = Iterator
        .iterate(Option(call(tool, None)))(_.flatMap(p => p.nextOffset.map(o => call(tool, Some(o)))))
        .takeWhile(_.isDefined)
        .flatten
        .toVector
      withClue(s"cap $cap") {
        pages.map(_.content).mkString shouldBe whole
        pages.foreach(p => p.content.length should be <= cap)
        pages.foreach(p => Character.isLowSurrogate(p.content.head) shouldBe false)
        pages.init.foreach(_.truncated shouldBe true)
        pages.last.nextOffset shouldBe None
      }
    }
    val tool = ok(ReadFileTool.createSafe(config, maxResultChars = 100))
    tool
      .handler(SafeParameterExtractor(ujson.Obj("path" -> file.toString, "offset" -> whole.length)))
      .isLeft shouldBe true
    tool.handler(SafeParameterExtractor(ujson.Obj("path" -> file.toString, "offset" -> -1))).isLeft shouldBe true
  }

  "http_request" should "cut a body over maxResultChars, never inside a surrogate pair" in {
    val result = HTTPResult("u", "GET", 200, "OK", Map.empty, "a" * 10, None, 10L, truncated = false, 1.milli)
    HTTPTool.capped(result, 20) shouldBe result
    HTTPTool.capped(result, 4).body shouldBe "aaaa"
    HTTPTool.capped(result, 4).truncated shouldBe true
    HTTPTool.capped(result.copy(body = "a😀b"), 2).body shouldBe "a"
  }

  "The defaults" should "bound what a result can hold" in {
    HttpConfig().maxResponseSize shouldBe 1024L * 1024
    ShellConfig().maxOutputSize shouldBe BuiltinTools.DefaultMaxResultChars / 2
  }
}
