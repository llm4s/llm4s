package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, LinkOption, Path, Paths }
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * An allowlisted program may not write, delete, run another program or leave the workspace through its arguments
 * or environment (#1715). Each refused form below ran on the previous runner, which checked only the executable
 * name and shell metacharacters; the controls are the ordinary read-only uses that must keep working.
 *
 * Only the public `executeCommand` is used, so the same spec run against the previous runner shows every refused
 * form being executed.
 */
class WorkspaceCommandArgumentsSpec extends AnyFlatSpec with Matchers {
  import WorkspaceCommandArgumentsSpec._

  /** A link in the workspace, or a cancelled test where links cannot be made (Windows without the privilege). */
  private def link(fx: Fixture, name: String, target: Path): Path = {
    val made = fx.link(name, target)
    assume(made.isSuccess, s"cannot create a symbolic link here: ${made.failed.map(_.getMessage).getOrElse("")}")
    made.get
  }

  private def inWorkspace[A](f: Fixture => A): A = Using.resource(new Fixture)(f)

  private def refuses(
    ws: WorkspaceAgentInterfaceImpl,
    command: String,
    code: String,
    workingDirectory: Option[String] = None,
    environment: Option[Map[String, String]] = None
  ): WorkspaceAgentException = refusesWithAny(ws, command, Set(code), workingDirectory, environment)

  private def refusesWithAny(
    ws: WorkspaceAgentInterfaceImpl,
    command: String,
    codes: Set[String],
    workingDirectory: Option[String] = None,
    environment: Option[Map[String, String]] = None
  ): WorkspaceAgentException = {
    val ex = the[WorkspaceAgentException] thrownBy
      ws.executeCommand(command, workingDirectory, Some(5.seconds), environment)
    withClue(s"'$command' -> ${ex.code}: ${ex.error}\n") {
      codes should contain(ex.code)
    }
    ex
  }

  /** Refused by the policy or failing to start, but never an exception other than [[WorkspaceAgentException]]. */
  private def neverThrowsRaw(ws: WorkspaceAgentInterfaceImpl, command: String, workingDirectory: Option[String]): Unit =
    Try(ws.executeCommand(command, workingDirectory, Some(5.seconds), None)).failed.toOption.foreach { e =>
      withClue(s"'${command.replace("\u0000", "\\0")}' in ${workingDirectory.map(_.replace("\u0000", "\\0"))}: ") {
        e shouldBe a[WorkspaceAgentException]
      }
    }

  /** Runs on a Unix host (the programs are not on a Windows runner's PATH) and must succeed. */
  private def runs(
    ws: WorkspaceAgentInterfaceImpl,
    command: String,
    environment: Option[Map[String, String]] = None,
    workingDirectory: Option[String] = None
  ): ExecuteCommandResponse = {
    assume(!isWindowsHost, "the POSIX programs are not on a Windows runner's PATH")
    val response = ws.executeCommand(command, workingDirectory, Some(30.seconds), environment)
    withClue(s"'$command' stderr: ${response.stderr}\n") {
      response.exitCode shouldBe 0
    }
    response
  }

  /** Not refused by the policy; the program itself may still fail (an option only one platform's version has). */
  private def passesPolicy(ws: WorkspaceAgentInterfaceImpl, command: String): Unit = {
    val outcome = Try(ws.executeCommand(command, None, Some(30.seconds), None)).failed.toOption
    outcome.collect { case e: WorkspaceAgentException => e }.foreach { e =>
      withClue(s"'$command' -> ${e.code}: ${e.error}\n")(PolicyCodes should not contain e.code)
    }
  }

  /** A real repository in the workspace, made directly rather than through the sandbox. */
  private def gitRepo(fx: Fixture): Unit = {
    assume(!isWindowsHost, "the git controls run on a Unix host")
    def git(args: String*): Unit = {
      val p = new ProcessBuilder(("git" +: args).asJava).directory(fx.root.toFile).redirectErrorStream(true).start()
      p.getInputStream.readAllBytes()
      p.waitFor() shouldBe 0
    }
    git("init", "-q")
    git("-c", "user.name=t", "-c", "user.email=t@example.com", "add", ".")
    git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "init")
    write(fx.root.resolve("a.txt"), "changed\n")
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Controls: ordinary read-only use keeps working

  "An allowlisted command" should "still run its ordinary read-only forms" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly)
    runs(ws, "ls -la").stdout should include("a.txt")
    runs(ws, "cat a.txt").stdout should include("b")
    runs(ws, "grep -rn object .").stdout should include("Main.scala")
    runs(ws, "find . -name '*.scala'").stdout should include("Main.scala")
    runs(ws, "find . -type f -name a.txt -print").stdout should include("a.txt")
    runs(ws, "head -n 2 a.txt").stdout shouldBe "b\na\n"
    runs(ws, "tail -n 1 a.txt").stdout shouldBe "c\n"
    runs(ws, "wc -l a.txt").stdout should include("4")
    runs(ws, "sort a.txt").stdout shouldBe "a\na\nb\nc\n"
    runs(ws, "sort -r -t , -k 1 a.txt").stdout shouldBe "c\nb\na\na\n"
    runs(ws, "uniq a.txt").stdout shouldBe "b\na\nc\n"
    runs(ws, "uniq -c a.txt").stdout should include("2 a")
    runs(ws, "uniq -s 0 a.txt").stdout shouldBe "b\na\nc\n"
    runs(ws, "diff a.txt b.txt").stdout shouldBe ""
    runs(ws, "ls sub").stdout should include("Main.scala")
    runs(ws, "ls -R .").stdout should include("Main.scala")
    runs(ws, "cat sub/../a.txt").stdout should include("b")
    runs(ws, "pwd")
    runs(ws, "whoami")
    runs(ws, "echo /etc/passwd ../x").stdout should include("/etc/passwd")
  }

  it should "still accept an absolute path inside the workspace and a link that stays inside it" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly)
    runs(ws, s"cat '${fx.root.resolve("a.txt")}'").stdout should include("b")
    link(fx, "inner", fx.root.resolve("sub"))
    runs(ws, "cat inner/Main.scala").stdout should include("object Main")
  }

  it should "still run read-only git" in inWorkspace { fx =>
    gitRepo(fx)
    val ws = fx.interface(ReadOnly)
    runs(ws, "git status").stdout should include("a.txt")
    runs(ws, "git --no-pager log --oneline").stdout should include("init")
    runs(ws, "git diff").stdout should include("changed")
    runs(ws, "git diff --stat HEAD").stdout should include("a.txt")
    runs(ws, "git diff --text -- a.txt").stdout should include("changed")
    runs(ws, "git show --stat HEAD").stdout should include("init")
    runs(ws, "git ls-files").stdout should include("victim.txt")
    runs(ws, "git grep -n object").stdout should include("Main.scala")
    runs(ws, "git blame b.txt").stdout should include("b")
    runs(ws, "git rev-parse --abbrev-ref HEAD")
    runs(ws, "git branch").stdout should not be empty
    runs(ws, "git branch -a -v")
    runs(ws, "git branch --list 'ma*'")
    runs(ws, "git log HEAD~0..HEAD")
    runs(ws, "git --version").stdout should include("git")
  }

  it should "still allow locale variables in the environment" in inWorkspace { fx =>
    runs(fx.interface(ReadOnly), "ls", environment = Some(Map("LANG" -> "C", "LC_ALL" -> "C", "TZ" -> "UTC")))
  }

  it should "still let the read-write list write inside the workspace" in inWorkspace { fx =>
    val ws = fx.interface(ReadWrite)
    runs(ws, "cp a.txt copy.txt")
    runs(ws, "mkdir -p made/deeper")
    runs(ws, "mv copy.txt made/moved.txt")
    runs(ws, "touch made/new.txt")
    runs(ws, "rm made/new.txt")
    Files.exists(fx.root.resolve("made").resolve("moved.txt")) shouldBe true
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Refused options

  refusedOptions.foreach { command =>
    it should s"refuse `$command` (ARGUMENT_NOT_ALLOWED)" in inWorkspace { fx =>
      refuses(fx.interface(ReadOnly), command, ArgumentNotAllowed)
      Files.exists(fx.root.resolve("victim.txt")) shouldBe true
      Files.exists(fx.root.resolve("out.txt")) shouldBe false
    }
  }

  it should "name the refused argument and the reason" in inWorkspace { fx =>
    val ex = refuses(fx.interface(ReadOnly), "find . -delete", ArgumentNotAllowed)
    ex.error should include("-delete")
    ex.error should include("find")
  }

  it should "apply the same option rules to the read-write list" in inWorkspace { fx =>
    val ws = fx.interface(ReadWrite)
    refuses(ws, "find . -exec true {} +", ArgumentNotAllowed)
    refuses(ws, "git -c alias.x=!true x", ArgumentNotAllowed)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Refused paths

  refusedPaths.foreach { command =>
    it should s"refuse `$command` (PATH_ESCAPE_ATTEMPT)" in inWorkspace { fx =>
      // A path attached to a short option (`-f'{out}'`) is scanned as an option cluster first, so a refused letter
      // in the temporary directory's name (the `R` of Windows' `RUNNER~1`) refuses it as an option instead.
      val codes = if (command.contains(" -f'{out}")) Set(PathEscape, ArgumentNotAllowed) else Set(PathEscape)
      refusesWithAny(fx.interface(ReadOnly), fx.expand(command), codes)
    }
  }

  it should "refuse a path through a link that leads out of the workspace" in inWorkspace { fx =>
    link(fx, "escape", fx.outside)
    val ws = fx.interface(ReadOnly)
    refuses(ws, "cat escape/secret.txt", PathEscape)
    refuses(ws, "ls escape", PathEscape)
    refuses(ws, "grep -r secret escape", PathEscape)
  }

  it should "follow a link before '..', as the kernel does" in inWorkspace { fx =>
    // `deep` -> parent/outside/inner, so `deep/..` is parent/outside, not the workspace.
    Files.createDirectory(fx.outside.resolve("inner"))
    link(fx, "deep", fx.outside.resolve("inner"))
    refuses(fx.interface(ReadOnly), "cat deep/../secret.txt", PathEscape)
  }

  it should "refuse a '..' after a link that leaves the workspace when '..' is removed as text, as Windows does" in
    inWorkspace { fx =>
      // `l` -> a/b/c. Physically `l/../../outside` is a/outside, inside; Win32 removes `..` as text first, so
      // there `l/..` is the workspace and `l/../../outside/secret.txt` the file outside.
      Files.createDirectories(fx.root.resolve("a").resolve("b").resolve("c"))
      link(fx, "l", fx.root.resolve("a").resolve("b").resolve("c"))
      Seq(false, true).foreach { windows =>
        val ws = fx.interface(ReadOnly, windows)
        refuses(ws, "cat l/../../outside/secret.txt", PathEscape)
        refuses(ws, "cat l/../../../outside", PathEscape)
        refuses(ws, "grep -r secret l/../..", PathEscape)
        if (isWindowsHost) { // `\` separates components only on Windows
          refuses(ws, "type 'l\\..\\..\\outside\\secret.txt'", PathEscape)
          refuses(ws, "type 'l\\..\\..\\..\\outside'", PathEscape)
        }
      }
      refuses(fx.interface(ReadWrite), "cp a.txt l/../../outside/c.txt", PathEscape)
      Files.exists(fx.outside.resolve("c.txt")) shouldBe false
      // Both readings inside: physically a/b, textually the workspace.
      passesPolicy(fx.interface(ReadOnly), "ls l/..")
      passesPolicy(fx.interface(ReadOnly), "ls l/../c")
    }

  it should "refuse a working directory that is a link out of the workspace" in inWorkspace { fx =>
    link(fx, "escape", fx.outside)
    refuses(fx.interface(ReadOnly), "ls", PathEscape, workingDirectory = Some("escape"))
  }

  it should "refuse writes outside the workspace under the read-write list" in inWorkspace { fx =>
    val ws = fx.interface(ReadWrite)
    refuses(ws, fx.expand("cp a.txt '{out}/copied.txt'"), PathEscape)
    refuses(ws, "cp a.txt ../outside/copied.txt", PathEscape)
    refuses(ws, fx.expand("cp --target-directory='{out}' a.txt"), PathEscape)
    refuses(ws, "mv victim.txt ../outside/moved.txt", PathEscape)
    refuses(ws, "touch ../outside/touched.txt", PathEscape)
    refuses(ws, "mkdir -p made/../../outside/made", PathEscape)
    refuses(ws, "rm -f ../outside/secret.txt", PathEscape)
    refuses(ws, "chmod 000 ../outside/secret.txt", PathEscape)
    Files.exists(fx.outside.resolve("copied.txt")) shouldBe false
    Files.exists(fx.outside.resolve("secret.txt")) shouldBe true
    Files.exists(fx.root.resolve("victim.txt")) shouldBe true
  }

  // ---------------------------------------------------------------------------------------------------------------
  // A link that points out of the workspace can be removed or renamed itself (#1730)

  /**
   * `outfile` -> outside/secret.txt, `outdir` -> outside, `dangling` -> outside/missing, and the chain
   * `chain` -> `hop` -> outside, `hop` itself a link in the workspace.
   */
  private def linksOut(fx: Fixture): Unit = {
    link(fx, "outfile", fx.outside.resolve("secret.txt"))
    link(fx, "outdir", fx.outside)
    link(fx, "dangling", fx.outside.resolve("missing"))
    link(fx, "hop", fx.outside)
    link(fx, "chain", fx.root.resolve("hop").getFileName)
    ()
  }

  private def isLink(p: Path): Boolean = Files.isSymbolicLink(p)

  /** Nothing outside was touched: the directory and its file are still there. */
  private def outsideIntact(fx: Fixture): Unit = {
    Files.isDirectory(fx.outside, java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe true
    new String(Files.readAllBytes(fx.outside.resolve("secret.txt")), StandardCharsets.UTF_8) shouldBe "secret\n"
  }

  "rm, mv and unlink" should "remove or rename a link that points out of the workspace, and only the link" in
    inWorkspace { fx =>
      linksOut(fx)
      val ws = fx.interface(ReadWrite.withExtraCommands("unlink").fold(fail(_), identity))
      runs(ws, "rm outfile")
      Files.exists(fx.root.resolve("outfile"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
      runs(ws, "rm -f outdir")
      Files.exists(fx.root.resolve("outdir"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
      runs(ws, "rm -- dangling")
      Files.exists(fx.root.resolve("dangling"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
      // A link to a link: only the first goes
      runs(ws, "rm chain")
      Files.exists(fx.root.resolve("chain"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
      isLink(fx.root.resolve("hop")) shouldBe true
      outsideIntact(fx)

      // mv renames the link, not its target, into a name or a directory inside
      runs(ws, "mv hop renamed")
      isLink(fx.root.resolve("renamed")) shouldBe true
      Files.readSymbolicLink(fx.root.resolve("renamed")) shouldBe fx.outside
      runs(ws, "mv -f renamed sub/")
      isLink(fx.root.resolve("sub").resolve("renamed")) shouldBe true
      // by a path through a directory inside, and from another working directory
      runs(ws, "mv sub/renamed sub/again")
      runs(ws, "rm ../sub/again", workingDirectory = Some("sub"))
      Files.exists(fx.root.resolve("sub").resolve("again"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false

      link(fx, "viaunlink", fx.outside)
      runs(ws, "unlink viaunlink")
      Files.exists(fx.root.resolve("viaunlink"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
      outsideIntact(fx)
    }

  it should "refuse such a link written with a trailing '/' or '/.', which makes the program follow it" in
    inWorkspace { fx =>
      linksOut(fx)
      val ws = fx.interface(ReadWrite.withExtraCommands("unlink").fold(fail(_), identity))
      // `mv outdir/ x` moves the directory outdir points to; `rm -r outdir/` empties it
      Seq(
        "rm outdir/",
        "rm -f outdir/.",
        "rm -r outdir/",
        "rm -rf outdir/.",
        "rm outdir//",
        "mv outdir/ moved",
        "mv outdir/. moved",
        "mv outfile/ moved",
        "unlink outdir/"
      ).foreach(refuses(ws, _, PathEscape))
      isLink(fx.root.resolve("outdir")) shouldBe true
      Files.exists(fx.root.resolve("moved"), java.nio.file.LinkOption.NOFOLLOW_LINKS) shouldBe false
      outsideIntact(fx)
    }

  it should "refuse a recursive rm of such a link" in inWorkspace { fx =>
    linksOut(fx)
    val ws = fx.interface(ReadWrite)
    Seq("rm -r outdir", "rm -R outdir", "rm -rf outdir", "rm -fR outdir", "rm --recursive outdir", "rm --rec outdir")
      .foreach(refuses(ws, _, PathEscape))
    isLink(fx.root.resolve("outdir")) shouldBe true
    outsideIntact(fx)
  }

  it should "still hold the destination of mv to the path rule, links followed" in inWorkspace { fx =>
    linksOut(fx)
    val ws = fx.interface(ReadWrite)
    Seq(
      "mv a.txt outdir", // moves a.txt into the directory outdir points to
      "mv a.txt outdir/",
      "mv a.txt outdir/a.txt",
      "mv a.txt outfile",
      "mv outfile outdir", // a link source into a link destination
      "mv b.txt hop",
      "mv -t outdir a.txt",
      "mv --target-directory=outdir a.txt",
      "mv a.txt -t outdir",
      "mv -f a.txt ../outside/a.txt"
    ).foreach(refuses(ws, _, PathEscape))
    Files.exists(fx.root.resolve("a.txt")) shouldBe true
    Files.exists(fx.outside.resolve("a.txt")) shouldBe false
    outsideIntact(fx)
  }

  it should "refuse a link whose directory is outside the workspace, or is so under one reading of the path" in
    inWorkspace { fx =>
      linksOut(fx)
      Files.createSymbolicLink(fx.outside.resolve("lnk"), fx.outside.resolve("secret.txt"))
      link(fx, "escape", fx.outside)
      // `l` -> a/b/c, and a/b/x -> outside: physically `l/../x` is a/b/x, textually the workspace's `x`
      Files.createDirectories(fx.root.resolve("a").resolve("b").resolve("c"))
      link(fx, "l", fx.root.resolve("a").resolve("b").resolve("c"))
      link(fx, "a/b/x", fx.outside)
      val ws = fx.interface(ReadWrite)
      Seq(
        "rm escape/lnk",
        "rm ../outside/lnk",
        fx.expand("rm '{out}/lnk'"),
        "mv escape/lnk moved",
        "rm l/../x",
        "rm .."
      ).foreach(refuses(ws, _, PathEscape))
      isLink(fx.outside.resolve("lnk")) shouldBe true
      isLink(fx.root.resolve("a").resolve("b").resolve("x")) shouldBe true
      outsideIntact(fx)
    }

  it should "keep the link refused under a form macOS or GNU would parse differently" in inWorkspace { fx =>
    linksOut(fx)
    val ws = fx.interface(ReadWrite)
    // GNU-only options (macOS rm and mv have no long options, -t or -T), and GNU mv's -S taking the link as its value
    Seq(
      "rm --force outfile",
      "mv -T outfile moved",
      "mv -t sub outfile",
      "mv --verbose outfile moved",
      "mv -S outfile a.txt sub"
    ).foreach(refuses(ws, _, PathEscape))
    isLink(fx.root.resolve("outfile")) shouldBe true
  }

  it should "keep such a link refused on Windows, where the policy does not model removing a link" in inWorkspace {
    fx =>
      linksOut(fx)
      val root = fx.root.toRealPath()
      Seq("rm" -> "outfile", "mv" -> "outdir", "del" -> "outfile").foreach { case (program, arg) =>
        CommandPolicy.refusal(program, Seq(arg, "x"), isWindows = true, root, root, Map.empty).map(_.code) shouldBe
          Some(PathEscape)
      }
      CommandPolicy.refusal("rm", Seq("outfile"), isWindows = false, root, root, Map.empty) shouldBe None
  }

  it should "leave other programs' handling of the link unchanged" in inWorkspace { fx =>
    linksOut(fx)
    val ws = fx.interface(ReadWrite)
    Seq("cat outfile", "cp outfile copied", "chmod 600 outfile", "touch outfile", "ls outdir", "mkdir outdir/x")
      .foreach(refuses(ws, _, PathEscape))
    outsideIntact(fx)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Several sources in one mv or cp: an earlier operation changes what a later path names (#1776)

  /** Nothing outside was moved, removed or changed, and none of `names` appeared in the workspace. */
  private def nothingPulledIn(fx: Fixture, names: String*): Unit = {
    outsideIntact(fx)
    names.foreach { name =>
      withClue(s"$name: ")(Files.exists(fx.root.resolve(name), LinkOption.NOFOLLOW_LINKS) shouldBe false)
    }
  }

  "mv and cp of several sources" should "refuse a path through a relative link an earlier source moves (#1776)" in
    inWorkspace { fx =>
      // While `sub/l` -> ../outside sits in `sub` it names the workspace's own (missing) `outside`; moved to the root
      // it names the real outside directory, so `mv sub/l l/secret.txt .` would move outside/secret.txt in.
      link(fx, "sub/l", Paths.get("../outside"))
      val ws = fx.interface(ReadWrite)
      Seq(
        "mv sub/l l/secret.txt .",
        "mv -f sub/l l/secret.txt ./",
        "mv -t . sub/l l/secret.txt",
        "mv sub/l l/../l/secret.txt .",
        "mv sub/l L/secret.txt ." // a case-insensitive file system
      ).foreach(refuses(ws, _, ArgumentNotAllowed))
      refuses(ws, "mv l ../l/secret.txt ..", ArgumentNotAllowed, workingDirectory = Some("sub"))
      isLink(fx.root.resolve("sub").resolve("l")) shouldBe true
      nothingPulledIn(fx, "l", "secret.txt")
    }

  it should "refuse a path through a link out of the workspace that an earlier source moves (#1730's exemption)" in
    inWorkspace { fx =>
      // `evil` may be moved itself (#1730); moved into the empty `d`, `d/evil/secret.txt` is outside/secret.txt
      link(fx, "evil", fx.outside)
      val d = Files.createDirectory(fx.root.resolve("d"))
      link(fx, "alias", d)
      val ws = fx.interface(ReadWrite)
      // On Windows `evil` is not exempted (#1730), so the path rule refuses it before this rule is reached
      val refusedWith = if (isWindowsHost) PathEscape else ArgumentNotAllowed
      Seq(
        "mv evil d/evil/secret.txt d",
        "mv evil alias/evil/secret.txt d", // the destination under another name
        "mv evil d/evil/secret.txt alias",
        "mv evil d/EVIL/secret.txt d",
        "mv evil d/evil~/secret.txt d", // the name --backup would keep a replaced entry under
        "mv evil d/evil.~1~/secret.txt d",
        "mv d/evil/secret.txt evil d" // order is not modelled
      ).foreach(refuses(ws, _, refusedWith))
      // On a case-insensitive file system (macOS, Windows), `D` is `d`: the same directory under another spelling
      if (Files.isDirectory(fx.root.resolve("D"))) refuses(ws, "mv evil D/evil/secret.txt d", refusedWith)
      isLink(fx.root.resolve("evil")) shouldBe true
      Using.resource(Files.list(d))(_.count()) shouldBe 0L
      nothingPulledIn(fx, "secret.txt")
    }

  it should "refuse a path through a source the same mv moves away, and an mv of the working directory's tree" in
    inWorkspace { fx =>
      Files.createDirectories(fx.root.resolve("a").resolve("b").resolve("w"))
      Files.createDirectory(fx.root.resolve("d"))
      val ws = fx.interface(ReadWrite)
      refuses(ws, "mv sub sub/Main.scala d", ArgumentNotAllowed)
      // From a/b/w, ../../outside is the workspace's own a/outside; once w is moved to the root, the working
      // directory moves with it and ../../outside is the real outside directory.
      refuses(
        ws,
        s"mv ../w ../../outside/secret.txt '${fx.root}'",
        ArgumentNotAllowed,
        workingDirectory = Some("a/b/w")
      )
      Files.isDirectory(fx.root.resolve("a").resolve("b").resolve("w")) shouldBe true
      Files.isDirectory(fx.root.resolve("sub")) shouldBe true
      nothingPulledIn(fx, "w", "secret.txt", "d/sub")
    }

  it should "refuse a cp whose later source is read through a link an earlier source copies as a link" in
    inWorkspace { fx =>
      link(fx, "sub/l", Paths.get("../outside"))
      Files.createDirectory(fx.root.resolve("d"))
      val ws = fx.interface(ReadWrite)
      Seq(
        "cp -P sub/l l/secret.txt .",
        "cp -R sub/l l/secret.txt .",
        "cp -a sub/l l/secret.txt .",
        "cp -P sub/l d/l/secret.txt d",
        "cp -R sub/l d/l/secret.txt d",
        "cp -a sub/l d/l/secret.txt d/",
        "cp -d sub/l d/l/secret.txt d", // GNU's -d
        "cp -P -t d sub/l d/l/secret.txt",
        "cp -R sub d/sub/l/secret.txt d" // a directory copied, then read through what it holds
      ).foreach(refuses(ws, _, ArgumentNotAllowed))
      nothingPulledIn(fx, "l", "secret.txt", "d/l", "d/secret.txt", "d/sub")
    }

  it should "still move and copy several sources that do not reach through each other, links included" in
    inWorkspace { fx =>
      linksOut(fx)
      val d  = Files.createDirectory(fx.root.resolve("d"))
      val ws = fx.interface(ReadWrite)
      runs(ws, "mv a.txt b.txt d")
      Files.exists(d.resolve("a.txt")) shouldBe true
      Files.exists(d.resolve("b.txt")) shouldBe true
      runs(ws, "mv d/a.txt d/b.txt .")
      runs(ws, "cp a.txt b.txt d/")
      Files.exists(d.resolve("b.txt")) shouldBe true
      // several links moved at once, each the link itself (#1730)
      runs(ws, "mv outdir dangling d/")
      Files.readSymbolicLink(d.resolve("outdir")) shouldBe fx.outside
      isLink(d.resolve("dangling")) shouldBe true
      runs(ws, "mv outfile sub")
      isLink(fx.root.resolve("sub").resolve("outfile")) shouldBe true
      runs(ws, "rm sub/outfile d/outdir")
      Files.exists(d.resolve("outdir"), LinkOption.NOFOLLOW_LINKS) shouldBe false
      outsideIntact(fx)
    }

  // ---------------------------------------------------------------------------------------------------------------
  // Links met inside a tree: options that follow them, and writes through a link already in the destination

  /** `d/innerout` -> outside, the shape a recursive command meets inside the tree it walks. */
  private def innerLinkOut(fx: Fixture): Unit = {
    Files.createDirectory(fx.root.resolve("d"))
    write(fx.root.resolve("d").resolve("in.txt"), "in\n")
    link(fx, "d/innerout", fx.outside)
    ()
  }

  it should "refuse cp options that follow links met inside the tree, so outside content is not copied in" in
    inWorkspace { fx =>
      innerLinkOut(fx)
      val ws = fx.interface(ReadWrite)
      Seq(
        "cp -RL d copied",
        "cp -R -L d copied",
        "cp -RH d copied",
        "cp -R --dereference d copied",
        "cp -R --deref d copied",
        "cp -s a.txt made-link",
        "cp --symbolic-link a.txt made-link"
      ).foreach(command => refuses(ws, command, ArgumentNotAllowed))
      Files.exists(fx.root.resolve("copied")) shouldBe false
      Files.exists(fx.root.resolve("made-link")) shouldBe false
    }

  it should "refuse chmod options that follow links met inside the tree, so outside permissions do not change" in
    inWorkspace { fx =>
      innerLinkOut(fx)
      // Windows has no POSIX permissions to compare; the refusals are still checked there.
      def permissions = Try(Files.getPosixFilePermissions(fx.outside.resolve("secret.txt"))).toOption
      val before      = permissions
      val ws          = fx.interface(ReadWrite)
      Seq("chmod -RL 700 d", "chmod -R -L 700 d", "chmod -RH 700 d", "chmod --dereference 700 a.txt")
        .foreach(command => refuses(ws, command, ArgumentNotAllowed))
      permissions shouldBe before
    }

  it should "refuse grep -S, which follows every link (BSD grep)" in inWorkspace { fx =>
    innerLinkOut(fx)
    val ws = fx.interface(ReadOnly)
    refuses(ws, "grep -rS secret d", ArgumentNotAllowed)
    refuses(ws, "grep -S -r secret .", ArgumentNotAllowed)
  }

  /** `dst/secret.txt` -> outside/secret.txt, and `src/secret.txt` a file that a copy into `dst` would write through it. */
  private def destinationLinkOut(fx: Fixture): Unit = {
    Files.createDirectory(fx.root.resolve("dst"))
    link(fx, "dst/secret.txt", fx.outside.resolve("secret.txt"))
    Files.createDirectory(fx.root.resolve("src"))
    write(fx.root.resolve("src").resolve("secret.txt"), "OVERWRITTEN\n")
  }

  it should "refuse a cp whose destination holds a link out of the workspace that the copy would write through" in
    inWorkspace { fx =>
      destinationLinkOut(fx)
      val ws = fx.interface(ReadWrite)
      refuses(ws, "cp src/secret.txt dst/", PathEscape)
      refuses(ws, "cp src/secret.txt dst", PathEscape)
      refuses(ws, "cp -t dst src/secret.txt", PathEscape)
      refuses(ws, "cp --target-directory=dst src/secret.txt", PathEscape)
      refuses(ws, "cp --t=dst src/secret.txt", PathEscape)
      refuses(ws, "cp src/secret.txt --target-directory dst", PathEscape)
      refuses(ws, "cp -R src/. dst/", PathEscape)
      refuses(ws, "cp -R src/ dst", PathEscape)
      refuses(ws, "cp -r src/. dst", PathEscape)
      Files.createDirectory(fx.root.resolve("dst2"))
      link(fx, "dst2/src", fx.outside)
      refuses(ws, "cp -R src dst2", PathEscape)
      new String(Files.readAllBytes(fx.outside.resolve("secret.txt")), StandardCharsets.UTF_8) shouldBe "secret\n"
    }

  it should "refuse a link-preserving cp of several sources that could put a file and a link at one name" in
    inWorkspace { fx =>
      Files.createDirectories(fx.root.resolve("s1"))
      Files.createDirectories(fx.root.resolve("s2"))
      val ws = fx.interface(ReadWrite)
      refuses(ws, "cp -R s1/. s2/. sub", ArgumentNotAllowed)
      refuses(ws, "cp -R s1/ s2/ sub", ArgumentNotAllowed)
      refuses(ws, "cp -R s1/x s2/x sub", ArgumentNotAllowed)
      refuses(ws, "cp -P s1/x s2/x sub", ArgumentNotAllowed)
      refuses(ws, "cp -a -t sub s1/x s2/x", ArgumentNotAllowed)
    }

  it should "still let cp and chmod work inside the workspace" in inWorkspace { fx =>
    innerLinkOut(fx)
    val ws = fx.interface(ReadWrite)
    runs(ws, "cp a.txt b.txt sub/")
    runs(ws, "cp -R sub sub2")
    runs(ws, "cp -R sub/. sub3")
    runs(ws, "chmod -R 755 sub")
    Files.exists(fx.root.resolve("sub").resolve("a.txt")) shouldBe true
    Files.exists(fx.root.resolve("sub2").resolve("Main.scala")) shouldBe true
    Files.exists(fx.root.resolve("sub3").resolve("Main.scala")) shouldBe true
  }

  // ---------------------------------------------------------------------------------------------------------------
  // uniq: BSD uniq does not permute, so every argument after the first operand is an operand

  it should "refuse uniq with an option after its operand, which BSD uniq takes as the output file" in
    inWorkspace { fx =>
      val ws = fx.interface(ReadOnly)
      refuses(ws, "uniq a.txt -s", ArgumentNotAllowed)
      refuses(ws, "uniq a.txt -c", ArgumentNotAllowed)
      refuses(ws, "uniq a.txt -fzz", ArgumentNotAllowed)
      Files.exists(fx.root.resolve("-s")) shouldBe false
      Files.exists(fx.root.resolve("-fzz")) shouldBe false
    }

  // ---------------------------------------------------------------------------------------------------------------
  // Option values are consumed once, in getopt order (#1763)

  it should "check the file after a -t that an earlier sort option took as its value (#1763)" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly)
    sortValueEscapes.foreach { command =>
      // A Windows runner refuses sort's -t and -T as options first (see `windowsSortRefusal`)
      if (isWindowsHost) refusesWithAny(ws, fx.expand(command), Set(ArgumentNotAllowed, PathEscape))
      else refuses(ws, fx.expand(command), PathEscape)
      refusesWithAny(fx.interface(ReadOnly, windows = true), fx.expand(command), Set(ArgumentNotAllowed, PathEscape))
    }
  }

  it should "still take sort's field separator as text when it really is -t's value (#1763)" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly)
    runs(ws, "sort -t / -k1 a.txt").stdout shouldBe "a\na\nb\nc\n"
    runs(ws, "sort -t: a.txt").stdout shouldBe "a\na\nb\nc\n"
    runs(ws, "sort -k2,2 -t, a.txt")
    runs(ws, "sort -T sub -t / -k2 a.txt")
    runs(ws, "sort -Tsub -t / a.txt")
    runs(ws, "sort -rT sub -t / a.txt").stdout shouldBe "c\nb\na\na\n"
    runs(ws, "sort --temporary-directory=sub -t / a.txt")
    runs(ws, "sort --temp sub --field-separator / a.txt")
    runs(ws, "sort --field-sep / a.txt")
    runs(ws, "sort --random-source a.txt -t / a.txt")
    runs(ws, "sort -k 1 -t / -- a.txt")
    runs(ws, "sort -t / a.txt b.txt")
  }

  it should "check sort's -t value when the arguments cannot be parsed exactly (#1763)" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly)
    Seq(
      // BSD sort rewrites `+POS1 -POS2` before parsing options, so `-1t` may vanish into another option's value
      "sort -T +0 -1t /",
      // an option neither GNU nor BSD sort has: the parse after it is a guess
      "sort --no-such-option -t / a.txt",
      "sort -x -t / a.txt",
      // after an operand, a sort run with POSIXLY_CORRECT reads every argument as a file
      "sort a.txt -t /"
    ).foreach(command => refuses(ws, command, if (isWindowsHost) ArgumentNotAllowed else PathEscape))
  }

  it should "check an option-looking argument whole, as a program that reads it as a file opens it (#1763)" in
    inWorkspace { fx =>
      link(fx, "-f", fx.outside.resolve("secret.txt"))
      link(fx, "-d", fx.outside)
      val ws = fx.interface(ReadWrite)
      // BSD cat stops reading options at its first operand, so it opens `-f`
      refuses(ws, "cat a.txt -f", PathEscape)
      // `-d` is the value of -T and of -t: sort writes its temporary files into it, cp copies into it
      // (a Windows runner refuses sort -T itself first, see `windowsSortRefusal`)
      refuses(ws, "sort -T -d a.txt", if (isWindowsHost) ArgumentNotAllowed else PathEscape)
      refuses(ws, "cp -t -d a.txt", PathEscape)
      refuses(ws, "cp --target-directory -d a.txt", PathEscape)
      new String(Files.readAllBytes(fx.outside.resolve("secret.txt")), StandardCharsets.UTF_8) shouldBe "secret\n"
    }

  it should "see cp's recursive flag after a -- that -S took as its suffix (#1763)" in inWorkspace { fx =>
    // `dst/src` is a directory inside the workspace holding a link out: a recursive copy of `src` writes through it
    Files.createDirectories(fx.root.resolve("dst").resolve("src"))
    link(fx, "dst/src/secret.txt", fx.outside.resolve("secret.txt"))
    Files.createDirectory(fx.root.resolve("src"))
    write(fx.root.resolve("src").resolve("secret.txt"), "OVERWRITTEN\n")
    val ws = fx.interface(ReadWrite)
    refuses(ws, "cp -R src dst", PathEscape) // the control: seen as recursive
    refuses(ws, "cp -S -- -R src dst", PathEscape)
    refuses(ws, "cp --suffix -- -R src dst", PathEscape)
    refuses(ws, "cp --suf -- -a src dst", PathEscape)
    new String(Files.readAllBytes(fx.outside.resolve("secret.txt")), StandardCharsets.UTF_8) shouldBe "secret\n"
  }

  it should "check the name cp --path writes, as it does for --parents (#1763)" in inWorkspace { fx =>
    Files.createDirectories(fx.root.resolve("dst"))
    link(fx, "dst/src", fx.outside)
    Files.createDirectory(fx.root.resolve("src"))
    write(fx.root.resolve("src").resolve("secret.txt"), "OVERWRITTEN\n")
    val ws = fx.interface(ReadWrite)
    refuses(ws, "cp --parents src/secret.txt dst", PathEscape)
    refuses(ws, "cp --path src/secret.txt dst", PathEscape)
    refuses(ws, "cp --pat src/secret.txt dst", PathEscape)
  }

  it should "still run cp with option values in every spelling (#1763)" in inWorkspace { fx =>
    val ws = fx.interface(ReadWrite)
    // GNU cp only; BSD cp has no -t
    passesPolicy(ws, "cp -t sub a.txt")
    passesPolicy(ws, "cp --target-directory sub b.txt")
    passesPolicy(ws, "cp -S .bak -- a.txt sub")
    runs(ws, "cp -R sub sub2")
    Files.exists(fx.root.resolve("sub2").resolve("Main.scala")) shouldBe true
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Cost of the path check

  it should "refuse an over-long argument instead of walking it" in inWorkspace { fx =>
    val ws    = fx.interface(ReadOnly)
    val start = System.nanoTime()
    refuses(ws, "cat " + ("x/" * 3000) + ("../" * 3000) + "a.txt", ArgumentNotAllowed)
    // 64 relative paths of 1000 components each: every one is inside, but walking them all costs too much
    refuses(ws, ("ls" +: Seq.fill(64)("a/" * 1000)).mkString(" "), ArgumentNotAllowed)
    // an option whose every tail is a path: the first absolute tail is refused without walking the rest
    refuses(ws, "ls -x" + ("a/" * 2040), PathEscape)
    // The refusals above are the signal: without the cap the first command would walk back inside and run. This
    // bound is only a backstop against a walk that takes far longer than refusing (seconds per command before the cap).
    (System.nanoTime() - start).nanos should be < 30.seconds
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Option values that are not paths

  it should "check only the value of a --name=value option, so text values that look like paths run" in
    inWorkspace { fx =>
      gitRepo(fx)
      val ws = fx.interface(ReadOnly)
      runs(ws, "git log --since=2024/01/01 --oneline")
      runs(ws, "git log --grep=feat/x --oneline")
      runs(ws, "git ls-files --exclude=*/target/* -o")
      runs(ws, "grep -rn --exclude=sub/*.txt object .").stdout should include("Main.scala")
      passesPolicy(ws, "ls --hide=x/y") // GNU ls only; BSD ls rejects the option itself
    }

  it should "not take sort's field separator for a path" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly)
    runs(ws, "sort -t/ -k2 a.txt")
    runs(ws, "sort -t / -k2 a.txt")
    runs(ws, "sort -rt/ a.txt")
    runs(ws, "sort --field-separator=/ a.txt")
  }

  it should "still check option values that name files outside the workspace" in inWorkspace { fx =>
    link(fx, "escape", fx.outside)
    val ws = fx.interface(ReadOnly)
    // (command, refused as an option on Windows): a Windows runner refuses sort's `-t` and `-T` before any path is
    // checked, since it cannot tell sort.exe from a GNU sort (see `windowsSortRefusal`), so there those commands are
    // refused with ARGUMENT_NOT_ALLOWED instead.
    Seq(
      "sort --random-source='{out}/secret.txt' a.txt"    -> false,
      "sort --random-source=escape/secret.txt a.txt"     -> false,
      "sort --random-source=../outside/secret.txt a.txt" -> false,
      "sort -t/ '{out}/secret.txt'"                      -> true,
      "sort -t / '{out}/secret.txt'"                     -> true,
      "sort -Tt '{out}/secret.txt'"                      -> true,
      "sort --field-separator / '{out}/secret.txt'"      -> false,
      "grep --exclude-from='{out}/secret.txt' x a.txt"   -> false,
      "grep --exclude-from=escape/secret.txt x a.txt"    -> false,
      "git log --grep=escape/secret.txt"                 -> false,
      "git branch --merged '{out}'"                      -> false,
      "cat -- --x=/../../outside/secret.txt"             -> false
    ).foreach { case (command, refusedOnWindows) =>
      refuses(ws, fx.expand(command), if (isWindowsHost && refusedOnWindows) ArgumentNotAllowed else PathEscape)
      // The same Windows verdict on every host, so a Windows-only rule that changes the code is seen off Windows too
      if (refusedOnWindows) refuses(fx.interface(ReadOnly, windows = true), fx.expand(command), ArgumentNotAllowed)
    }
  }

  it should "parse git branch filter values as values, not as branch names" in inWorkspace { fx =>
    gitRepo(fx)
    val ws = fx.interface(ReadOnly)
    runs(ws, "git branch --merged HEAD")
    runs(ws, "git branch --no-merged HEAD")
    runs(ws, "git branch --contains HEAD")
    runs(ws, "git branch --points-at HEAD")
    runs(ws, "git branch --sort -committerdate")
    runs(ws, "git branch --format x").stdout should include("x")
    refuses(ws, "git branch --merged HEAD evil", ArgumentNotAllowed)
    refuses(ws, "git branch --format x evil", ArgumentNotAllowed)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Refused environment

  refusedEnvironment.foreach { case (command, variable) =>
    it should s"refuse `$command` with $variable set (ENVIRONMENT_NOT_ALLOWED)" in inWorkspace { fx =>
      refuses(fx.interface(ReadOnly), command, EnvironmentNotAllowed, environment = Some(Map(variable -> "true")))
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Windows built-ins: the policy runs before the process starts, so a runner told it is on Windows shows it

  "On Windows" should "refuse sort /O, which writes a file" in inWorkspace { fx =>
    refuses(fx.interface(ReadOnly, windows = true), "sort /O out.txt a.txt", ArgumentNotAllowed)
    refuses(fx.interface(ReadOnly, windows = true), "sort /output out.txt a.txt", ArgumentNotAllowed)
  }

  it should "refuse a built-in's path outside the workspace, in an operand or a switch value" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly, windows = true)
    refuses(ws, fx.expand("type '{out}/secret.txt'"), PathEscape)
    refuses(ws, "type ../outside/secret.txt", PathEscape)
    refuses(ws, fx.expand("findstr /G:'{out}/secret.txt' a.txt"), PathEscape)
    refuses(ws, "dir ..", PathEscape)
    refuses(fx.interface(ReadWrite, windows = true), "copy a.txt ../outside/c.txt", PathEscape)
    refuses(fx.interface(ReadWrite, windows = true), "move a.txt ../outside/m.txt", PathEscape)
  }

  it should "refuse a built-in's path given as a device, NT-namespace or other-drive path" in inWorkspace { fx =>
    assume(isWindowsHost, "these are paths only on Windows")
    val ws    = fx.interface(ReadOnly, windows = true)
    val out   = fx.outside.toString
    val drive = fx.root.getRoot.toString.take(2) // `C:`
    val other = if (drive.equalsIgnoreCase("Z:")) "Y:" else "Z:"
    refuses(ws, s"type '\\\\?\\$out\\secret.txt'", PathEscape)
    refuses(ws, s"type '\\??\\$out\\secret.txt'", PathEscape)
    refuses(ws, s"type '\\\\.\\$out\\secret.txt'", PathEscape)
    refuses(ws, s"type ${other}secret.txt", PathEscape)
    refuses(ws, s"type '\\${out.drop(3)}\\secret.txt'", PathEscape) // root-relative: the workspace's drive
    refuses(ws, s"dir '$out\\*'", PathEscape)
    refuses(ws, "dir '..\\*'", PathEscape)
    refuses(ws, s"findstr /G:'$out\\secret.txt' a.txt", PathEscape)
    passesPolicy(ws, s"type ${drive}a.txt")    // drive-relative on the workspace's drive: the working directory
    passesPolicy(ws, "findstr /G:a.txt b.txt") // `G:` names the switch, not a drive
    refuses(ws, s"findstr /G:${other}secret.txt a.txt", PathEscape)
  }

  it should "refuse a wildcard or ':' argument whose '..' after that character climbs out" in inWorkspace { fx =>
    // Win32 removes `..` as text before it opens a name or matches a wildcard, so `x*\..\..\outside\secret.txt`
    // opens `..\outside\secret.txt`; the part before the `*` (`x`) is inside.
    val ro = fx.interface(ReadOnly, windows = true)
    val rw = fx.interface(ReadWrite, windows = true)
    unparseableEscapes.foreach(command => refuses(ro, command, PathEscape))
    refuses(rw, "copy 'x*\\..\\..\\outside\\secret.txt' c.txt", PathEscape)
    refuses(rw, "copy a.txt 'x?\\..\\..\\outside\\c.txt'", PathEscape)
    refuses(rw, "move a.txt 'ab:c\\..\\..\\outside\\m.txt'", PathEscape)
    unparseableControls.foreach(command => passesPolicy(ro, command))
  }

  it should "refuse any argument holding '\"', which Windows removes as a quote before it opens the name" in inWorkspace {
    fx =>
      // The C runtime's argv parser and cmd.exe delete `"`, so `"..\x` opens `..\x` and `"C:\x` opens `C:\x`.
      val ro = fx.interface(ReadOnly, windows = true)
      quotedEscapes.map(fx.expand).foreach(command => refuses(ro, command, ArgumentNotAllowed))
      refuses(fx.interface(ReadWrite, windows = true), "copy '\"..\\outside\\secret.txt' c.txt", ArgumentNotAllowed)
      refuses(ro, "echo '\"x'", ArgumentNotAllowed)
      unparseableControls.foreach(command => passesPolicy(ro, command))
      // Off Windows `"` is an ordinary file-name character
      passesPolicy(fx.interface(ReadOnly, windows = false), "cat 'q\"x.txt'")
  }

  it should "refuse a cmd.exe delimiter in a built-in's argument, which cmd.exe splits the argument at" in inWorkspace {
    fx =>
      // ProcessBuilder quotes an argument only for a space, tab, `"`, `<` or `>`, so `,` and `=` reach cmd.exe bare
      // and `type a.txt,..\outside\secret.txt` types `a.txt` and then `..\outside\secret.txt`.
      val ro = fx.interface(ReadOnly, windows = true)
      val rw = fx.interface(ReadWrite, windows = true)
      cmdDelimiterEscapes.foreach(command => refuses(ro, command, ArgumentNotAllowed))
      refuses(rw, "copy a.txt 'b.txt,..\\outside\\x'", ArgumentNotAllowed)
      refuses(rw, "move a.txt 'b.txt=..\\outside\\x'", ArgumentNotAllowed)
      refuses(ro, "type 'a.txt;..\\outside\\secret.txt'", "FORBIDDEN_CHARACTERS")
      cmdDelimiterControls.foreach(command => passesPolicy(ro, command))
      passesPolicy(rw, "copy a.txt c.txt")
      // Not a built-in: findstr is its own program and gets its arguments from the C runtime, which splits on
      // space and tab only, so `a,b.txt` is one name there
      passesPolicy(ro, "findstr x 'a,b.txt'")
      // Off Windows nothing goes through cmd.exe
      passesPolicy(fx.interface(ReadOnly, windows = false), "cat 'a,b.txt'")
  }

  it should "refuse those built-in arguments through the policy itself" in inWorkspace { fx =>
    val root = fx.root.toRealPath()
    def refusal(program: String, args: String*) =
      CommandPolicy.refusal(program, args, isWindows = true, root, root, Map.empty).map(_.code)
    Seq(
      "type" -> "a.txt,..\\outside\\secret.txt",
      "type" -> "a.txt=..\\outside\\secret.txt",
      "type" -> "a.txt=C:\\outside\\secret.txt",
      "type" -> ",C:\\outside\\secret.txt",
      "dir"  -> "a,..\\..",
      "type" -> "a.txt\u000B..\\outside\\secret.txt",
      "type" -> "a.txt\u000C..\\outside\\secret.txt",
      "type" -> "a.txt\u00A0..\\outside\\secret.txt",
      "type" -> "a.txt\u00FF..\\outside\\secret.txt",
      "type" -> "a.txt\n..\\outside\\secret.txt",
      "type" -> "a.txt\r..\\outside\\secret.txt",
      "type" -> "a.txt\u3000..\\outside\\secret.txt",
      "type" -> "(a.txt)",
      "type" -> "@a.txt",
      "type" -> "!PATH!",
      "echo" -> "!PATH!",
      "echo" -> "x\ny"
    ).foreach { case (program, arg) =>
      withClue(s"$program ${arg.map(c => if (Character.isISOControl(c)) '?' else c)}: ") {
        refusal(program, arg) shouldBe Some(ArgumentNotAllowed)
      }
    }
    refusal("copy", "a.txt", "b.txt,..\\outside\\x") shouldBe Some(ArgumentNotAllowed)
    refusal("type", "a.txt") shouldBe None
    refusal("type", "my file.txt") shouldBe None // a space: ProcessBuilder quotes it
    refusal("dir", "*.txt") shouldBe None
    refusal("echo", "Hello,", "world", "(a=b)") shouldBe None
    refusal("findstr", "x", "a,b.txt") shouldBe None
  }

  it should "type only the checked file when a delimiter is refused on a Windows host" in inWorkspace { fx =>
    assume(isWindowsHost, "cmd.exe runs the built-ins only on Windows")
    val ws = fx.interface(ReadOnly, windows = true)
    cmdDelimiterEscapes.foreach(command => refuses(ws, command, ArgumentNotAllowed))
    refuses(ws, s"type 'a.txt,${fx.outside}\\secret.txt'", ArgumentNotAllowed)
    val typed = ws.executeCommand("type a.txt", None, Some(30.seconds), None)
    typed.exitCode shouldBe 0
    typed.stdout should include("b")
    (typed.stdout should not).include("secret")
  }

  it should "refuse those arguments where the platform itself cannot parse them" in inWorkspace { fx =>
    assume(isWindowsHost, "these strings are unparseable paths only on Windows")
    val ws = fx.interface(ReadOnly, windows = true)
    unparseableEscapes.foreach(command => refuses(ws, command, PathEscape))
    quotedEscapes.map(fx.expand).foreach(command => refuses(ws, command, ArgumentNotAllowed))
    refuses(fx.interface(ReadWrite, windows = true), "copy 'x*\\..\\..\\outside\\secret.txt' c.txt", PathEscape)
    unparseableControls.foreach(command => passesPolicy(ws, command))
  }

  it should "match variable names without regard to case" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly, windows = true)
    refuses(ws, "dir", EnvironmentNotAllowed, environment = Some(Map("git_external_diff" -> "x")))
    refuses(ws, "dir", EnvironmentNotAllowed, environment = Some(Map("Path" -> "x")))
  }

  it should "not refuse a built-in's own switches" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly, windows = true)
    Seq("dir /S /B", "findstr /S /I x a.txt", "dir", "type a.txt").foreach { command =>
      // cmd.exe is absent off Windows, so the command may fail to start; it must not be refused by the policy
      val outcome = Try(ws.executeCommand(command, None, Some(5.seconds), Some(Map("lang" -> "C")))).failed.toOption
      outcome.collect { case e: WorkspaceAgentException => e.code }.foreach { code =>
        withClue(command)(PolicyCodes should not contain code)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Windows: forms the policy cannot reason about are refused (over-blocking is accepted there)

  /** The policy's verdict code for `program args` as a Windows (or POSIX) runner would judge it in the workspace. */
  private def policy(fx: Fixture, windows: Boolean, command: String*): Option[String] = {
    val root = fx.root.toRealPath()
    CommandPolicy.refusal(command.head, command.tail, windows, root, root, Map.empty).map(_.code)
  }

  /** Windows forms that each item refuses, as (program, arguments). */
  private def windowsFormsRefused: Seq[Seq[String]] = Seq(
    // findstr /F reads the list of files to search from a file; /D takes a directory list
    Seq("findstr", "/F:list.txt", "x"),
    Seq("findstr", "/f:list.txt", "x"),
    Seq("findstr", "-F:list.txt", "x"),
    Seq("findstr", "/SIF:list.txt", "x"),
    Seq("findstr", "/F", "list.txt", "x"),
    Seq("findstr", "/D:sub,other", "x", "*.txt"),
    Seq("findstr", "/d:sub;other", "x", "*.txt"),
    // A runtime that expands the command line (MSYS2, Cygwin): response files, tilde, glob and quoting syntax
    Seq("grep", "x", "@args.txt"),
    Seq("findstr", "x", "@args.txt"),
    Seq("git", "log", "@{1}"),
    Seq("cat", "~/secret.txt"),
    Seq("cat", "~other"),
    Seq("grep", "x", "a{b,c}.txt"),
    Seq("grep", "[ab]", "a.txt"),
    Seq("grep", "x", "a[0-9].txt"),
    Seq("grep", "x(", "a.txt"),
    Seq("grep", "x", "it's.txt"),
    Seq("cat", "/sub/a.txt"),
    Seq("grep", "-f/sub/a.txt", "x"),
    Seq("grep", "--file=/sub/a.txt", "x"),
    // Wildcards that can match `..`, sit in an earlier component, or start absolute or with `..`
    Seq("grep", "x", "*"),
    Seq("grep", "x", ".*"),
    Seq("grep", "x", "*.*"),
    Seq("grep", "x", "??"),
    Seq("grep", "x", "*/a.txt"),
    Seq("grep", "x", "sub/../*.txt"),
    Seq("findstr", "x", "*"),
    Seq("git", "ls-files", "--exclude=*/target/*"),
    // Device names, any case, any extension, trailing dots and spaces ignored
    Seq("type", "nul"),
    Seq("type", "NUL.txt"),
    Seq("type", "sub\\con"),
    Seq("type", "aux .txt"),
    Seq("type", "CONIN$"),
    Seq("type", "conout$"),
    Seq("type", "prn"),
    Seq("dir", "com1"),
    Seq("findstr", "x", "LPT9"),
    Seq("findstr", "x", "com¹"),
    Seq("findstr", "x", "lpt³.log"),
    Seq("findstr", "/G:nul", "a.txt"),
    Seq("copy", "a.txt", "nul"),
    Seq("grep", "-fnul", "a.txt"),
    Seq("grep", "--file=PRN", "a.txt"),
    Seq("cat", "aux:stream"),
    // A trailing dot or space, which Win32 strips
    Seq("type", "outside."),
    Seq("type", "a.txt."),
    Seq("type", "a.txt "),
    Seq("type", "sub.\\Main.scala"),
    Seq("findstr", "x", "sub \\Main.scala"),
    Seq("git", "log", "HEAD.."),
    // sort.exe writes its output or temporary files
    Seq("sort", "/O", "out.txt", "a.txt"),
    Seq("sort", "/o", "out.txt", "a.txt"),
    Seq("sort", "/OUTPUT", "out.txt", "a.txt"),
    Seq("sort", "/T", "sub", "a.txt"),
    Seq("sort", "/TEMPORARY", "sub", "a.txt"),
    Seq("sort", "-O", "out.txt", "a.txt"),
    Seq("sort", "-T", "sub", "a.txt"),
    Seq("sort", "--temporary-directory=sub", "a.txt"),
    Seq("sort", "--temp=sub", "a.txt"),
    // git pathspec magic and index paths resolve from the repository's top level
    Seq("git", "ls-files", ":/"),
    Seq("git", "log", "--", ":(top)a.txt"),
    Seq("git", "show", ":a.txt"),
    Seq("git", "grep", "x", "--", ":!a.txt")
  )

  /** Ordinary Windows uses that every item leaves alone. */
  private def windowsFormsAllowed: Seq[Seq[String]] = Seq(
    Seq("dir", "*.txt"),
    Seq("dir"),
    Seq("dir", "/S", "/B"),
    Seq("type", "a.txt"),
    Seq("type", "sub\\Main.scala"),
    Seq("type", ".\\a.txt"),
    Seq("type", "console.txt"),
    Seq("type", "nullable.txt"),
    Seq("type", "a.b.txt"),
    Seq("findstr", "x", "a.txt"),
    Seq("findstr", "/S", "/I", "x", "*.txt"),
    Seq("findstr", "/SIN", "x", "*.txt"),
    Seq("findstr", "/OFFLINE", "x", "a.txt"),
    Seq("findstr", "/D:sub", "x", "*.scala"),
    Seq("findstr", "/C:x", "a.txt"),
    Seq("findstr", "/G:a.txt", "b.txt"),
    Seq("findstr", "x", "a?.txt"),
    Seq("grep", "x", "*.txt"),
    Seq("grep", "x", "sub/*.scala"),
    Seq("grep", "-r", "--include=*.scala", "x", "."),
    Seq("sort", "a.txt"),
    Seq("sort", "/R", "a.txt"),
    Seq("sort", "/+2", "a.txt"),
    Seq("git", "status"),
    Seq("git", "log", "--oneline"),
    Seq("git", "log", "HEAD~1"),
    Seq("git", "diff", "HEAD~1..HEAD"),
    Seq("git", "show", "HEAD:a.txt"),
    Seq("echo", "nul"),
    Seq("echo", "Hello", "world."),
    // A short name that does not exist is a name inside the working directory (see the docs)
    Seq("type", "PROGRA~1\\x.txt"),
    // An alternate data stream of a file inside
    Seq("findstr", "x", "sub\\Main.scala:s")
  )

  "On Windows, the policy" should "refuse every form it cannot reason about" in inWorkspace { fx =>
    def windows(command: String*) = policy(fx, true, command: _*)
    windowsFormsRefused.foreach { command =>
      withClue(s"${command.mkString(" ")}: ") {
        windows(command: _*) shouldBe defined
      }
    }
  }

  it should "still allow the ordinary forms" in inWorkspace { fx =>
    def windows(command: String*) = policy(fx, true, command: _*)
    windowsFormsAllowed.foreach { command =>
      withClue(s"${command.mkString(" ")}: ") {
        windows(command: _*) shouldBe None
      }
    }
  }

  it should "refuse them through executeCommand too" in inWorkspace { fx =>
    val ro = fx.interface(ReadOnly, windows = true)
    Seq(
      "findstr /F:a.txt x",
      "grep x @a.txt",
      "cat '~/secret.txt'",
      "grep '[ab]' a.txt",
      "grep x '*'",
      "type nul",
      "findstr x COM1",
      "type 'a.txt.'",
      "sort /T sub a.txt",
      "git show :a.txt"
    ).foreach(command => refuses(ro, command, ArgumentNotAllowed))
    refuses(fx.interface(ReadWrite, windows = true), "copy a.txt nul", ArgumentNotAllowed)
    passesPolicy(ro, "findstr /S /I x *.txt")
    passesPolicy(ro, "dir *.txt")
    passesPolicy(ro, "type a.txt")
  }

  it should "judge an alternate data stream by the file it belongs to" in inWorkspace { fx =>
    def windows(command: String*) = policy(fx, true, command: _*)
    windows("type", "..\\outside\\secret.txt:stream") shouldBe Some(PathEscape)
    windows("findstr", "x", "..\\outside\\secret.txt:stream") shouldBe Some(PathEscape)
    windows("type", "a.txt:stream") shouldBe None
    windows("type", "sub\\Main.scala:stream:$DATA") shouldBe None
  }

  it should "leave POSIX runners' arguments to the existing rules" in inWorkspace { fx =>
    // None of these forms is Windows syntax off Windows: each is a plain name inside the workspace
    def posix(command: String*) = policy(fx, false, command: _*)
    posix("cat", "@a.txt") shouldBe None
    posix("cat", "~a.txt") shouldBe None
    posix("grep", "[/]api", "a.txt") shouldBe None
    posix("cat", "a.txt.") shouldBe None
    posix("cat", "nul") shouldBe None
    posix("grep", "x", "*") shouldBe None
    posix("sort", "-T", "sub", "a.txt") shouldBe None
    posix("sort", "-t", ",", "a.txt") shouldBe None
    posix("git", "log", "HEAD..") shouldBe None
    // git's ':' arguments are refused everywhere
    posix("git", "ls-files", ":/") shouldBe Some(ArgumentNotAllowed)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // git: a repository above the workspace, or one a .git file points to

  "git" should "not use a repository whose top level is above the workspace" in inWorkspace { fx =>
    assume(!isWindowsHost, "the git checks run on a Unix host")
    // The workspace's parent is a repository holding a file outside the workspace; the workspace has none of its own
    def git(dir: Path, args: String*): Unit = {
      val p = new ProcessBuilder(("git" +: args).asJava).directory(dir.toFile).redirectErrorStream(true).start()
      p.getInputStream.readAllBytes()
      p.waitFor() shouldBe 0
    }
    git(fx.parent, "init", "-q")
    git(fx.parent, "-c", "user.name=t", "-c", "user.email=t@example.com", "add", ".")
    git(fx.parent, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "init")
    val ws = fx.interface(ReadOnly)
    Seq("git show HEAD:outside/secret.txt", "git log -p", "git status", "git ls-files").foreach { command =>
      val response = ws.executeCommand(command, None, Some(30.seconds), None)
      withClue(s"$command: ${response.stdout} ${response.stderr}") {
        response.exitCode should not be 0
        (response.stdout should not).include("secret")
        response.stderr should include("not a git repository")
      }
    }
    // A repository of the workspace's own is still used, from any directory below it
    git(fx.root, "init", "-q")
    ws.executeCommand("git status", Some("sub"), Some(30.seconds), None).exitCode shouldBe 0
  }

  it should "refuse a .git file or link that points the repository elsewhere" in inWorkspace { fx =>
    // `gitdir: <path>` makes git use that repository: its content and its configuration
    write(fx.root.resolve(".git"), s"gitdir: ${fx.outside.resolve(".git")}\n")
    Seq(false, true).foreach { windows =>
      refuses(fx.interface(ReadOnly, windows), "git status", PathEscape)
      refuses(fx.interface(ReadOnly, windows), "git log", PathEscape, workingDirectory = Some("sub"))
    }
    Files.delete(fx.root.resolve(".git"))
    link(fx, ".git", fx.outside)
    refuses(fx.interface(ReadOnly), "git status", PathEscape)
  }

  it should "confine git's repository search to the workspace" in {
    val environment = new java.util.HashMap[String, String]()
    environment.put("GIT_DIR", "/elsewhere/.git")
    environment.put("git_work_tree", "/elsewhere")
    environment.put("GIT_CEILING_DIRECTORIES", "")
    // Configuration, programs and object stores the runner's own environment could carry
    Seq(
      "GIT_CONFIG",
      "GIT_CONFIG_GLOBAL",
      "GIT_CONFIG_SYSTEM",
      "GIT_CONFIG_NOSYSTEM",
      "GIT_CONFIG_PARAMETERS",
      "GIT_CONFIG_COUNT",
      "GIT_CONFIG_KEY_0",
      "GIT_CONFIG_VALUE_0",
      "git_config_key_1",
      "GIT_EXEC_PATH",
      "GIT_EXTERNAL_DIFF",
      "GIT_PAGER",
      "GIT_ALTERNATE_OBJECT_DIRECTORIES",
      "GIT_OBJECT_DIRECTORY",
      "GIT_INDEX_FILE",
      "GIT_NAMESPACE",
      "GIT_COMMON_DIR",
      "GIT_DISCOVERY_ACROSS_FILESYSTEM"
    ).foreach(environment.put(_, "/elsewhere"))
    environment.put("LANG", "C")
    environment.put("PATH", "/usr/bin")
    environment.put("MYGIT_X", "kept")
    val root = Files.createTempDirectory("ws-git").toRealPath()
    try {
      CommandPolicy.confineGit(environment, root)
      environment.asScala.toMap shouldBe Map(
        "LANG"                    -> "C",
        "PATH"                    -> "/usr/bin",
        "MYGIT_X"                 -> "kept",
        "GIT_CEILING_DIRECTORIES" -> root.getParent.toString
      )
    } finally Files.delete(root)
  }

  it should "refuse git when the workspace's parent path holds the path-list separator" in {
    assume(!isWindowsHost, "the git checks run on a Unix host")
    // GIT_CEILING_DIRECTORIES is a ':'-separated list with no escaping, so a parent named `x:y` would be read as the
    // two entries `.../x` and `y`, the ceiling would be ignored, and git would climb into the repository above
    def git(dir: Path, args: String*): Unit = {
      val p = new ProcessBuilder(("git" +: args).asJava).directory(dir.toFile).redirectErrorStream(true).start()
      p.getInputStream.readAllBytes()
      p.waitFor() shouldBe 0
    }
    def layout(parentName: String)(check: WorkspaceAgentInterfaceImpl => Unit): Unit = {
      val top = Files.createTempDirectory("ws-sep")
      try {
        val parent = Files.createDirectory(top.resolve(parentName))
        write(parent.resolve("s"), "SECRET\n")
        git(parent, "init", "-q")
        git(parent, "-c", "user.name=t", "-c", "user.email=t@example.com", "add", ".")
        git(parent, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "init")
        val root = Files.createDirectory(parent.resolve("ws"))
        Files.createDirectory(root.resolve("sub"))
        check(new WorkspaceAgentInterfaceImpl(root.toString, isWindowsHost, Some(ReadOnly)))
      } finally {
        def delete(p: Path): Unit = {
          if (Files.isDirectory(p) && !Files.isSymbolicLink(p))
            Using.resource(Files.list(p))(_.iterator().asScala.foreach(delete))
          Files.deleteIfExists(p)
        }
        delete(top)
      }
    }
    layout("x:y") { ws =>
      val ex = refuses(ws, "git show HEAD:s", PathEscape, workingDirectory = Some("sub"))
      (ex.error should not).include("SECRET")
      refuses(ws, "git status", PathEscape)
      // Other programs are unaffected
      ws.executeCommand("ls", Some("sub"), Some(30.seconds), None).exitCode shouldBe 0
    }
    // Control: the same layout without the separator runs git, which stops at the workspace root
    layout("xy") { ws =>
      val response = ws.executeCommand("git show HEAD:s", Some("sub"), Some(30.seconds), None)
      (response.stdout should not).include("SECRET")
      response.stderr should include("not a git repository")
    }
  }

  it should "refuse a .git directory that resolves outside the workspace" in inWorkspace { fx =>
    // A link to a real repository's .git directory outside: git would read its objects and configuration
    val outsideGit = Files.createDirectory(fx.outside.resolve(".git"))
    link(fx, ".git", outsideGit)
    refuses(fx.interface(ReadOnly), "git status", PathEscape)
    refuses(fx.interface(ReadOnly), "git log", PathEscape, workingDirectory = Some("sub"))
    // What Java reports as a directory without following links (a Windows junction) must also resolve inside the
    // workspace: reached through a link `l` -> outside, `l/.git` is a directory whose real path is outside
    Files.delete(fx.root.resolve(".git"))
    val l        = link(fx, "l", fx.outside)
    val realRoot = fx.root.toRealPath()
    CommandPolicy
      .refusal("git", Seq.empty, isWindows = false, realRoot.resolve("l"), realRoot, Map.empty)
      .map(_.code) shouldBe Some(PathEscape)
    // A .git directory of the workspace's own is accepted
    Files.delete(l)
    Files.createDirectory(fx.root.resolve(".git"))
    CommandPolicy.refusal("git", Seq.empty, isWindows = false, realRoot, realRoot, Map.empty) shouldBe None
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Strings that are not paths: refused or run, never a raw exception

  "Path checks" should "refuse an argument or working directory holding a NUL character" in inWorkspace { fx =>
    Seq(false, true).foreach { windows =>
      val ws = fx.interface(ReadOnly, windows)
      refuses(ws, "cat a.txt\u0000../../outside/secret.txt", ArgumentNotAllowed)
      refuses(ws, "grep -f\u0000x a.txt", ArgumentNotAllowed)
      refuses(ws, "ls", PathEscape, workingDirectory = Some("sub\u0000x"))
    }
  }

  it should "never throw anything but a WorkspaceAgentException for a string that is not a path" in inWorkspace { fx =>
    val inputs = Seq(
      "C:foo",
      "Z:foo",
      "C:",
      "\\\\?\\C:\\x",
      "\\\\?\\",
      "\\??\\C:\\x",
      "\\\\.\\C:\\x",
      "\\\\server",
      "\\\\",
      "nul.txt",
      "x:y:z",
      "a<b>c|d",
      "*",
      "x" * 255,
      ("y" * 250 + "/") * 15
    )
    Seq(false, true).foreach { windows =>
      val ws = fx.interface(ReadOnly, windows)
      inputs.foreach { arg =>
        Seq("cat", "type", "dir", "grep -f", "findstr /G:").foreach { program =>
          val attached = program.endsWith(":") || program.endsWith("-f")
          neverThrowsRaw(ws, if (attached) s"$program'$arg' a.txt" else s"$program '$arg'", None)
        }
        neverThrowsRaw(ws, "ls", Some(arg))
      }
      // Windows device names: `type con` would wait on the console, so only as a working directory and to `dir`
      Seq("con", "aux:", "CON.txt").foreach { device =>
        neverThrowsRaw(ws, s"dir '$device'", None)
        neverThrowsRaw(ws, "ls", Some(device))
      }
    }
  }
}

object WorkspaceCommandArgumentsSpec {

  val isWindowsHost = System.getProperty("os.name").startsWith("Windows")

  val ReadOnly  = WorkspaceSandboxConfig(allowedCommands = WorkspaceSandboxConfig.ReadOnlyCommands)
  val ReadWrite = WorkspaceSandboxConfig.Permissive

  val ArgumentNotAllowed    = "ARGUMENT_NOT_ALLOWED"
  val PathEscape            = "PATH_ESCAPE_ATTEMPT"
  val EnvironmentNotAllowed = "ENVIRONMENT_NOT_ALLOWED"
  val PolicyCodes           = Set(ArgumentNotAllowed, PathEscape, EnvironmentNotAllowed)

  /** A workspace `parent/workspace` with a sibling `parent/outside` holding `secret.txt`. */
  final class Fixture extends AutoCloseable {
    val parent: Path  = Files.createTempDirectory("ws-args")
    val root: Path    = Files.createDirectory(parent.resolve("workspace"))
    val outside: Path = Files.createDirectory(parent.resolve("outside"))
    write(outside.resolve("secret.txt"), "secret\n")
    write(root.resolve("a.txt"), "b\na\na\nc\n")
    write(root.resolve("b.txt"), "b\na\na\nc\n")
    write(root.resolve("victim.txt"), "keep me\n")
    Files.createDirectory(root.resolve("sub"))
    write(root.resolve("sub").resolve("Main.scala"), "object Main\n")

    def interface(config: WorkspaceSandboxConfig, windows: Boolean = isWindowsHost) =
      new WorkspaceAgentInterfaceImpl(root.toString, windows, Some(config))

    /** `{out}` in a command is the outside directory's absolute path. */
    def expand(command: String): String = command.replace("{out}", outside.toString)

    /** A link `name` in the workspace to `target`; making one can fail (Windows without the privilege). */
    def link(name: String, target: Path): Try[Path] = Try(Files.createSymbolicLink(root.resolve(name), target))

    override def close(): Unit = {
      def delete(p: Path): Unit = {
        if (Files.isDirectory(p) && !Files.isSymbolicLink(p))
          Using.resource(Files.list(p))(_.iterator().asScala.foreach(delete))
        Files.deleteIfExists(p)
      }
      delete(parent)
    }
  }

  def write(p: Path, s: String): Unit = { Files.write(p, s.getBytes(StandardCharsets.UTF_8)); () }

  val refusedOptions: Seq[String] = Seq(
    // find: deletes, runs a program, writes a file, reads starting points from a file, follows every link
    "find . -name victim.txt -delete",
    "find . -name victim.txt -exec true {} +",
    "find . -name victim.txt -execdir true {} +",
    "find . -name victim.txt -ok true {} +",
    "find . -name victim.txt -okdir true {} +",
    "find . -fprint out.txt",
    "find . -fprint0 out.txt",
    "find . -fprintf out.txt x",
    "find . -fls out.txt",
    "find -files0-from a.txt",
    "find . -follow -name a.txt",
    "find -L . -name a.txt",
    "find -HL . -name a.txt",
    "find . -name x -- -delete",
    // git: a subcommand that is not a read, a global option, an option that writes a file or runs a program
    "git clean -n",
    "git clean -fdx",
    "git checkout -- .",
    "git reset --hard",
    "git config user.name x",
    "git stash",
    "git commit -m x",
    "git push",
    "git fetch",
    "git help log",
    "git -c alias.x=!true x",
    "git -c core.pager=true log",
    "git --no-pager -c core.pager=true log",
    "git --exec-path=. status",
    "git -C . status",
    "git --git-dir=.git status",
    "git --work-tree=. status",
    "git -p log",
    "git diff --output=out.txt",
    "git diff --outp=out.txt",
    "git diff --output out.txt",
    "git log -p --output=out.txt",
    "git show --output=out.txt",
    "git diff --ext-diff",
    "git log -p --ext-diff",
    "git diff --textconv",
    "git show --show-signature",
    "git log --show-signature",
    "git grep -O x",
    "git grep -nO x",
    "git grep --open-files-in-pager x",
    "git grep --open-files=true x",
    "git grep --textconv x",
    "git blame --textconv a.txt",
    "git branch evil",
    "git branch -d main",
    "git branch -D main",
    "git branch -m main other",
    "git branch -c main other",
    "git branch --set-upstream-to=x",
    "git branch --edit-description",
    // sort: writes a file, runs a program, reads names from a file
    "sort -o out.txt a.txt",
    "sort -ro out.txt a.txt",
    "sort -oout.txt a.txt",
    "sort --output=out.txt a.txt",
    "sort --outp=out.txt a.txt",
    "sort a.txt -o out.txt",
    "sort -- -o out.txt",
    "sort --compress-program=true a.txt",
    "sort --files0-from=a.txt",
    // uniq: a second operand is written
    "uniq a.txt out.txt",
    "uniq -c a.txt out.txt",
    "uniq -f 1 a.txt out.txt",
    "uniq -f1 a.txt out.txt",
    "uniq --skip-fields 1 a.txt out.txt",
    "uniq -- a.txt out.txt",
    "uniq - out.txt",
    // wc: reads the names of the files to count from a file
    "wc --files0-from=a.txt",
    "wc --files0=a.txt",
    // ls and grep: follow every link
    "ls -L",
    "ls -lL",
    "ls --dereference",
    "ls --deref",
    "grep -R x .",
    "grep -rR x .",
    "grep --dereference-recursive x .",
    // hostname: sets the host name
    "hostname llm4s-evil-name",
    "hostname -F a.txt",
    "hostname --file=a.txt"
  )

  val refusedPaths: Seq[String] = Seq(
    "cat '{out}/secret.txt'",
    "cat ../outside/secret.txt",
    "cat sub/../../outside/secret.txt",
    "head -n 1 '{out}/secret.txt'",
    "tail -n 1 ../outside/secret.txt",
    "wc -l '{out}/secret.txt'",
    "grep -r secret ..",
    "grep -r secret '{out}'",
    "grep --file='{out}/secret.txt' a.txt",
    "grep -f'{out}/secret.txt' a.txt",
    "grep -f ../outside/secret.txt a.txt",
    "sort '{out}/secret.txt'",
    "uniq ../outside/secret.txt",
    "diff a.txt '{out}/secret.txt'",
    "diff --to-file=../outside/secret.txt a.txt",
    "find '{out}'",
    "find .. -name secret.txt",
    "find . -newer ../outside/secret.txt",
    "ls ..",
    "ls '{out}'",
    "git diff --no-index a.txt ../outside/secret.txt",
    "git log -- ../outside",
    "git grep --no-index secret -- ../outside"
  )

  /**
   * Arguments Windows cannot parse as a path, whose part before the first such character is inside but whose `..`
   * after it climbs out once Win32 removes it as text.
   */
  val unparseableEscapes: Seq[String] = Seq(
    "type 'x*\\..\\..\\outside\\secret.txt'",
    "type 'x?\\..\\..\\outside\\secret.txt'",
    "type 'ab:c\\..\\..\\outside\\secret.txt'",
    "type 'x*\\..\\.. \\outside\\secret.txt'",
    "findstr secret 'x*\\..\\..\\outside\\secret.txt'",
    "findstr /G:'x?\\..\\..\\outside\\secret.txt' a.txt",
    "findstr secret 'ab:c\\..\\..\\outside\\secret.txt'",
    "dir 'x*\\..\\..\\outside'",
    "dir 'x?\\..\\..\\outside\\*'",
    "dir 'ab:c\\..\\..\\outside'",
    "git diff --no-index a.txt 'x*\\..\\..\\outside\\secret.txt'",
    "git log -- 'x?\\..\\..\\outside'"
  )

  /**
   * Arguments holding `"`, which Windows' argv parser and cmd.exe delete as a quote toggle: `"..` opens `..`, and a
   * leading `"` hides an absolute path (`{out}` is the outside directory). Refused on Windows whatever follows.
   */
  val quotedEscapes: Seq[String] = Seq(
    "type 'x\"\\..\\..\\outside\\secret.txt'",
    "type '\"..\\outside\\secret.txt'",
    "type '.\".\\outside\\secret.txt'",
    "type '\"{out}\\secret.txt'",
    "findstr secret '\"{out}/secret.txt'",
    "findstr /G:'\"{out}/secret.txt' a.txt",
    "git diff --no-index a.txt '\"{out}/secret.txt'",
    "type 'a\"\"{out}/secret.txt'",
    "type '\"\\\\?\\C:\\outside\\secret.txt'",
    "dir '\"..'"
  )

  /**
   * A routed built-in's argument holding a cmd.exe delimiter that `ProcessBuilder` does not quote: cmd.exe splits the
   * argument there, so the part after it, unchecked, is a second name.
   */
  val cmdDelimiterEscapes: Seq[String] = Seq(
    "type 'a.txt,..\\outside\\secret.txt'",
    "type 'a.txt=..\\outside\\secret.txt'",
    "type 'a.txt=C:\\outside\\secret.txt'",
    "type ',C:\\outside\\secret.txt'",
    "dir 'a,..\\..'",
    "dir '=..\\outside'",
    "TYPE 'a.txt,..\\outside\\secret.txt'"
  )

  /** Ordinary built-in uses, and text for `echo`: not refused. */
  val cmdDelimiterControls: Seq[String] = Seq(
    "dir *.txt",
    "dir /S /B",
    "type a.txt",
    "type 'my file.txt'",
    "echo Hello, world",
    "echo a=b (c)"
  )

  /** Wildcards, switches and `:` values that stay inside: not refused. */
  val unparseableControls: Seq[String] = Seq(
    "dir *.txt",
    "dir 'sub\\*.scala'",
    "findstr /C:x a.txt",
    "findstr /S /I x *.txt",
    "type a?.txt",
    "git show HEAD:a.txt"
  )

  /**
   * A `sort` whose input `{out}/secret.txt` follows a `-t` (or `--field-separator`) that is not an option: an earlier
   * option took it as its value, so the next argument is a file sort reads (#1763). BSD sort (macOS) printed the
   * secret for each form it accepts; GNU sort reads it in the same forms (and through `-y`, which BSD lacks).
   */
  val sortValueEscapes: Seq[String] = Seq(
    "sort -T -t '{out}/secret.txt'",
    "sort --random-source -t '{out}/secret.txt'",
    "sort -T-t '{out}/secret.txt'",
    "sort -rT -t '{out}/secret.txt'",
    "sort --temporary-directory -t '{out}/secret.txt'",
    "sort --temp -t '{out}/secret.txt'",
    "sort --random-sou -t '{out}/secret.txt'",
    "sort --random-source --field-separator '{out}/secret.txt'",
    "sort --random-source --field-sep '{out}/secret.txt'",
    "sort -S -t '{out}/secret.txt'",
    "sort --buffer-size -t '{out}/secret.txt'",
    "sort --parallel -t '{out}/secret.txt'",
    "sort --batch-size -t '{out}/secret.txt'",
    "sort -k -t '{out}/secret.txt'",
    "sort --key -t '{out}/secret.txt'",
    "sort --sort -t '{out}/secret.txt'",
    "sort -yt '{out}/secret.txt'",
    "sort -T +0 -1t '{out}/secret.txt'",
    "sort -t, -T -t '{out}/secret.txt'"
  )

  val refusedEnvironment: Seq[(String, String)] = Seq(
    "git diff"   -> "GIT_EXTERNAL_DIFF",
    "git log"    -> "GIT_PAGER",
    "git status" -> "GIT_CONFIG_COUNT",
    "git status" -> "GIT_DIR",
    "git log"    -> "PAGER",
    "ls"         -> "LD_PRELOAD",
    "ls"         -> "DYLD_INSERT_LIBRARIES",
    "ls"         -> "PATH",
    "git status" -> "HOME",
    "sort a.txt" -> "GCONV_PATH"
  )
}
