package org.llm4s.runner

import org.llm4s.runner.WorkspacePathPolicy.Access
import org.llm4s.shared.WorkspaceSandboxConfig
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.util.Comparator
import scala.util.Try

/**
 * The workspace path policy (#870) as a value: `allowedPaths` and `readOnlyPaths` judged after every `..` and symbolic
 * link is resolved, and each refusal a `Left` naming the refused path, never an exception.
 *
 * Platform-sensitive (separators, links, real paths), so it runs on Windows too: `workspaceRunner` is a whole module of
 * `project/PlatformTests.scala`. A link case is cancelled where the host may not create links (Windows without
 * Developer Mode).
 */
class WorkspacePathPolicySpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var base: Path = _
  private var root: Path = _

  override def beforeEach(): Unit = {
    base = Files.createTempDirectory("path-policy").toRealPath()
    root = Files.createDirectories(base.resolve("ws"))
    Files.createDirectories(root.resolve("src/main"))
    Files.createDirectories(root.resolve("docs"))
    Files.createDirectories(base.resolve("outside"))
    Files.writeString(root.resolve("src/main/A.scala"), "a")
    Files.writeString(root.resolve("docs/readme.md"), "r")
    Files.writeString(base.resolve("outside/secret"), "s"): Unit
  }

  override def afterEach(): Unit =
    Try(Files.walk(base).sorted(Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p): Unit)): Unit

  private def policy(allowed: List[String] = Nil, readOnly: List[String] = Nil) =
    new WorkspacePathPolicy(root, WorkspaceSandboxConfig(allowedPaths = allowed, readOnlyPaths = readOnly))

  /** A symbolic link, or the case cancelled where this host may not make one. */
  private def link(at: Path, target: Path): Unit =
    Try(Files.createSymbolicLink(at, target)).failed.foreach(e => cancel(s"cannot create a symbolic link here: $e"))

  private def code(r: Either[PathRefusal, ?]): Option[String] = r.left.toOption.map(_.code)

  "WorkspacePathPolicy" should "accept a path inside the workspace and return it lexically" in {
    policy().resolvePath("src/main/A.scala", Access.Read) shouldBe Right(root.resolve("src/main/A.scala"))
  }

  it should "refuse a path whose .. leaves the workspace, as a Left naming it" in {
    val r = policy().resolvePath("src/../../outside/secret", Access.Read)
    r.left.toOption.map(x => (x.path, x.code)) shouldBe Some(("src/../../outside/secret", "PATH_ESCAPE_ATTEMPT"))
    r.left.toOption.get.message should include("src/../../outside/secret")
  }

  it should "resolve .. before comparing against allowedPaths" in {
    val p = policy(allowed = List("src"))
    p.resolvePath("src/main/../main/A.scala", Access.Read).isRight shouldBe true
    code(p.resolvePath("src/../docs/readme.md", Access.Read)) shouldBe Some("PATH_NOT_ALLOWED")
    // an allowedPaths entry written with .. names what it resolves to
    policy(allowed = List("docs/../src")).resolvePath("src/main/A.scala", Access.Read).isRight shouldBe true
  }

  it should "not take a sibling that shares a prefix for an allowed path" in {
    Files.createDirectories(root.resolve("src-evil"))
    code(policy(allowed = List("src")).resolvePath("src-evil/x", Access.Write)) shouldBe Some("PATH_NOT_ALLOWED")
  }

  it should "refuse a write under readOnlyPaths, and allow a read there" in {
    val p = policy(readOnly = List("docs"))
    p.resolvePath("docs/readme.md", Access.Read).isRight shouldBe true
    val refused = p.resolvePath("docs/readme.md", Access.Write)
    refused.left.toOption.map(r => (r.path, r.code)) shouldBe Some(("docs/readme.md", "PATH_READ_ONLY"))
    refused.left.toOption.get.message should include("docs/readme.md")
    code(p.resolvePath("docs/sub/new.md", Access.Write)) shouldBe Some("PATH_READ_ONLY")
    code(p.resolvePath("src/../docs/new.md", Access.Write)) shouldBe Some("PATH_READ_ONLY")
    p.resolvePath("src/main/B.scala", Access.Write).isRight shouldBe true
  }

  it should "refuse a write into .git" in {
    code(policy().resolvePath(".git/config", Access.Write)) shouldBe Some("PATH_NOT_ALLOWED")
  }

  it should "judge a link where it points: outside the workspace is refused" in {
    link(root.resolve("out"), base.resolve("outside"))
    code(policy().resolvePath("out/secret", Access.Read)) shouldBe Some("PATH_ESCAPE_ATTEMPT")
  }

  it should "not let a link inside allowedPaths reach a path outside them" in {
    link(root.resolve("src/docs-link"), root.resolve("docs"))
    code(policy(allowed = List("src")).resolvePath("src/docs-link/readme.md", Access.Read)) shouldBe
      Some("PATH_NOT_ALLOWED")
  }

  it should "not let a link outside readOnlyPaths write into one" in {
    link(root.resolve("src/docs-link"), root.resolve("docs"))
    code(policy(readOnly = List("docs")).resolvePath("src/docs-link/readme.md", Access.Write)) shouldBe
      Some("PATH_READ_ONLY")
  }

  it should "judge a dangling link where a write through it would create its target" in {
    link(root.resolve("src/drop"), base.resolve("outside/new-file"))
    code(policy().resolvePath("src/drop", Access.Write)) shouldBe Some("PATH_ESCAPE_ATTEMPT")
  }

  it should "let an entry be judged as itself, so a link out of the workspace can be deleted" in {
    link(root.resolve("src/out"), base.resolve("outside"))
    policy().resolveEntry("src/out", Access.Write) shouldBe Right(root.resolve("src/out"))
  }

  it should "refuse a link loop as a Left, not an exception" in {
    link(root.resolve("a"), root.resolve("b"))
    link(root.resolve("b"), root.resolve("a"))
    code(policy().resolvePath("a/x", Access.Read)) shouldBe Some("PATH_ESCAPE_ATTEMPT")
  }

  it should "refuse a path the platform cannot parse as a Left" in {
    code(policy().lexical("bad\u0000name")) shouldBe Some("PATH_ESCAPE_ATTEMPT")
  }
}
