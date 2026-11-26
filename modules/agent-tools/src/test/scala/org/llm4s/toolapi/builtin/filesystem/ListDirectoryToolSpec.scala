package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.toolapi.{ SafeParameterExtractor, ToolFunction }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * The `list_directory` tool's limits, ordering, counts, sizes and errors (#2236). Symbolic links and path-policy
 * bypasses are covered by #1408 and left out here.
 */
class ListDirectoryToolSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // The allowed area, and a sibling outside it; both are recreated for every test and removed after it
  private var tempDir: Path    = _
  private var outsideDir: Path = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    tempDir = Files.createTempDirectory("list-directory-tool").toRealPath()
    outsideDir = Files.createTempDirectory("list-directory-outside").toRealPath()
  }

  override def afterEach(): Unit =
    try {
      deleteRecursively(tempDir)
      deleteRecursively(outsideDir)
    } finally super.afterEach()

  private def deleteRecursively(root: Path): Unit =
    if (root != null && Files.exists(root))
      Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p)))

  // Blocked paths are emptied so the test does not depend on where the platform puts its temporary directory
  private def toolWith(config: FileConfig): ToolFunction[ListDirectoryResult] =
    ListDirectoryTool
      .createSafe(config)
      .fold(e => fail(s"Tool creation failed: ${e.formatted}"), identity)

  private def tool: ToolFunction[ListDirectoryResult] =
    toolWith(FileConfig(allowedPaths = Some(Seq(tempDir.toString)), blockedPaths = Seq.empty))

  private def listWith(
    tool: ToolFunction[ListDirectoryResult],
    params: (String, ujson.Value)*
  ): Either[String, ListDirectoryResult] =
    tool.handler(SafeParameterExtractor(ujson.Obj.from(params))).left.map(_.message)

  private def list(params: (String, ujson.Value)*): Either[String, ListDirectoryResult] =
    listWith(tool, params: _*)

  private def listOk(params: (String, ujson.Value)*): ListDirectoryResult =
    list(params: _*).fold(err => fail(s"Expected Right but got Left: $err"), identity)

  private def file(name: String, bytes: Int = 0): Path =
    Files.write(tempDir.resolve(name), Array.fill[Byte](bytes)('x'.toByte))

  private def dir(name: String): Path = Files.createDirectory(tempDir.resolve(name))

  "ListDirectoryTool" should "return the entries sorted by name" in {
    file("b.txt")
    file("a.txt")
    dir("c")

    listOk("path" -> tempDir.toString).entries.map(_.name) shouldBe Seq("a.txt", "b.txt", "c")
  }

  it should "truncate to max_entries and say so" in {
    file("a.txt")
    file("b.txt")
    file("c.txt")

    val result = listOk("path" -> tempDir.toString, "max_entries" -> 2)

    result.entries.map(_.name) shouldBe Seq("a.txt", "b.txt")
    result.truncated shouldBe true
  }

  it should "not report truncation when the directory has exactly max_entries entries" in {
    file("a.txt")
    file("b.txt")

    val result = listOk("path" -> tempDir.toString, "max_entries" -> 2)

    result.entries.map(_.name) shouldBe Seq("a.txt", "b.txt")
    result.truncated shouldBe false
  }

  it should "count only the entries it returned" in {
    dir("a-dir")
    file("b.txt")
    file("c.txt")
    dir("d-dir")
    file("e.txt")

    val result = listOk("path" -> tempDir.toString, "max_entries" -> 3)

    result.entries.map(_.name) shouldBe Seq("a-dir", "b.txt", "c.txt")
    result.truncated shouldBe true
    result.totalFiles shouldBe 2
    result.totalDirectories shouldBe 1
  }

  it should "cap max_entries at 100" in {
    (0 until 101).foreach(i => file(f"file-$i%03d.txt"))

    val result = listOk("path" -> tempDir.toString, "max_entries" -> 500)

    result.entries.size shouldBe 100
    result.entries.last.name shouldBe "file-099.txt"
    result.truncated shouldBe true
    result.totalFiles shouldBe 100
  }

  it should "treat a max_entries below 1 as 1" in {
    file("a.txt")
    file("b.txt")

    val result = listOk("path" -> tempDir.toString, "max_entries" -> 0)

    result.entries.map(_.name) shouldBe Seq("a.txt")
    result.truncated shouldBe true
  }

  it should "not describe entries the file policy blocks" in {
    file("visible.txt", bytes = 12)
    val blocked = dir(".blocked")
    Files.write(blocked.resolve("secret.txt"), "secret".getBytes("UTF-8"))

    val blockingTool = toolWith(
      FileConfig(allowedPaths = Some(Seq(tempDir.toString)), blockedPaths = Seq(blocked.toString))
    )
    val result = listWith(blockingTool, "path" -> tempDir.toString, "include_hidden" -> true)
      .fold(err => fail(s"Expected Right but got Left: $err"), identity)

    result.entries.map(_.name) shouldBe Seq("visible.txt")
    result.totalFiles shouldBe 1
  }

  it should "not fail the whole listing when a child's attributes cannot be read" in {
    assume(Try(Files.getPosixFilePermissions(tempDir)).isSuccess, "POSIX permissions only")
    assume(System.getProperty("user.name") != "root", "root can stat through a directory without execute permission")
    val original = Files.getPosixFilePermissions(tempDir)
    file("a.txt")

    Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("r--r--r--"))
    try {
      val result = listOk("path" -> tempDir.toString)
      result.entries shouldBe empty
      result.truncated shouldBe false
    } finally Files.setPosixFilePermissions(tempDir, original)
  }

  it should "report a file's length in bytes and a directory's size as 0" in {
    file("data.bin", bytes = 1234)
    dir("sub")

    val entries = listOk("path" -> tempDir.toString).entries.map(e => e.name -> e).toMap

    val data = entries("data.bin")
    data.size shouldBe 1234L
    data.isFile shouldBe true
    data.isDirectory shouldBe false
    data.path shouldBe tempDir.resolve("data.bin").toString

    val sub = entries("sub")
    sub.size shouldBe 0L
    sub.isFile shouldBe false
    sub.isDirectory shouldBe true
  }

  it should "refuse a path that does not exist" in {
    val missing = tempDir.resolve("missing")

    list("path" -> missing.toString) shouldBe Left(s"Directory not found: $missing")
  }

  it should "refuse a path that is a regular file" in {
    val regular = file("plain.txt", bytes = 3)

    list("path" -> regular.toString) shouldBe Left(s"Not a directory: $regular")
  }

  it should "refuse a path outside allowedPaths" in {
    Files.write(outsideDir.resolve("secret.txt"), "secret".getBytes)

    val result = list("path" -> outsideDir.toString)

    result.isLeft shouldBe true
    result.left.getOrElse("") should include("Access denied")
  }

  it should "refuse a call without a path" in {
    list("max_entries" -> 10).isLeft shouldBe true
  }
}
