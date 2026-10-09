package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
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
  ): WorkspaceAgentException = {
    val ex = the[WorkspaceAgentException] thrownBy
      ws.executeCommand(command, workingDirectory, Some(5.seconds), environment)
    withClue(s"'$command' -> ${ex.code}: ${ex.error}\n") {
      ex.code shouldBe code
    }
    ex
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
      refuses(fx.interface(ReadOnly), fx.expand(command), PathEscape)
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
      val before = Files.getPosixFilePermissions(fx.outside.resolve("secret.txt"))
      val ws     = fx.interface(ReadWrite)
      Seq("chmod -RL 700 d", "chmod -R -L 700 d", "chmod -RH 700 d", "chmod --dereference 700 a.txt")
        .foreach(command => refuses(ws, command, ArgumentNotAllowed))
      Files.getPosixFilePermissions(fx.outside.resolve("secret.txt")) shouldBe before
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
  // Cost of the path check

  it should "refuse an over-long argument instead of walking it" in inWorkspace { fx =>
    val ws    = fx.interface(ReadOnly)
    val start = System.nanoTime()
    refuses(ws, "cat " + ("x/" * 3000) + ("../" * 3000) + "a.txt", ArgumentNotAllowed)
    // 64 relative paths of 1000 components each: every one is inside, but walking them all costs too much
    refuses(ws, ("ls" +: Seq.fill(64)("a/" * 1000)).mkString(" "), ArgumentNotAllowed)
    // an option whose every tail is a path: the first absolute tail is refused without walking the rest
    refuses(ws, "ls -x" + ("a/" * 2040), PathEscape)
    (System.nanoTime() - start).nanos should be < 2.seconds
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
    Seq(
      "sort --random-source='{out}/secret.txt' a.txt",
      "sort --random-source=escape/secret.txt a.txt",
      "sort --random-source=../outside/secret.txt a.txt",
      "sort -t/ '{out}/secret.txt'",
      "sort -t / '{out}/secret.txt'",
      "sort -Tt '{out}/secret.txt'",
      "sort --field-separator / '{out}/secret.txt'",
      "grep --exclude-from='{out}/secret.txt' x a.txt",
      "grep --exclude-from=escape/secret.txt x a.txt",
      "git log --grep=escape/secret.txt",
      "git branch --merged '{out}'",
      "cat -- --x=/../../outside/secret.txt"
    ).foreach(command => refuses(ws, fx.expand(command), PathEscape))
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
