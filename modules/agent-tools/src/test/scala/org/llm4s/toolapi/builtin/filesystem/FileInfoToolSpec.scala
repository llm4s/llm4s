package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.toolapi.SafeParameterExtractor
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, Paths }
import java.util.Locale
import scala.jdk.CollectionConverters._
import scala.util.Using

class FileInfoToolSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

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

  private def infoFor(
    path: String,
    allowedPaths: Seq[String] = Seq(scratchRoot.toString)
  ): Either[String, FileInfoResult] =
    FileInfoTool
      .createSafe(FileConfig(allowedPaths = Some(allowedPaths), blockedPaths = Seq.empty))
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => tool.handler(SafeParameterExtractor(ujson.Obj("path" -> path)))
      )

  private def fileOfSize(name: String, bytes: Int): Path = {
    val file = scratchDir().resolve(name)
    Files.write(file, new Array[Byte](bytes))
    file
  }

  // sizeHuman must not depend on the JVM's default locale (#2669): these tests set one to prove it.
  private def withLocale[T](locale: Locale)(thunk: => T): T = {
    val previous = Locale.getDefault
    try {
      Locale.setDefault(locale)
      thunk
    } finally Locale.setDefault(previous)
  }

  "FileInfoTool" should "format sub-kilobyte sizes as whole bytes" in withLocale(Locale.US) {
    infoFor(fileOfSize("empty.bin", 0).toString).map(_.sizeHuman) shouldBe Right("0 B")
    infoFor(fileOfSize("almost.bin", 1023).toString).map(_.sizeHuman) shouldBe Right("1023 B")
  }

  it should "format kilobyte sizes with one decimal" in withLocale(Locale.US) {
    infoFor(fileOfSize("one-kb.bin", 1024).toString).map(_.sizeHuman) shouldBe Right("1.0 KB")
    infoFor(fileOfSize("one-half-kb.bin", 1536).toString).map(_.sizeHuman) shouldBe Right("1.5 KB")
  }

  it should "format megabyte sizes with one decimal" in withLocale(Locale.US) {
    infoFor(fileOfSize("one-mb.bin", 1024 * 1024).toString).map(_.sizeHuman) shouldBe Right("1.0 MB")
  }

  it should "format sizes with a '.' decimal separator whatever the default locale (#2669)" in withLocale(
    Locale.GERMANY
  ) {
    infoFor(fileOfSize("german-kb.bin", 1536).toString).map(_.sizeHuman) shouldBe Right("1.5 KB")
    infoFor(fileOfSize("german-mb.bin", 1024 * 1024 + 512 * 1024).toString).map(_.sizeHuman) shouldBe Right("1.5 MB")
  }

  it should "describe the filesystem root instead of throwing (#2670)" in {
    // "/" on Unix, the drive root (e.g. C:\) on Windows: Path.getFileName is null for both
    val root = testDir.toAbsolutePath.getRoot

    infoFor(root.toString, allowedPaths = Seq(root.toString)).fold(
      err => fail(s"Expected Right but got Left: $err"),
      result => {
        result.path shouldBe root.toString
        result.name shouldBe root.toString
        result.exists shouldBe true
        result.isDirectory shouldBe true
        result.extension shouldBe None
      }
    )
  }

  it should "report the extension after the last dot" in {
    infoFor(fileOfSize("notes.txt", 1).toString).map(_.extension) shouldBe Right(Some("txt"))
    infoFor(fileOfSize("archive.tar.gz", 1).toString).map(_.extension) shouldBe Right(Some("gz"))
  }

  it should "report no extension for dotfiles, names without a dot and a trailing dot" in {
    infoFor(fileOfSize(".bashrc", 1).toString).map(_.extension) shouldBe Right(None)
    infoFor(fileOfSize("README", 1).toString).map(_.extension) shouldBe Right(None)
    infoFor(fileOfSize("trailing.", 1).toString).map(_.extension) shouldBe Right(None)
  }

  it should "describe a directory without a size" in {
    val dir = Files.createDirectory(scratchDir().resolve("a-directory"))

    infoFor(dir.toString).fold(
      err => fail(s"Expected Right but got Left: $err"),
      result => {
        result.exists shouldBe true
        result.isDirectory shouldBe true
        result.isFile shouldBe false
        result.size shouldBe 0
      }
    )
  }

  it should "report the extension and a zero size for a missing file" in {
    val missing = scratchDir().resolve("ghost.md")

    infoFor(missing.toString).fold(
      err => fail(s"Expected Right but got Left: $err"),
      result => {
        result.exists shouldBe false
        result.extension shouldBe Some("md")
        result.sizeHuman shouldBe "0 B"
      }
    )
  }

  it should "deny access to a path outside allowedPaths" in {
    val outside = testDir.resolve("outside.txt")
    Files.writeString(outside, "not allowed")
    try
      infoFor(outside.toString) match {
        case Left(err) => err should include("Access denied")
        case Right(_)  => fail("Expected Left for a path outside allowedPaths")
      }
    finally Files.deleteIfExists(outside)
  }

  it should "return an error when the path parameter is missing" in {
    FileInfoTool
      .createSafe(FileConfig(allowedPaths = Some(Seq(scratchRoot.toString)), blockedPaths = Seq.empty))
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool =>
          tool.handler(SafeParameterExtractor(ujson.Obj("not-path" -> "x"))) match {
            case Left(err) => err should include("path")
            case Right(_)  => fail("Expected Left for a missing path parameter")
          }
      )
  }
}
