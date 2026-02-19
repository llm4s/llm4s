package org.llm4s.toolapi.builtin

import scala.annotation.nowarn

import org.llm4s.toolapi.SafeParameterExtractor
import org.llm4s.toolapi.builtin.filesystem._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Paths }

class FileSystemToolsSpec extends AnyFlatSpec with Matchers {

  private val isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

  // Use system temp directory for cross-platform compatibility
  private val testDir = {
    val tempRoot = System.getProperty("java.io.tmpdir")
    val dir      = Paths.get(tempRoot, "llm4s-test-" + System.currentTimeMillis())
    Files.createDirectories(dir)
    dir
  }

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

  "ReadFileTool" should "read existing files" in {
    val tempFile = testDir.resolve("read-test.txt")
    Files.writeString(tempFile, "Hello, World!")

    // No blocked paths, only test dir allowed
    val config = FileConfig(allowedPaths = Some(Seq(testDir.toString)), blockedPaths = Seq.empty)
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

                Files.deleteIfExists(tempFile)
              }
            )
        }
      )
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

  // ============ Deprecated API tests ============

  "ReadFileTool.create() (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = ReadFileTool.create()
    tool.name shouldBe "read_file"
  }

  "ReadFileTool.tool (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = ReadFileTool.tool
    tool.name shouldBe "read_file"
  }

  "ListDirectoryTool.create() (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = ListDirectoryTool.create()
    tool.name shouldBe "list_directory"
  }

  "FileInfoTool.create() (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = FileInfoTool.create()
    tool.name shouldBe "file_info"
  }

  "WriteFileTool.create() (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = WriteFileTool.create(WriteConfig(allowedPaths = Seq("/tmp")))
    tool.name shouldBe "write_file"
  }

  "filesystem.readOnlyTools (deprecated)" should "return 3 read-only tools" in {
    @nowarn("cat=deprecation") val tools = readOnlyTools
    tools.size shouldBe 3
    val names = tools.map(_.name).toSet
    names should contain("read_file")
    names should contain("list_directory")
    names should contain("file_info")
  }
}
