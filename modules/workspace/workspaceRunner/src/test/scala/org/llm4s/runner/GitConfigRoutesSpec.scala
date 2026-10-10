package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileTime
import java.nio.file.{ Files, Path }
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * A repository's own configuration, attributes and hooks cannot make an allowed `git` command run a program (#1721).
 *
 * Each route is planted directly (as a repository cloned into the workspace would arrive, bypassing the runner's
 * refusal to write `.git`), shown to run its marker program when git is run on the repository directly - so the test
 * is not vacuous - and then shown not to run it through the runner, for every allowed command it applies to. The
 * marker is a script outside the workspace that appends to a file outside it.
 */
class GitConfigRoutesSpec extends AnyFlatSpec with Matchers {
  import GitConfigRoutesSpec._

  private def inRepo[A](f: Repo => A): A = {
    assume(!isWindowsHost, "the routes are planted with a POSIX shell script as the program")
    Using.resource(new Repo)(f)
  }

  /** git run directly on `dir`, isolated from the developer's own configuration but with none of the runner's layers. */
  private def direct(repo: Repo, dir: Path, args: String*): Int = {
    val builder = new ProcessBuilder(("git" +: args).asJava).directory(dir.toFile).redirectErrorStream(true)
    val env     = builder.environment()
    env.keySet.asScala.toList.filter(_.toUpperCase.startsWith("GIT_")).foreach(env.remove)
    env.put("HOME", repo.home.toString)
    env.put("XDG_CONFIG_HOME", repo.home.toString)
    env.put("GIT_CONFIG_NOSYSTEM", "1")
    env.put("GIT_CONFIG_GLOBAL", "/dev/null")
    builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
    val process = builder.start()
    val reader  = new Thread(() => { process.getInputStream.readAllBytes(); () })
    reader.setDaemon(true)
    reader.start()
    if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); -1 }
    else process.exitValue()
  }

  private def setup(repo: Repo, args: String*): Unit =
    withClue(s"git ${args.mkString(" ")}: ")(direct(repo, repo.root, args: _*) shouldBe 0)

  /** The marker program runs when git is run directly: the route is real. */
  private def controlRuns(repo: Repo, dir: Path, args: String*): Unit = {
    repo.clearMarks()
    direct(repo, dir, args: _*)
    withClue(s"direct 'git ${args.mkString(" ")}' should run the planted program: ")(repo.marks should not be empty)
    repo.clearMarks()
  }

  /** Through the runner the marker never runs; the command may succeed, fail, or be refused. */
  private def neverRuns(repo: Repo, commands: String*): Unit =
    commands.foreach { command =>
      repo.clearMarks()
      val outcome = Try(repo.ws.executeCommand(command, None, Some(30.seconds), None))
      withClue(s"'$command' (${outcome.fold(e => e.getMessage, r => s"exit ${r.exitCode}: ${r.stderr}")}) ran: ") {
        repo.marks shouldBe empty
      }
    }

  /** Every read the runner allows that may reach the route. */
  private val reads = Seq(
    "git status",
    "git status --short",
    "git diff",
    "git diff HEAD",
    "git diff --stat",
    "git log -p",
    "git show",
    "git show HEAD:a.txt",
    "git blame a.txt",
    "git grep -n a",
    "git ls-files -m",
    "git ls-files",
    "git ls-tree HEAD",
    "git rev-parse HEAD",
    "git branch -v"
  )

  /** Rewrites `a.txt` with new content of the same size and a later time, so git must read the file to compare it. */
  private def touchA(repo: Repo, content: String = "x\nb\nb\nc\n"): Unit = {
    write(repo.root.resolve("a.txt"), content)
    repo.later(repo.root.resolve("a.txt"))
  }

  // -------------------------------------------------------------------------------------------------------------
  // Routes, one at a time

  "git through the runner" should "not run core.fsmonitor" in inRepo { repo =>
    setup(repo, "config", "core.fsmonitor", s"${repo.marker} fsmonitor")
    touchA(repo)
    controlRuns(repo, repo.root, "status")
    neverRuns(repo, reads: _*)
  }

  it should "not run a hook in .git/hooks or under core.hooksPath (post-index-change)" in inRepo { repo =>
    val hook = repo.root.resolve(".git/hooks/post-index-change")
    Files.createDirectories(hook.getParent)
    Files.copy(repo.marker, hook)
    repo.later(repo.root.resolve("b.txt"))
    controlRuns(repo, repo.root, "status")
    repo.later(repo.root.resolve("b.txt"))
    neverRuns(repo, reads: _*)
    // core.hooksPath naming a directory in the work tree
    Files.delete(hook)
    val hooks = Files.createDirectories(repo.root.resolve("hooks"))
    Files.copy(repo.marker, hooks.resolve("post-index-change"))
    setup(repo, "config", "core.hooksPath", hooks.toString)
    repo.later(repo.root.resolve("b.txt"))
    controlRuns(repo, repo.root, "status")
    repo.later(repo.root.resolve("b.txt"))
    neverRuns(repo, reads: _*)
  }

  it should "not run diff.external or a diff driver's command" in inRepo { repo =>
    setup(repo, "config", "diff.external", s"${repo.marker} external")
    touchA(repo)
    controlRuns(repo, repo.root, "diff")
    neverRuns(repo, reads: _*)
    setup(repo, "config", "--unset", "diff.external")
    write(repo.root.resolve(".gitattributes"), "* diff=evil\n")
    setup(repo, "config", "diff.evil.command", s"${repo.marker} command")
    controlRuns(repo, repo.root, "diff")
    neverRuns(repo, reads: _*)
  }

  it should "not run a textconv driver named in .gitattributes" in inRepo { repo =>
    write(repo.root.resolve(".gitattributes"), "* diff=evil\n")
    setup(repo, "add", ".gitattributes")
    setup(repo, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "attributes")
    setup(repo, "config", "diff.evil.textconv", s"${repo.marker} textconv")
    touchA(repo)
    controlRuns(repo, repo.root, "diff")
    controlRuns(repo, repo.root, "log", "-p")
    controlRuns(repo, repo.root, "show")
    controlRuns(repo, repo.root, "blame", "a.txt")
    neverRuns(repo, reads: _*)
  }

  it should "not run a clean or smudge filter named in .gitattributes or .git/info/attributes" in inRepo { repo =>
    write(repo.root.resolve(".gitattributes"), "* filter=evil\n")
    setup(repo, "config", "filter.evil.clean", s"${repo.marker} clean")
    setup(repo, "config", "filter.evil.smudge", s"${repo.marker} smudge")
    touchA(repo)
    controlRuns(repo, repo.root, "status")
    controlRuns(repo, repo.root, "ls-files", "-m")
    touchA(repo)
    neverRuns(repo, reads: _*)
    // The same driver, named by an upper-case subsection and .git/info/attributes instead
    Files.delete(repo.root.resolve(".gitattributes"))
    Files.createDirectories(repo.root.resolve(".git/info"))
    write(repo.root.resolve(".git/info/attributes"), "* filter=Evil\n")
    setup(repo, "config", "filter.Evil.clean", s"${repo.marker} clean")
    setup(repo, "config", "filter.Evil.required", "true")
    touchA(repo, "y\nb\nb\nc\n")
    controlRuns(repo, repo.root, "status")
    touchA(repo, "z\nb\nb\nc\n")
    neverRuns(repo, reads: _*)
  }

  it should "not run a long-running process filter, even one marked required" in inRepo { repo =>
    write(repo.root.resolve(".gitattributes"), "* filter=evil\n")
    setup(repo, "config", "filter.evil.process", s"${repo.marker} process")
    setup(repo, "config", "filter.evil.required", "true")
    touchA(repo)
    controlRuns(repo, repo.root, "status")
    touchA(repo, "y\nb\nb\nc\n")
    neverRuns(repo, reads: _*)
  }

  it should "not run gpg.program for log.showSignature (a `%G?` format is refused for its '%')" in inRepo { repo =>
    repo.signedCommit()
    setup(repo, "config", "gpg.program", repo.marker.toString)
    setup(repo, "config", "gpg.ssh.program", repo.marker.toString)
    setup(repo, "config", "log.showSignature", "true")
    controlRuns(repo, repo.root, "log", "-1", "signed")
    controlRuns(repo, repo.root, "log", "-1", "--format=%G?", "signed")
    neverRuns(repo, reads.map(_.replace("git log -p", "git log -p signed")) ++ Seq("git show signed"): _*)
    setup(repo, "config", "gpg.format", "ssh")
    neverRuns(repo, "git log -1 signed", "git show signed")
  }

  it should "not fetch a missing object through the repository's transport (a partial clone's ext:: remote)" in inRepo {
    repo =>
      write(repo.root.resolve("g.txt"), "secret\n")
      setup(repo, "add", "g.txt")
      setup(repo, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "g")
      def dropBlob(): Unit = {
        val out = new ProcessBuilder("git", "rev-parse", "HEAD:g.txt").directory(repo.root.toFile).start()
        val id  = new String(out.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
        out.waitFor()
        Files.deleteIfExists(repo.root.resolve(s".git/objects/${id.take(2)}/${id.drop(2)}"))
        ()
      }
      dropBlob()
      setup(repo, "config", "core.repositoryformatversion", "1")
      setup(repo, "config", "extensions.partialClone", "origin")
      setup(repo, "config", "remote.origin.promisor", "true")
      setup(repo, "config", "remote.origin.url", s"ext::${repo.marker} ext %S")
      setup(repo, "config", "protocol.ext.allow", "always")
      neverRuns(repo, "git show HEAD:g.txt", "git log -p", "git diff HEAD~1", "git blame g.txt")
      controlRuns(repo, repo.root, "show", "HEAD:g.txt")
      // An ssh remote with core.sshCommand: no transport is allowed at all
      setup(repo, "config", "remote.origin.url", "ssh://example.invalid/r")
      setup(repo, "config", "core.sshCommand", s"${repo.marker} ssh")
      neverRuns(repo, "git show HEAD:g.txt", "git log -p")
  }

  it should "not start git in a submodule, whose own configuration names its own drivers" in inRepo { repo =>
    val sub = Files.createDirectories(repo.root.resolve("mod"))
    def inSub(args: String*): Unit =
      withClue(s"sub: git ${args.mkString(" ")}: ")(direct(repo, sub, args: _*) shouldBe 0)
    val identity = Seq("-c", "user.name=t", "-c", "user.email=t@example.com")
    inSub("init", "-q")
    write(sub.resolve("s.txt"), "x\n")
    write(sub.resolve(".gitattributes"), "* filter=evil2 diff=evil2\n")
    inSub("add", ".")
    inSub(identity ++ Seq("commit", "-q", "-m", "s"): _*)
    setup(repo, "add", "mod")
    setup(repo, identity ++ Seq("commit", "-q", "-m", "add sub"): _*)
    write(sub.resolve("s.txt"), "y\n")
    inSub(identity ++ Seq("commit", "-q", "-a", "-m", "s2"): _*)
    setup(repo, "add", "mod")
    setup(repo, identity ++ Seq("commit", "-q", "-m", "bump sub"): _*)
    inSub("config", "filter.evil2.clean", s"${repo.marker} subclean")
    inSub("config", "diff.evil2.textconv", s"${repo.marker} subtextconv")
    // Recreated with the same content: the submodule's directory and file change, so git must read the file again
    def recreate(): Unit = {
      Files.delete(sub.resolve("s.txt"))
      write(sub.resolve("s.txt"), "y\n")
      repo.later(sub.resolve("s.txt"))
    }
    recreate()
    controlRuns(repo, repo.root, "status")
    recreate()
    controlRuns(repo, repo.root, "log", "-p", "-1", "--submodule=diff")
    // The superproject asks for submodule diffs and for every submodule to be checked
    setup(repo, "config", "diff.submodule", "diff")
    setup(repo, "config", "submodule.mod.ignore", "none")
    setup(repo, "config", "status.submoduleSummary", "true")
    recreate()
    neverRuns(
      repo,
      reads ++ Seq(
        "git log -p -1 --submodule=diff",
        "git show --submodule=diff",
        "git diff HEAD~1 --submodule=diff"
      ): _*
    )
    // The options that would undo the runner's --ignore-submodules=all are refused
    Seq("git status --ignore-submodules=none", "git diff --ignore-submodules", "git log -p --ignore-sub=none")
      .foreach { command =>
        val ex = the[WorkspaceAgentException] thrownBy repo.ws.executeCommand(command, None, Some(5.seconds), None)
        ex.code shouldBe "ARGUMENT_NOT_ALLOWED"
      }
  }

  it should "not use a bare repository written into the workspace" in inRepo { repo =>
    repo.signedCommit()
    val bare = repo.root.resolve("bare")
    setup(repo, "clone", "-q", "--bare", repo.root.toString, bare.toString)
    direct(repo, bare, "config", "gpg.program", repo.marker.toString) shouldBe 0
    direct(repo, bare, "config", "log.showSignature", "true") shouldBe 0
    controlRuns(repo, bare, "log", "-1", "signed")
    repo.clearMarks()
    val response = repo.ws.executeCommand("git log -1 signed", Some("bare"), Some(30.seconds), None)
    response.exitCode should not be 0
    response.stderr should include("bare")
    repo.marks shouldBe empty
  }

  it should "refuse a repository whose configuration includes another file or defines a hook" in inRepo { repo =>
    val included = repo.root.resolve("included.cfg")
    write(included, s"[filter \"evil\"]\n\tclean = ${repo.marker} clean\n")
    write(repo.root.resolve(".gitattributes"), "* filter=evil\n")
    setup(repo, "config", "include.path", included.toString)
    touchA(repo)
    controlRuns(repo, repo.root, "status")
    reads.foreach { command =>
      val ex = the[WorkspaceAgentException] thrownBy repo.ws.executeCommand(command, None, Some(30.seconds), None)
      withClue(command)(ex.code shouldBe GitSandbox.GitConfigNotAllowed)
    }
    repo.marks shouldBe empty
    setup(repo, "config", "--unset", "include.path")
    setup(repo, "config", "includeIf.gitdir:/.path", included.toString)
    (the[WorkspaceAgentException] thrownBy repo.ws.executeCommand("git status", None, None, None)).code shouldBe
      GitSandbox.GitConfigNotAllowed
    setup(repo, "config", "--unset", "includeIf.gitdir:/.path")
    setup(repo, "config", "hook.evil.command", repo.marker.toString)
    (the[WorkspaceAgentException] thrownBy repo.ws.executeCommand("git status", None, None, None)).code shouldBe
      GitSandbox.GitConfigNotAllowed
    // `git --version` reads no repository
    repo.ws.executeCommand("git --version", None, Some(30.seconds), None).exitCode shouldBe 0
  }

  it should "run nothing in a repository that arrives with every route planted at once" in inRepo { repo =>
    repo.signedCommit()
    write(repo.root.resolve(".gitattributes"), "* filter=evil diff=evil\n*.md filter=other\n")
    Files.createDirectories(repo.root.resolve(".git/info"))
    write(repo.root.resolve(".git/info/attributes"), "*.txt filter=info diff=info\n")
    val hook = repo.root.resolve(".git/hooks/post-index-change")
    Files.createDirectories(hook.getParent)
    Files.copy(repo.marker, hook)
    val m = repo.marker.toString
    Seq(
      "core.fsmonitor"       -> s"$m fsmonitor",
      "diff.external"        -> s"$m external",
      "core.pager"           -> s"$m pager",
      "pager.log"            -> s"$m pagerlog",
      "core.editor"          -> s"$m editor",
      "sequence.editor"      -> s"$m sequence",
      "core.askPass"         -> s"$m askpass",
      "credential.helper"    -> s"!$m credential",
      "core.sshCommand"      -> s"$m ssh",
      "gpg.program"          -> m,
      "log.showSignature"    -> "true",
      "filter.evil.clean"    -> s"$m clean",
      "filter.evil.smudge"   -> s"$m smudge",
      "filter.other.process" -> s"$m process",
      "filter.info.clean"    -> s"$m infoclean",
      "diff.evil.textconv"   -> s"$m textconv",
      "diff.evil.command"    -> s"$m command",
      "diff.info.textconv"   -> s"$m infotextconv",
      "alias.status"         -> s"!$m alias",
      "diff.submodule"       -> "diff"
    ).foreach { case (k, v) => setup(repo, "config", k, v) }
    touchA(repo)
    controlRuns(repo, repo.root, "status")
    controlRuns(repo, repo.root, "diff")
    touchA(repo, "q\nb\nb\nc\n")
    neverRuns(repo, reads ++ reads.map(_.replace("git ", "git --no-optional-locks ")): _*)
    // The ordinary results are still there
    val status = repo.ws.executeCommand("git status --short", None, Some(30.seconds), None)
    status.exitCode shouldBe 0
    status.stdout should include("a.txt")
    val diff = repo.ws.executeCommand("git diff", None, Some(30.seconds), None)
    diff.stdout should include("+q")
  }

  it should "still give ordinary results for a clean repository" in inRepo { repo =>
    touchA(repo)
    def runs(command: String): ExecuteCommandResponse = {
      val r = repo.ws.executeCommand(command, None, Some(30.seconds), None)
      withClue(s"$command: ${r.stderr}")(r.exitCode shouldBe 0)
      r
    }
    runs("git status").stdout should include("a.txt")
    runs("git diff").stdout should include("+x")
    runs("git diff --no-index b.txt b.txt")
    runs("git log --oneline").stdout should include("init")
    runs("git show --stat HEAD").stdout should include("a.txt")
    runs("git blame a.txt").stdout should include("b")
    runs("git grep -n b").stdout should include("b.txt")
    runs("git rev-parse --show-toplevel").stdout.trim should not be empty
    runs("git --no-pager log -1 --oneline").stdout should include("init")
  }

  // -------------------------------------------------------------------------------------------------------------
  // Writes into .git are refused

  "The file operations" should "refuse to write .git or a path inside one, in any spelling or through a link" in
    inRepo { repo =>
      val ws                       = repo.ws
      def refused(f: => Any): Unit = (the[WorkspaceAgentException] thrownBy f).code shouldBe "PATH_NOT_ALLOWED"
      refused(ws.writeFile(".git/config", "[core]\n\tfsmonitor = x\n"))
      refused(ws.writeFile(".git/hooks/post-index-change", "#!/bin/sh\n", createDirectories = Some(true)))
      refused(ws.writeFile("./sub/../.git/info/attributes", "* filter=x\n", createDirectories = Some(true)))
      refused(ws.writeFile(".GIT/config", "x"))
      refused(ws.writeFile(".git./config", "x"))
      refused(ws.writeFile(".g‌it/config", "x"))
      refused(ws.writeFile("nested/.git", "gitdir: /elsewhere\n", createDirectories = Some(true)))
      refused(ws.modifyFile(".git/config", List(InsertOperation("insert", 0, "[core]"))))
      Files.createSymbolicLink(repo.root.resolve("cfg"), repo.root.resolve(".git/config"))
      refused(ws.writeFile("cfg", "x"))
      Files.createSymbolicLink(repo.root.resolve("meta"), repo.root.resolve(".git"))
      refused(ws.writeFile("meta/config", "x"))
      // Names that only start like it are ordinary files
      ws.writeFile(".gitattributes", "*.txt text\n").success shouldBe true
      ws.writeFile(".gitignore", "target/\n").success shouldBe true
      ws.writeFile(".github/ci.yml", "x\n", createDirectories = Some(true)).success shouldBe true
      ws.writeFile("git/config", "x\n", createDirectories = Some(true)).success shouldBe true
      ws.readFile(".git/config").content should include("[core]")
    }

  "The write commands" should "refuse to name .git or a path inside one" in inRepo { repo =>
    val ws = repo.rw
    write(repo.root.resolve("evil.cfg"), "[core]\n\tfsmonitor = x\n")
    Files.createSymbolicLink(repo.root.resolve("meta"), repo.root.resolve(".git"))
    Seq(
      "cp evil.cfg .git/config",
      "cp evil.cfg .GIT/config",
      "cp -t .git evil.cfg",
      "cp evil.cfg meta/config",
      "cp evil.cfg sub/../.git/config",
      "mv evil.cfg .git/config",
      "mv evil.cfg nested/.git",
      "mkdir -p .git/hooks",
      "touch .git/hooks/post-index-change",
      "chmod +x .git/hooks/pre-commit",
      "rm .git/index",
      "rm -rf .git",
      "cp evil.cfg .git./config",
      s"cp evil.cfg ${repo.root.resolve(".git/config")}"
    ).foreach { command =>
      val ex = the[WorkspaceAgentException] thrownBy ws.executeCommand(command, None, Some(5.seconds), None)
      withClue(s"$command -> ${ex.error}")(ex.code shouldBe "ARGUMENT_NOT_ALLOWED")
    }
    (new String(Files.readAllBytes(repo.root.resolve(".git/config")), StandardCharsets.UTF_8) should not)
      .include("fsmonitor")
    // Reading it, and writing names that only start like it, still run
    ws.executeCommand("cat .git/config", None, Some(5.seconds), None).exitCode shouldBe 0
    ws.executeCommand("ls .git", None, Some(5.seconds), None).exitCode shouldBe 0
    ws.executeCommand("cp evil.cfg .gitattributes", None, Some(5.seconds), None).exitCode shouldBe 0
    ws.executeCommand("mkdir .github", None, Some(5.seconds), None).exitCode shouldBe 0
  }
}

/** The parts of [[GitSandbox]] and the `.git` name rule that need no git, on every host (Windows included). */
class GitSandboxUnitSpec extends AnyFlatSpec with Matchers {

  private val emptyDir = Files.createTempDirectory("git-empty-unit")

  "GitSandbox.environment" should "replace the runner's home, configuration and pager with the runner's own" in {
    val env = new java.util.HashMap[String, String]()
    env.put("Home", "/home/agent")
    env.put("XDG_CONFIG_HOME", "/home/agent/.config")
    env.put("pager", "evil")
    env.put("PATH", "/usr/bin")
    GitSandbox.environment(env, emptyDir)
    val windows = System.getProperty("os.name").startsWith("Windows")
    env.asScala.toMap shouldBe Map(
      "PATH"                -> "/usr/bin",
      "HOME"                -> emptyDir.toString,
      "XDG_CONFIG_HOME"     -> emptyDir.toString,
      "PAGER"               -> "cat",
      "GIT_PAGER"           -> "cat",
      "GIT_CONFIG_NOSYSTEM" -> "1",
      "GIT_CONFIG_GLOBAL"   -> (if (windows) "NUL" else "/dev/null"),
      "GIT_ATTR_NOSYSTEM"   -> "1",
      "GIT_TERMINAL_PROMPT" -> "0",
      "GIT_OPTIONAL_LOCKS"  -> "0",
      "GIT_NO_LAZY_FETCH"   -> "1",
      "GIT_ALLOW_PROTOCOL"  -> "llm4s-none"
    )
  }

  "GitSandbox.arguments" should "put the overrides before the subcommand and its options right after it" in {
    GitSandbox.arguments(
      Seq("--no-optional-locks", "log", "-p", "--", "a.txt"),
      Seq("core.fsmonitor" -> "false")
    ) shouldBe
      Seq(
        "--no-pager",
        "-c",
        "core.fsmonitor=false",
        "--no-optional-locks",
        "log",
        "--no-ext-diff",
        "--no-textconv",
        "--ignore-submodules=all",
        "-p",
        "--",
        "a.txt"
      )
    GitSandbox.arguments(Seq("status"), Nil) shouldBe Seq("--no-pager", "status", "--ignore-submodules=all")
    GitSandbox.arguments(Seq("blame", "a.txt"), Nil) shouldBe Seq("--no-pager", "blame", "--no-textconv", "a.txt")
    GitSandbox.arguments(Seq("rev-parse", "HEAD"), Nil) shouldBe Seq("--no-pager", "rev-parse", "HEAD")
    GitSandbox.arguments(Seq("--version"), Nil) shouldBe Seq("--no-pager", "--version")
    GitSandbox.hasSubcommand(Seq("--version")) shouldBe false
    GitSandbox.hasSubcommand(Seq("--no-pager", "status")) shouldBe true
  }

  it should "switch off every route that does not depend on a driver name" in {
    val keys = GitSandbox.fixedOverrides(emptyDir).toMap
    keys("core.fsmonitor") shouldBe "false"
    keys("core.hooksPath") shouldBe emptyDir.toString
    keys("diff.external") shouldBe ""
    keys("log.showSignature") shouldBe "false"
    Seq("gpg.program", "gpg.openpgp.program", "gpg.x509.program", "gpg.ssh.program").foreach(
      keys(_) shouldBe emptyDir.toString
    )
    keys("diff.ignoreSubmodules") shouldBe "all"
    keys("safe.bareRepository") shouldBe "explicit"
  }

  private def listing(entries: (String, String)*): String =
    entries.map { case (scope, entry) => s"$scope\u0000$entry\u0000" }.mkString

  "GitSandbox.driverOverrides" should "blank every filter and diff driver the configuration defines" in {
    val out = GitSandbox.driverOverrides(
      listing(
        "local"   -> "core.bare\nfalse",
        "local"   -> "filter.Evil.clean\n/x clean",
        "local"   -> "filter.evil.smudge\n/x smudge",
        "local"   -> "filter.a.b.process\n/x",
        "local"   -> "diff.evil.textconv\n/x",
        "local"   -> "diff.other.command\n/x",
        "local"   -> "diff.python.xfuncname\n^def",
        "local"   -> "diff.external\n/x",
        "local"   -> "filter.flag",
        "command" -> "filter.ours.clean\n"
      )
    )
    out.map(_.toSet) shouldBe Right(
      Set(
        "filter.Evil.clean"    -> "",
        "filter.Evil.smudge"   -> "",
        "filter.Evil.process"  -> "",
        "filter.Evil.required" -> "false",
        "filter.evil.clean"    -> "",
        "filter.evil.smudge"   -> "",
        "filter.evil.process"  -> "",
        "filter.evil.required" -> "false",
        "filter.a.b.clean"     -> "",
        "filter.a.b.smudge"    -> "",
        "filter.a.b.process"   -> "",
        "filter.a.b.required"  -> "false",
        "diff.evil.textconv"   -> "",
        "diff.evil.command"    -> "",
        "diff.other.textconv"  -> "",
        "diff.other.command"   -> ""
      )
    )
    GitSandbox.driverOverrides("") shouldBe Right(Nil)
  }

  it should "refuse what the overrides cannot cover" in {
    Seq(
      "include.path\n/w/x.cfg",
      "includeIf.gitdir:/w/.path\n/w/x.cfg",
      "hook.evil.command\n/x",
      "filter.a=b.clean\n/x",
      "diff.a\"b.textconv\n/x",
      "filter.a\tb.clean\n/x"
    ).foreach { entry =>
      withClue(entry)(
        GitSandbox.driverOverrides(listing("local" -> entry)).left.map(_.code) shouldBe
          Left(GitSandbox.GitConfigNotAllowed)
      )
    }
  }

  "Writes into .git" should "be refused on every host, by the file operations and Windows' copy and move" in {
    val root = Files.createTempDirectory("git-meta-unit")
    Files.createDirectories(root.resolve(".git"))
    Files.write(root.resolve("x.cfg"), "x".getBytes(StandardCharsets.UTF_8))
    val windowsHost = System.getProperty("os.name").startsWith("Windows")
    val ws = new WorkspaceAgentInterfaceImpl(root.toString, windowsHost, Some(WorkspaceSandboxConfig.Permissive))
    Seq(".git/config", ".GIT/config", ".git/hooks/post-index-change", "a/.git").foreach { path =>
      val ex = the[WorkspaceAgentException] thrownBy ws.writeFile(path, "x", createDirectories = Some(true))
      withClue(path)(ex.code shouldBe "PATH_NOT_ALLOWED")
    }
    val windows = new WorkspaceAgentInterfaceImpl(root.toString, true, Some(WorkspaceSandboxConfig.Permissive))
    Seq("copy x.cfg '.git\\config'", "move x.cfg '.GIT\\config'", "copy x.cfg .git/config", "copy x.cfg .git").foreach {
      command =>
        val ex = the[WorkspaceAgentException] thrownBy windows.executeCommand(command, None, Some(5.seconds), None)
        withClue(s"$command: ${ex.error}")(ex.code shouldBe "ARGUMENT_NOT_ALLOWED")
    }
    Files.exists(root.resolve(".git/config")) shouldBe false
  }

  "CommandPolicy.isGitMetadataName" should "match every spelling a file system may take for .git" in {
    Seq(
      ".git",
      ".GIT",
      ".Git",
      ".git.",
      ".git ",
      ".git. .",
      ".g‌it",
      "﻿.git",
      ".git::$INDEX_ALLOCATION",
      "GIT~1",
      "git~12"
    )
      .foreach(name => withClue(name)(CommandPolicy.isGitMetadataName(name) shouldBe true))
    Seq(".gitignore", ".gitattributes", ".github", "git", ".gi", "x.git", ".git-x", "git~x")
      .foreach(name => withClue(name)(CommandPolicy.isGitMetadataName(name) shouldBe false))
  }
}

object GitConfigRoutesSpec {

  val isWindowsHost: Boolean = System.getProperty("os.name").startsWith("Windows")

  def write(p: Path, s: String): Unit = { Files.write(p, s.getBytes(StandardCharsets.UTF_8)); () }

  /**
   * A repository in `parent/workspace` with one commit of `a.txt`, `b.txt` and `sub/x.txt`; a marker script in
   * `parent` that appends its arguments to `parent/marks`; an empty `parent/home` for the direct runs.
   */
  final class Repo extends AutoCloseable {
    val parent: Path = Files.createTempDirectory("ws-git-routes").toRealPath()
    val root: Path   = Files.createDirectory(parent.resolve("workspace"))
    val home: Path   = Files.createDirectory(parent.resolve("home"))
    val marksFile    = parent.resolve("marks")
    val marker: Path = parent.resolve("marker.sh")
    write(marker, s"#!/bin/sh\necho \"$$0 $$*\" >> '$marksFile'\nexit 0\n")
    marker.toFile.setExecutable(true)
    write(root.resolve("a.txt"), "a\nb\nb\nc\n")
    write(root.resolve("b.txt"), "b\n")
    Files.createDirectory(root.resolve("sub"))
    write(root.resolve("sub/x.txt"), "x\n")
    run(root, "init", "-q")
    run(root, "add", ".")
    run(root, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "init")

    val ws = new WorkspaceAgentInterfaceImpl(
      root.toString,
      isWindowsHost,
      Some(WorkspaceSandboxConfig(allowedCommands = WorkspaceSandboxConfig.ReadOnlyCommands))
    )
    val rw = new WorkspaceAgentInterfaceImpl(root.toString, isWindowsHost, Some(WorkspaceSandboxConfig.Permissive))

    private var tick = 10L

    /** Moves `p`'s modification time forward, so git must look at it again. */
    def later(p: Path): Unit = {
      tick += 10
      Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() + tick * 1000)); ()
    }

    def marks: String = Try(new String(Files.readAllBytes(marksFile), StandardCharsets.UTF_8)).getOrElse("")

    def clearMarks(): Unit = { Files.deleteIfExists(marksFile); () }

    /** A commit `signed` carrying a (fake) PGP signature, so log and show ask gpg to verify it. */
    def signedCommit(): Unit = {
      val tree = output(root, "rev-parse", "HEAD^{tree}")
      val body =
        s"tree $tree\nauthor a <a@b> 0 +0000\ncommitter a <a@b> 0 +0000\ngpgsig -----BEGIN PGP SIGNATURE-----\n \n abc\n" +
          " -----END PGP SIGNATURE-----\n\nsigned\n"
      val file = parent.resolve("commit.txt")
      write(file, body)
      val id = output(root, "hash-object", "-t", "commit", "-w", file.toString)
      run(root, "update-ref", "refs/heads/signed", id)
    }

    private def process(dir: Path, args: Seq[String]): Process = {
      val b   = new ProcessBuilder(("git" +: args).asJava).directory(dir.toFile)
      val env = b.environment()
      env.keySet.asScala.toList.filter(_.toUpperCase.startsWith("GIT_")).foreach(env.remove)
      env.put("HOME", home.toString)
      env.put("GIT_CONFIG_NOSYSTEM", "1")
      env.put("GIT_CONFIG_GLOBAL", "/dev/null")
      b.redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }

    private def output(dir: Path, args: String*): String = {
      val p   = process(dir, args)
      val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      require(p.waitFor() == 0, s"git ${args.mkString(" ")} failed")
      out
    }

    private def run(dir: Path, args: String*): Unit = { output(dir, args: _*); () }

    override def close(): Unit = {
      def delete(p: Path): Unit = {
        if (Files.isDirectory(p) && !Files.isSymbolicLink(p))
          Using.resource(Files.list(p))(_.iterator().asScala.foreach(delete))
        Files.deleteIfExists(p)
      }
      delete(parent)
    }
  }
}
