package org.llm4s.runner

import org.llm4s.shared.WorkspaceSandboxConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, Paths }

class CommandConfinementSpec extends AnyFlatSpec with Matchers {
  import CommandConfinement.{ Grant, Rule }

  private def tree(paths: String*): Path = {
    val root = Files.createTempDirectory("confine").toRealPath()
    paths.foreach { p =>
      val target = root.resolve(p)
      if (p.endsWith("/")) Files.createDirectories(target)
      else { Files.createDirectories(target.getParent); Files.writeString(target, "x") }
    }
    root
  }

  private def cfg(allowed: List[String] = Nil, ro: List[String] = Nil) =
    WorkspaceSandboxConfig.Permissive.copy(allowedPaths = allowed, readOnlyPaths = ro)

  "workspaceRules" should "grant the whole root when nothing is restricted" in {
    val root = tree()
    CommandConfinement.workspaceRules(cfg(), root) shouldBe List(Rule(Grant.ReadWrite, root))
  }

  it should "carve a read-only directory out of the root" in {
    val root = tree("ro/", "src/", "a.txt", ".git/")
    CommandConfinement.workspaceRules(cfg(ro = List("ro")), root).toSet shouldBe Set(
      Rule(Grant.ReadOnly, root),
      Rule(Grant.ReadOnly, root.resolve("ro")),
      Rule(Grant.ReadWrite, root.resolve("src")),
      Rule(Grant.ReadWrite, root.resolve(".git")),
      Rule(Grant.WriteFile, root.resolve("a.txt"))
    )
  }

  it should "carve a nested read-only path" in {
    val root = tree("a/b/", "a/c/", "a/f.txt", "d/")
    CommandConfinement.workspaceRules(cfg(ro = List("a/b")), root).toSet shouldBe Set(
      Rule(Grant.ReadOnly, root),
      Rule(Grant.ReadWrite, root.resolve("d")),
      Rule(Grant.ReadOnly, root.resolve("a")),
      Rule(Grant.ReadOnly, root.resolve("a/b")),
      Rule(Grant.ReadWrite, root.resolve("a/c")),
      Rule(Grant.WriteFile, root.resolve("a/f.txt"))
    )
  }

  it should "grant only allowedPaths when given" in {
    val root = tree("src/", "docs/", "secret/")
    CommandConfinement.workspaceRules(cfg(allowed = List("src", "docs")), root).toSet shouldBe Set(
      Rule(Grant.ReadWrite, root.resolve("src")),
      Rule(Grant.ReadWrite, root.resolve("docs"))
    )
  }

  it should "make a path both allowed and read-only read-only" in {
    val root = tree("src/")
    CommandConfinement.workspaceRules(cfg(allowed = List("src"), ro = List("src")), root) shouldBe
      List(Rule(Grant.ReadOnly, root.resolve("src")))
  }

  it should "make an allowed path inside a read-only root read-only" in {
    val root = tree("src/")
    CommandConfinement.workspaceRules(cfg(allowed = List("src"), ro = List(".")), root) shouldBe
      List(Rule(Grant.ReadOnly, root.resolve("src")))
  }

  it should "treat a missing allowed path as no grant" in {
    val root = tree()
    CommandConfinement.workspaceRules(cfg(allowed = List("nope")), root) shouldBe Nil
  }

  it should "carve a missing read-only path to its deepest existing ancestor, so it cannot be created" in {
    val root = tree("ro/a", "ro/sub/", "src/", "b")
    CommandConfinement.workspaceRules(cfg(ro = List("ro/new")), root).toSet shouldBe Set(
      Rule(Grant.ReadOnly, root),
      Rule(Grant.ReadWrite, root.resolve("src")),
      Rule(Grant.WriteFile, root.resolve("b")),
      Rule(Grant.ReadOnly, root.resolve("ro")),
      Rule(Grant.ReadWrite, root.resolve("ro/sub")),
      Rule(Grant.WriteFile, root.resolve("ro/a"))
    )
    // several missing levels: the deepest existing one is the root
    CommandConfinement.workspaceRules(cfg(ro = List("later/deeper")), root) should contain(Rule(Grant.ReadOnly, root))
    CommandConfinement.workspaceRules(cfg(ro = List("later/deeper")), root) should not contain
      Rule(Grant.ReadWrite, root)
  }

  it should "give a dangling link named by a missing read-only path no rule, and its directory read only" in {
    val root = tree("ro/")
    Files.createSymbolicLink(root.resolve("ro/l"), root.resolve("nowhere"))
    CommandConfinement.workspaceRules(cfg(ro = List("ro/l")), root).toSet shouldBe Set(
      Rule(Grant.ReadOnly, root),
      Rule(Grant.ReadOnly, root.resolve("ro"))
    )
  }

  it should "leave a file where a missing read-only path's directory would be writable" in {
    val root = tree("f")
    CommandConfinement.workspaceRules(cfg(ro = List("f/x")), root).toSet shouldBe Set(
      Rule(Grant.ReadOnly, root),
      Rule(Grant.WriteFile, root.resolve("f"))
    )
  }

  it should "give a symlink child of a walked directory no rule" in {
    val root = tree("ro/")
    Files.createSymbolicLink(root.resolve("l"), root.resolve("ro"))
    val rules = CommandConfinement.workspaceRules(cfg(ro = List("ro")), root)
    rules.map(_.path) should not contain root.resolve("l")
  }

  it should "bind the target of a read-only symlink" in {
    val root = tree("ro/")
    Files.createSymbolicLink(root.resolve("ro-link"), root.resolve("ro"))
    CommandConfinement.workspaceRules(cfg(ro = List("ro-link")), root) should contain(
      Rule(Grant.ReadOnly, root.resolve("ro"))
    )
  }

  it should "give no rule outside the root" in {
    val root = tree("src/")
    Files.createDirectories(root.getParent.resolve("x"))
    CommandConfinement.workspaceRules(cfg(allowed = List("../x")), root).forall(_.path.startsWith(root)) shouldBe true
    CommandConfinement.workspaceRules(cfg(ro = List("../x")), root).forall(_.path.startsWith(root)) shouldBe true
  }

  "helperArgs" should "list the helper, status file, grants, --no-net and --" in {
    val rules = List(Rule(Grant.ReadOnly, Paths.get("/a")), Rule(Grant.ReadWrite, Paths.get("/b")))
    CommandConfinement.helperArgs(Paths.get("/h"), Paths.get("/s/status"), rules, noNetwork = true) shouldBe
      List("/h", "--status", "/s/status", "--ro", "/a", "--rw", "/b", "--no-net", "--")
    CommandConfinement.helperArgs(Paths.get("/h"), Paths.get("/s/status"), rules, noNetwork = false) should not contain
      "--no-net"
  }

  "systemRules" should "grant home and /dev/null, and only paths that exist" in {
    val home  = tree()
    val root  = tree()
    val rules = CommandConfinement.systemRules(home, None, root, sharedTmp = false)
    rules should contain(Rule(Grant.ReadWriteExec, home))
    if (Files.exists(Paths.get("/dev/null")))
      rules should contain(Rule(Grant.ReadWrite, Paths.get("/dev/null").toRealPath()))
    rules.forall(r => Files.exists(r.path)) shouldBe true
  }

  it should "drop a grant on a directory that contains the workspace" in {
    val root = tree()
    CommandConfinement
      .systemRules(root.getParent, None, root, sharedTmp = false)
      .map(_.path) should not contain root.getParent
  }

  it should "drop a grant on a directory inside the workspace" in {
    val root = tree("h/")
    CommandConfinement.systemRules(root.resolve("h"), None, root, sharedTmp = false).map(_.path) should not contain root
      .resolve("h")
  }

  it should "grant /tmp read-write only when the runner's attach mechanism is off (sharedTmp)" in {
    assume(Files.isDirectory(Paths.get("/tmp")))
    assume(Files.isDirectory(Confiner.ScratchBase))
    val tmp  = Paths.get("/tmp").toRealPath()
    val root = Files.createTempDirectory(Confiner.ScratchBase, "confine-outside-tmp").toRealPath() // never in /tmp
    CommandConfinement.systemRules(tree(), None, root, sharedTmp = true) should contain(Rule(Grant.ReadWrite, tmp))
    CommandConfinement.systemRules(tree(), None, root, sharedTmp = false).map(_.path) should not contain tmp
    Files.delete(root)
  }

  it should "drop the /tmp grant when the workspace lies inside /tmp" in {
    assume(Files.isDirectory(Paths.get("/tmp")))
    val root = Files.createTempDirectory(Paths.get("/tmp"), "confine-in-tmp").toRealPath()
    CommandConfinement.systemRules(tree(), None, root, sharedTmp = true).map(_.path) should not contain
      Paths.get("/tmp").toRealPath()
    Files.delete(root)
  }

  "Confiner.scratchParent" should "be made under its base, owner-only" in {
    val base = tree()
    val dir  = Confiner.scratchParent(base, tree()).get
    dir.getParent shouldBe base
    if (!System.getProperty("os.name", "").startsWith("Windows"))
      java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe "rwx------"
  }

  it should "fail, leaving nothing, when it would lie inside the workspace or cannot be made" in {
    val root = tree()
    Confiner.scratchParent(root, root).isFailure shouldBe true
    Files.list(root).count() shouldBe 0
    Confiner.scratchParent(root.resolve("missing"), tree()).isFailure shouldBe true
  }
}
