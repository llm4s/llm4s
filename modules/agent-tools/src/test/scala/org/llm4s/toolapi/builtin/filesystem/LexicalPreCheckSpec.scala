package org.llm4s.toolapi.builtin.filesystem

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, Paths }
import java.util.Locale

/**
 * The path policy decides what it can from a path's spelling before it looks anything up (#3270): resolving a path
 * stats every component, and on Windows stat-ing a UNC path (`\\host\share\x`) opens an SMB session to that host,
 * with the process account's credentials, before any "Access denied". A remote root is refused unless an allowed
 * entry has it, and a path spelled outside every allowed entry is refused unread.
 */
class LexicalPreCheckSpec extends AnyFlatSpec with Matchers {

  private val isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")

  private def newRoot(): Path = Files.createTempDirectory("lexical").toRealPath()

  "PathPolicy.lexicallyRefused" should "refuse a path spelled outside every allowed entry" in {
    val root    = newRoot()
    val entries = PathPolicy.prepare(Some(Seq(root.resolve("data").toString)), Seq.empty)
    PathPolicy.lexicallyRefused(root.resolve("elsewhere/x.txt"), entries) shouldBe true
    PathPolicy.lexicallyRefused(root.resolve("data-secret/x.txt"), entries) shouldBe true
    PathPolicy.lexicallyRefused(root.resolve("data/../elsewhere"), entries) shouldBe true
    PathPolicy.lexicallyRefused(root.resolve("data/x.txt"), entries) shouldBe false
    PathPolicy.resolve(root.resolve("elsewhere/x.txt"), entries) shouldBe None
  }

  it should "accept a spelling inside an allowed entry's resolved form, as well as its configured one" in {
    val root = newRoot()
    Files.createDirectories(root.resolve("real"))
    val linked = scala.util.Try(Files.createSymbolicLink(root.resolve("entry"), root.resolve("real"))).isSuccess
    assume(linked, "symbolic links are not available")
    val entries = PathPolicy.prepare(Some(Seq(root.resolve("entry").toString)), Seq.empty)
    PathPolicy.lexicallyRefused(root.resolve("real/x"), entries) shouldBe false
    PathPolicy.lexicallyRefused(root.resolve("entry/x"), entries) shouldBe false
  }

  it should "refuse a spelling inside a blocked entry, whatever its case" in {
    val root    = newRoot()
    val entries = PathPolicy.prepare(None, Seq(root.resolve("secret").toString))
    PathPolicy.lexicallyRefused(root.resolve("secret/x"), entries) shouldBe true
    PathPolicy.lexicallyRefused(root.resolve("SECRET/x"), entries) shouldBe true
    PathPolicy.lexicallyRefused(root.resolve("open/x"), entries) shouldBe false
  }

  "PathPolicy.isRemoteRoot" should "never see a remote root in a POSIX path" in {
    assume(!isWindows, "POSIX paths")
    PathPolicy.isRemoteRoot(Paths.get("/srv/x")) shouldBe false
    PathPolicy.isRemoteRoot(Paths.get("//host/share/x")) shouldBe false // UnixPath reads it as /host/share/x
  }

  it should "see a UNC root on Windows, and not a drive" in {
    assume(isWindows, "Windows paths")
    PathPolicy.isRemoteRoot(Paths.get("\\\\203.0.113.9\\share\\x")) shouldBe true
    PathPolicy.isRemoteRoot(Paths.get("//203.0.113.9/share/x")) shouldBe true
    PathPolicy.isRemoteRoot(Paths.get("C:\\Windows")) shouldBe false
  }

  "The file tools on Windows" should "refuse a UNC path without contacting its host, under the default config" in {
    assume(isWindows, "UNC paths are a Windows form")
    // 203.0.113.0/24 is TEST-NET-3: nothing answers, so a lookup would wait out an SMB connection timeout
    val unc     = Paths.get("\\\\203.0.113.9\\share\\secret.txt")
    val started = System.nanoTime()
    FileConfig().isPathAllowed(unc) shouldBe false
    FileConfig(allowedPaths = Some(Seq("C:\\agent"))).isPathAllowed(unc) shouldBe false
    PathPolicy.lexicallyRefused(unc, FileConfig().entries) shouldBe true
    (System.nanoTime() - started) should be < 2000000000L
  }
}
