package org.llm4s.toolapi.builtin.filesystem

import org.llm4s.toolapi._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, LinkOption, Path, Paths }
import java.nio.file.attribute.DosFileAttributeView
import java.util.Locale
import scala.util.Try

/**
 * The file tools refuse what no path check can contain, and block more credential files by default (#3272): a hard
 * link planted in an allowed directory, a project's `.env`, the other users' homes; a replace keeps a file's DOS
 * flags and refuses one with a setuid, setgid or sticky bit; descriptions carry no account name.
 */
class HardLinksAndCredentialsSpec extends AnyFlatSpec with Matchers {

  private val isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")

  private def newRoot(): Path = Files.createTempDirectory("hardlinks").toRealPath()

  private def file(path: Path, text: String): Path = {
    Files.createDirectories(path.getParent)
    Files.writeString(path, text)
    path
  }

  private def hardLink(link: Path, target: Path): Unit = {
    Files.createDirectories(link.getParent)
    assume(Try(Files.createLink(link, target)).isSuccess, "hard links cannot be created here")
  }

  private def countsLinks(path: Path): Boolean =
    Try(Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).isSuccess

  private def read(config: FileConfig, path: Path): Either[String, ReadFileResult] =
    ReadFileTool
      .createSafe(config)
      .fold(e => fail(e.formatted), identity)
      .handler(SafeParameterExtractor(ujson.Obj("path" -> path.toString)))
      .left
      .map(_.message)

  private def list(config: FileConfig, path: Path, hidden: Boolean): Either[String, ListDirectoryResult] =
    ListDirectoryTool
      .createSafe(config)
      .fold(e => fail(e.formatted), identity)
      .handler(SafeParameterExtractor(ujson.Obj("path" -> path.toString, "include_hidden" -> hidden)))
      .left
      .map(_.message)

  private def write(config: WriteConfig, path: Path, text: String, append: Boolean): Either[String, WriteFileResult] =
    WriteFileTool
      .createSafe(config)
      .fold(e => fail(e.formatted), identity)
      .handler(SafeParameterExtractor(ujson.Obj("path" -> path.toString, "content" -> text, "append" -> append)))
      .left
      .map(_.message)

  private def allowing(root: Path): FileConfig =
    FileConfig(allowedPaths = Some(Seq(root.toString)), blockedPaths = Seq.empty)

  "read_file" should "refuse a hard link planted in the allowed directory, unless allowHardLinks is set" in {
    val t       = newRoot()
    val outside = file(t.resolve("outside/secret.txt"), "SECRET")
    hardLink(t.resolve("data/planted.txt"), outside)
    assume(countsLinks(outside), "the file system reports no link counts")
    val refused = read(allowing(t.resolve("data")), t.resolve("data/planted.txt"))
    refused.left.toOption.getOrElse(fail(s"expected a refusal, got $refused")) should include("hard links")
    read(allowing(t.resolve("data")).copy(allowHardLinks = true), t.resolve("data/planted.txt"))
      .map(_.content) shouldBe Right("SECRET")
    read(allowing(t.resolve("data")), file(t.resolve("data/own.txt"), "mine")).map(_.content) shouldBe Right("mine")
  }

  "write_file" should "refuse to append through a planted hard link, and leave the file elsewhere as it was" in {
    val t       = newRoot()
    val outside = file(t.resolve("outside/target.txt"), "KEEP")
    hardLink(t.resolve("data/planted.txt"), outside)
    assume(countsLinks(outside), "the file system reports no link counts")
    val config = WriteConfig(allowedPaths = Seq(t.resolve("data").toString), allowOverwrite = true)
    write(config, t.resolve("data/planted.txt"), "-pwned", append = true).left.toOption
      .getOrElse(fail("expected a refusal")) should include("hard links")
    Files.readString(outside) shouldBe "KEEP"
    // a replace writes a new file, so it never changes the file the link shared
    write(config, t.resolve("data/planted.txt"), "new", append = false).isRight shouldBe true
    Files.readString(outside) shouldBe "KEEP"
    Files.readString(t.resolve("data/planted.txt")) shouldBe "new"
  }

  "PathPolicy.hasOtherHardLinks" should "say no for a file with one name, a directory and a missing path" in {
    val t = newRoot()
    PathPolicy.hasOtherHardLinks(file(t.resolve("a.txt"), "a")) shouldBe false
    PathPolicy.hasOtherHardLinks(t) shouldBe false
    PathPolicy.hasOtherHardLinks(t.resolve("missing")) shouldBe false
  }

  "A blocked name" should "block .env and its variants wherever they are, whatever their case" in {
    val t      = newRoot()
    val config = allowing(t)
    Seq(".env", ".env.local", ".ENV.production", ".envrc").foreach { name =>
      val refused = read(config, file(t.resolve(s"project/$name"), "TOKEN=x"))
      withClue(name)(
        refused.left.toOption.getOrElse(fail(s"expected a refusal, got $refused")) should include("Access denied")
      )
    }
    read(config, file(t.resolve(".envs/notes.txt"), "fine")).map(_.content) shouldBe Right("fine")
    read(config, file(t.resolve("project/env.txt"), "fine")).map(_.content) shouldBe Right("fine")
    read(config.copy(blockedNames = Seq.empty), t.resolve("project/.env")).map(_.content) shouldBe Right("TOKEN=x")
  }

  "The default blocklist" should "cover shell histories and more credential files under the home directory" in {
    val paths = FileConfig.defaultBlockedPaths(FileConfig.Platform.Posix, Some("/home/alice"))
    (paths should contain).allOf(
      "/home/alice/.bash_history",
      "/home/alice/.zsh_history",
      "/home/alice/.vault-token",
      "/home/alice/.terraform.d/credentials.tfrc.json",
      "/home/alice/.cargo/credentials.toml",
      "/home/alice/.gradle/gradle.properties",
      "/home/alice/.gem/credentials",
      "/home/alice/.config/op",
      "/home/alice/.mozilla",
      "/home/alice/.config/google-chrome"
    )
    FileConfig.defaultBlockedPaths(FileConfig.Platform.MacOS, Some("/Users/alice")) should contain(
      "/Users/alice/Library/Application Support/Google/Chrome"
    )
    FileConfig.defaultBlockedPaths(FileConfig.Platform.Windows, Some("C:\\Users\\alice")) should contain(
      "C:\\Users\\alice\\AppData\\Local\\Google\\Chrome\\User Data"
    )
  }

  it should "block the other users' home directories, read beside the user's own" in {
    val t     = newRoot()
    val homes = Files.createDirectories(t.resolve("home"))
    Seq("alice", "bob", "carol").foreach(n => Files.createDirectories(homes.resolve(n)))
    Files.writeString(homes.resolve("not-a-home.txt"), "x")
    FileConfig.otherHomes(homes.resolve("alice").toString) shouldBe
      Seq(homes.resolve("bob").toString, homes.resolve("carol").toString)
    // a home that is not in a directory of homes (/root) has no siblings to block
    FileConfig.otherHomes(Files.createDirectories(t.resolve("root")).toString) shouldBe empty
    FileConfig.defaultBlockedPaths(FileConfig.Platform.Posix, Some("/home/alice"), Seq("/home/bob")) should contain(
      "/home/bob"
    )
  }

  "A tool description" should "collapse the other users' homes and write the user's own as ~" in {
    FileConfig.describe(Seq("/etc", "/home/alice/.ssh", "/home/bob", "/home/carol"), Some("/home/alice")) shouldBe
      "/etc, ~/.ssh, /home/* (other users)"
    FileConfig.describe(Seq("C:\\Users\\bob", "C:\\Users\\alice\\.ssh"), Some("C:\\Users\\alice")) shouldBe
      "~\\.ssh, C:\\Users\\* (other users)"
    FileConfig.describe(Seq("/home/alicex/.ssh"), Some("/home/alice")) shouldBe "/home/alicex/.ssh"
  }

  it should "write the home directory as ~ in allowed paths and the working directory too" in {
    val home = FileConfig.userHome.getOrElse(cancel("no user.home"))
    assume(home.length > 3, "a home directory whose name could be told apart")
    val allowed = Seq(Paths.get(home, "project").toString)
    (FileConfig(allowedPaths = Some(allowed)).describedAllowedPaths.getOrElse("") should not).include(home)
    (WriteConfig(allowedPaths = allowed).describedAllowedPaths should not).include(home)
    val description = ReadFileTool.createSafe(FileConfig(allowedPaths = Some(allowed))).fold(_ => "", _.description)
    (description should not).include(home)
  }

  "list_directory" should "not show a staging file a crashed write left, even with hidden files shown" in {
    val t = newRoot()
    file(t.resolve("kept.txt"), "k")
    file(t.resolve(".llm4s-write-123e4567-e89b-12d3-a456-426614174000.tmp"), "partial")
    file(t.resolve(".llm4s-write-notes.tmp"), "a user's file")
    list(allowing(t), t, hidden = true).map(_.entries.map(_.name).sorted) shouldBe
      Right(Seq(".llm4s-write-notes.tmp", "kept.txt"))
  }

  "write_file replace" should "keep the DOS hidden flag" in {
    assume(isWindows, "DOS flags are a Windows attribute")
    val t      = newRoot()
    val target = file(t.resolve("hidden.txt"), "old")
    Files.getFileAttributeView(target, classOf[DosFileAttributeView]).setHidden(true)
    write(WriteConfig(Seq(t.toString), allowOverwrite = true), target, "new", append = false).isRight shouldBe true
    Files.readString(target) shouldBe "new"
    Files.readAttributes(target, classOf[java.nio.file.attribute.DosFileAttributes]).isHidden shouldBe true
  }

  it should "refuse a file with a setgid bit, which a replacement would drop" in {
    assume(!isWindows, "POSIX mode bits")
    val t      = newRoot()
    val target = file(t.resolve("tool.sh"), "old")
    val set    = Try(new ProcessBuilder("chmod", "g+s", target.toString).start().waitFor()).toOption.contains(0)
    assume(set && WriteFileTool.hasSpecialBits(target), "the setgid bit cannot be set here")
    write(WriteConfig(Seq(t.toString), allowOverwrite = true), target, "new", append = false).left.toOption
      .getOrElse(fail("expected a refusal")) should include("setgid")
    Files.readString(target) shouldBe "old"
  }
}
