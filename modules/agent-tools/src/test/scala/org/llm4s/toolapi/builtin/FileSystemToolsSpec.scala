package org.llm4s.toolapi.builtin

import org.llm4s.toolapi.SafeParameterExtractor
import org.llm4s.toolapi.builtin.filesystem._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._
import scala.util.Using

class FileSystemToolsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

  // Use system temp directory for cross-platform compatibility
  private val testDir = {
    val tempRoot = System.getProperty("java.io.tmpdir")
    val dir      = Paths.get(tempRoot, "llm4s-test-" + System.currentTimeMillis())
    Files.createDirectories(dir)
    dir
  }

  // Per-test scratch directory, removed after every test whether it passed or failed
  private val scratchRoot = testDir.resolve("scratch")

  private def scratchDir(): Path = Files.createDirectories(scratchRoot)

  override def afterEach(): Unit = {
    deleteRecursively(scratchRoot)
    super.afterEach()
  }

  private def deleteRecursively(root: Path): Unit =
    if (Files.exists(root))
      Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p)))

  private def allFiles(root: Path): List[Path] =
    Using.resource(Files.walk(root))(_.iterator().asScala.toList)

  "FileConfig" should "block system paths by default" in {
    assume(!isWindows, "Unix system paths not available on Windows")
    val config = FileConfig()

    config.isPathAllowed(Paths.get("/etc/passwd")) shouldBe false
    config.isPathAllowed(Paths.get("/var/log/syslog")) shouldBe false
    config.isPathAllowed(Paths.get("/sys/kernel")) shouldBe false
    config.isPathAllowed(Paths.get("/proc/1/status")) shouldBe false
    config.isPathAllowed(Paths.get("/dev/null")) shouldBe false
  }

  it should "allow paths when allowedPaths is set" in {
    assume(!isWindows, "Unix paths not available on Windows")
    val config = FileConfig(allowedPaths = Some(Seq("/tmp", "/home/user")))

    config.isPathAllowed(Paths.get("/tmp/file.txt")) shouldBe true
    config.isPathAllowed(Paths.get("/home/user/doc.txt")) shouldBe true
    config.isPathAllowed(Paths.get("/other/file.txt")) shouldBe false
  }

  it should "block paths in blocklist even if in allowlist" in {
    assume(!isWindows, "Unix paths not available on Windows")
    val config = FileConfig(
      allowedPaths = Some(Seq("/")),
      blockedPaths = Seq("/etc", "/var")
    )

    config.isPathAllowed(Paths.get("/tmp/ok.txt")) shouldBe true
    config.isPathAllowed(Paths.get("/etc/passwd")) shouldBe false
    config.isPathAllowed(Paths.get("/var/log/test")) shouldBe false
  }

  "WriteConfig" should "require explicit allowed paths" in {
    assume(!isWindows, "Unix paths not available on Windows")
    val config = WriteConfig(allowedPaths = Seq("/tmp/output"))

    config.isPathAllowed(Paths.get("/tmp/output/file.txt")) shouldBe true
    config.isPathAllowed(Paths.get("/tmp/other/file.txt")) shouldBe false
    config.isPathAllowed(Paths.get("/home/user/file.txt")) shouldBe false
  }

  it should "not treat a sibling sharing a name prefix as inside an allowed path" in {
    assume(!isWindows, "Unix paths not available on Windows")
    val config = WriteConfig(allowedPaths = Seq("/srv/agent-data"))

    config.isPathAllowed(Paths.get("/srv/agent-data")) shouldBe true
    config.isPathAllowed(Paths.get("/srv/agent-data/out.txt")) shouldBe true
    config.isPathAllowed(Paths.get("/srv/agent-data-secret/out.txt")) shouldBe false
    config.isPathAllowed(Paths.get("/srv/agent-data/../agent-data-secret/out.txt")) shouldBe false
  }

  "FileConfig" should "not treat a sibling sharing a name prefix as inside an allowed path" in {
    assume(!isWindows, "Unix paths not available on Windows")
    val config = FileConfig(allowedPaths = Some(Seq("/srv/agent-data/")), blockedPaths = Seq("/srv/agent-data/private"))

    config.isPathAllowed(Paths.get("/srv/agent-data/notes.txt")) shouldBe true
    config.isPathAllowed(Paths.get("/srv/agent-data-secret/key.pem")) shouldBe false
    config.isPathAllowed(Paths.get("/srv/agent-data/private/key.pem")) shouldBe false
    config.isPathAllowed(Paths.get("/srv/agent-data/private-ok/notes.txt")) shouldBe true
  }

  "ReadFileTool" should "read existing files" in {
    val dir      = scratchDir()
    val tempFile = dir.resolve("read-test.txt")
    Files.writeString(tempFile, "Hello, World!")

    // No blocked paths, only the scratch dir allowed
    val config = FileConfig(allowedPaths = Some(Seq(dir.toString)), blockedPaths = Seq.empty)
    ReadFileTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("path" -> tempFile.toString)
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              readResult => {
                readResult.content shouldBe "Hello, World!"
                readResult.lines shouldBe 1
                readResult.truncated shouldBe false
              }
            )
        }
      )
  }

  private def readWithEncoding(encoding: Option[String], content: String): Either[String, ReadFileResult] = {
    val tempFile = scratchDir().resolve("read-encoding.txt")
    Files.writeString(tempFile, content, StandardCharsets.UTF_8)
    readPath(tempFile, encoding)
  }

  private def readPath(path: Path, encoding: Option[String]): Either[String, ReadFileResult] = {
    val config = FileConfig(allowedPaths = Some(Seq(scratchRoot.toString)), blockedPaths = Seq.empty)
    val params = ujson.Obj("path" -> path.toString)
    encoding.foreach(e => params("encoding") = e)
    ReadFileTool
      .createSafe(config)
      .fold(e => fail(s"Tool creation failed: ${e.formatted}"), _.handler(SafeParameterExtractor(params)))
  }

  it should "return an error for an unknown encoding" in {
    readWithEncoding(Some("utf-9"), "Hello, World!") shouldBe Left("Unsupported encoding: utf-9")
  }

  it should "return an error for an illegal encoding name" in {
    readWithEncoding(Some("bad name!"), "Hello, World!") shouldBe Left("Unsupported encoding: bad name!")
  }

  it should "return an error for an empty encoding name" in {
    readWithEncoding(Some(""), "Hello, World!") shouldBe Left("Unsupported encoding: ")
  }

  it should "read as UTF-8 when no encoding is given" in {
    readWithEncoding(None, "héllo").map(_.content) shouldBe Right("héllo")
  }

  it should "deny access to blocked paths" in {
    assume(!isWindows, "Unix system paths not available on Windows")
    val config = FileConfig()
    ReadFileTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("path" -> "/etc/passwd")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => err should include("Access denied"),
              result => fail(s"Expected Left but got Right: $result")
            )
        }
      )
  }

  it should "return error for non-existent files" in {
    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    ReadFileTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("path" -> (testDir.toString + "/nonexistent_file_12345.txt"))
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => err should include("not found"),
              result => fail(s"Expected Left but got Right: $result")
            )
        }
      )
  }

  it should "limit lines when max_lines is specified" in {
    val tempFile = testDir.resolve("lines-test.txt")
    Files.writeString(tempFile, "line1\nline2\nline3\nline4\nline5")

    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    ReadFileTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("path" -> tempFile.toString, "max_lines" -> 2)
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              readResult => {
                readResult.content shouldBe "line1\nline2"
                readResult.truncated shouldBe true

                Files.deleteIfExists(tempFile)
              }
            )
        }
      )
  }

  "ListDirectoryTool" should "list directory contents" in {
    val subDir = testDir.resolve("list-test")
    Files.createDirectories(subDir)
    Files.createFile(subDir.resolve("file1.txt"))
    Files.createFile(subDir.resolve("file2.txt"))
    Files.createDirectory(subDir.resolve("subdir"))

    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    ListDirectoryTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("path" -> subDir.toString)
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              listResult => {
                listResult.entries.size shouldBe 3
                listResult.totalFiles shouldBe 2
                listResult.totalDirectories shouldBe 1

                // Cleanup
                Files.deleteIfExists(subDir.resolve("file1.txt"))
                Files.deleteIfExists(subDir.resolve("file2.txt"))
                Files.deleteIfExists(subDir.resolve("subdir"))
                Files.deleteIfExists(subDir)
              }
            )
        }
      )
  }

  it should "hide hidden files by default" in {
    val subDir = testDir.resolve("hidden-test")
    Files.createDirectories(subDir)
    Files.createFile(subDir.resolve("visible.txt"))
    Files.createFile(subDir.resolve(".hidden"))

    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    ListDirectoryTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("path" -> subDir.toString)
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              listResult => {
                listResult.entries.size shouldBe 1
                listResult.entries.head.name shouldBe "visible.txt"

                Files.deleteIfExists(subDir.resolve("visible.txt"))
                Files.deleteIfExists(subDir.resolve(".hidden"))
                Files.deleteIfExists(subDir)
              }
            )
        }
      )
  }

  it should "include hidden files when requested" in {
    val subDir = testDir.resolve("hidden-include-test")
    Files.createDirectories(subDir)
    Files.createFile(subDir.resolve("visible.txt"))
    Files.createFile(subDir.resolve(".hidden"))

    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    ListDirectoryTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("path" -> subDir.toString, "include_hidden" -> true)
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              listResult => {
                listResult.entries.size shouldBe 2

                Files.deleteIfExists(subDir.resolve("visible.txt"))
                Files.deleteIfExists(subDir.resolve(".hidden"))
                Files.deleteIfExists(subDir)
              }
            )
        }
      )
  }

  "FileInfoTool" should "get file information" in {
    val tempFile = testDir.resolve("info-test.txt")
    Files.writeString(tempFile, "test content")

    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    FileInfoTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("path" -> tempFile.toString)
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              infoResult => {
                infoResult.exists shouldBe true
                infoResult.isFile shouldBe true
                infoResult.isDirectory shouldBe false
                infoResult.size shouldBe 12
                infoResult.extension shouldBe Some("txt")

                Files.deleteIfExists(tempFile)
              }
            )
        }
      )
  }

  it should "report non-existent file info" in {
    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
    FileInfoTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("path" -> (testDir.toString + "/nonexistent_12345.txt"))
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              infoResult => {
                infoResult.exists shouldBe false
                infoResult.isFile shouldBe false
                infoResult.isDirectory shouldBe false
              }
            )
        }
      )
  }

  "WriteFileTool" should "write to allowed paths" in {
    val config = WriteConfig(allowedPaths = Seq(testDir.toString), allowOverwrite = true)
    WriteFileTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val outputPath = testDir.resolve("write-output.txt").toString
          val params     = ujson.Obj("path" -> outputPath, "content" -> "Hello!")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              writeResult => {
                writeResult.bytesWritten shouldBe 6
                writeResult.created shouldBe true

                // Verify content
                Files.readString(Paths.get(outputPath)) shouldBe "Hello!"

                Files.deleteIfExists(Paths.get(outputPath))
              }
            )
        }
      )
  }

  /** Writes `content` to a nested path under a fresh scratch dir; returns the result and the nested target. */
  private def writeWithEncoding(encoding: Option[String], content: String): (Either[String, WriteFileResult], Path) = {
    val dir    = scratchDir()
    val target = dir.resolve("nested").resolve("deeper").resolve("write-encoding.txt")
    val config = WriteConfig(allowedPaths = Seq(dir.toString), allowOverwrite = true)
    val params = ujson.Obj("path" -> target.toString, "content" -> content)
    encoding.foreach(e => params("encoding") = e)
    val result = WriteFileTool
      .createSafe(config)
      .fold(e => fail(s"Tool creation failed: ${e.formatted}"), _.handler(SafeParameterExtractor(params)))
    (result, target)
  }

  private def assertRefusesEncoding(encoding: String): org.scalatest.Assertion = {
    val (result, target) = writeWithEncoding(Some(encoding), "Hello!")
    result shouldBe Left(s"Unsupported encoding: $encoding")
    // Neither the file nor its parent directories were created
    allFiles(scratchRoot) shouldBe List(scratchRoot)
    Files.exists(target.getParent.getParent) shouldBe false
  }

  it should "return an error for an unknown encoding" in {
    assertRefusesEncoding("utf-9")
  }

  it should "return an error for an illegal encoding name" in {
    assertRefusesEncoding("bad name!")
  }

  it should "return an error for an empty encoding name" in {
    assertRefusesEncoding("")
  }

  it should "round-trip non-ASCII text through ReadFileTool as UTF-8 when no encoding is given" in {
    val (result, target) = writeWithEncoding(None, "héllo")
    result.map(_.created) shouldBe Right(true)
    Files.readAllBytes(target) shouldBe "héllo".getBytes(StandardCharsets.UTF_8)
    readPath(target, None).map(_.content) shouldBe Right("héllo")
  }

  it should "deny write to non-allowed paths" in {
    assume(!isWindows, "Unix paths not available on Windows")
    val config = WriteConfig(allowedPaths = Seq("/tmp/specific"))
    WriteFileTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("path" -> "/tmp/other/file.txt", "content" -> "test")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => err should include("Access denied"),
              result => fail(s"Expected Left but got Right: $result")
            )
        }
      )
  }

  it should "support append mode" in {
    val tempFile = testDir.resolve("append-test.txt")
    Files.writeString(tempFile, "Hello")

    val config = WriteConfig(allowedPaths = Seq(testDir.toString), allowOverwrite = true)
    WriteFileTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("path" -> tempFile.toString, "content" -> " World!", "append" -> true)
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              _ => {
                Files.readString(tempFile) shouldBe "Hello World!"

                Files.deleteIfExists(tempFile)
              }
            )
        }
      )
  }

}
