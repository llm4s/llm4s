package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{ Files, Path, Paths, StandardCopyOption }
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * The runner starts a program by an absolute path it found in a trusted directory, never by its bare name (#1790).
 * On Windows `CreateProcess` searched the runner's current directory before the system directory, and on POSIX the
 * JDK read a relative or empty `PATH` entry from the command's working directory, inside the workspace; either let a
 * program an agent wrote run in place of an allowlisted one.
 *
 * The resolver is tested with an injected search over temporary directories, in both platforms' flavours, on any
 * host; the end-to-end checks run the real programs of the host they are on.
 */
class ProgramResolverSpec extends AnyFlatSpec with Matchers {
  import ProgramResolverSpec._

  private def inDirs[A](f: Dirs => A): A = Using.resource(new Dirs)(f)

  private def posix(path: Seq[Any], cwd: Option[Path] = None, java: Option[Path] = None): ProgramSearch =
    ProgramSearch(windows = false, Nil, Some(path.mkString(File.pathSeparator)), cwd, java)

  private def windows(
    d: Dirs,
    path: Seq[Any],
    cwd: Option[Path] = None,
    java: Option[Path] = None
  ): ProgramSearch =
    ProgramSearch(windows = true, Seq(d.system32, d.windowsDir), Some(path.mkString(File.pathSeparator)), cwd, java)

  private def code(result: Either[CommandPolicy.Refusal, Path]): Option[String] = result.left.toOption.map(_.code)

  // ---------------------------------------------------------------------------------------------------------------
  // POSIX: PATH in order, absolute entries outside the workspace only

  "ProgramResolver on POSIX" should "find the first executable on PATH and return its absolute path" in inDirs { d =>
    val first  = d.program(d.trusted1, "cat")
    val second = d.program(d.trusted2, "cat")
    ProgramResolver.resolve("cat", posix(Seq(d.trusted1, d.trusted2)), d.roots) shouldBe Right(first)
    ProgramResolver.resolve("cat", posix(Seq(d.trusted2, d.trusted1)), d.roots) shouldBe Right(second)
    first.isAbsolute shouldBe true
  }

  it should "skip empty and relative PATH entries, which the JDK read from the command's working directory" in
    inDirs { d =>
      // The agent's `cat` sits where `.`, `` (empty) and `bin` would lead from the workspace
      d.program(d.workspace, "cat")
      Files.createDirectories(d.workspace.resolve("bin"))
      d.program(d.workspace.resolve("bin"), "cat")
      val real = d.program(d.trusted1, "cat")
      Seq(Seq(".", d.trusted1), Seq("", d.trusted1), Seq("bin", d.trusted1)).foreach { path =>
        withClue(path.mkString(":")) {
          ProgramResolver.resolve("cat", posix(path), d.roots) shouldBe Right(real)
        }
      }
      // Only there: refused, and the message says where it was found
      Seq(Seq("."), Seq(""), Seq("bin")).foreach { path =>
        val refused = ProgramResolver.resolve("cat", posix(path), d.roots).left.toOption.get
        refused.code shouldBe "EXECUTABLE_NOT_ALLOWED"
        refused.message should include(d.workspace.getFileName.toString)
      }
    }

  it should "never search a PATH entry inside the workspace, as written or through a link" in inDirs { d =>
    val tools = Files.createDirectories(d.workspace.resolve("tools"))
    d.program(tools, "cat")
    val real = d.program(d.trusted1, "cat")
    ProgramResolver.resolve("cat", posix(Seq(tools, d.trusted1)), d.roots) shouldBe Right(real)
    ProgramResolver.resolve("cat", posix(Seq(d.workspace, d.trusted1)), d.roots) shouldBe Right(real)
    // A directory outside that is a link into the workspace
    d.link(d.base.resolve("tools-link"), tools).foreach { viaLink =>
      ProgramResolver.resolve("cat", posix(Seq(viaLink, d.trusted1)), d.roots) shouldBe Right(real)
    }
    code(ProgramResolver.resolve("cat", posix(Seq(tools)), d.roots)) shouldBe Some("EXECUTABLE_NOT_ALLOWED")
  }

  it should "never run a file in a trusted directory that is a link into the workspace" in inDirs { d =>
    val agents = d.program(d.workspace, "cat")
    d.link(d.trusted1.resolve("cat"), agents).foreach { _ =>
      val real = d.program(d.trusted2, "cat")
      ProgramResolver.resolve("cat", posix(Seq(d.trusted1, d.trusted2)), d.roots) shouldBe Right(real)
    }
  }

  it should "never search the runner's own working directory, even on PATH" in inDirs { d =>
    d.program(d.runnerCwd, "cat")
    val real = d.program(d.trusted1, "cat")
    ProgramResolver.resolve("cat", posix(Seq(d.runnerCwd, d.trusted1), cwd = Some(d.runnerCwd)), d.roots) shouldBe
      Right(real)
    code(ProgramResolver.resolve("cat", posix(Seq(d.runnerCwd), cwd = Some(d.runnerCwd)), d.roots)) shouldBe
      Some("EXECUTABLE_NOT_ALLOWED")
  }

  it should "skip a file that is not executable, and refuse a program found nowhere" in inDirs { d =>
    assume(!isWindowsHost, "the executable bit is a POSIX notion")
    write(d.trusted1.resolve("cat"), "not a program")
    val real = d.program(d.trusted2, "cat")
    ProgramResolver.resolve("cat", posix(Seq(d.trusted1, d.trusted2)), d.roots) shouldBe Right(real)
    code(ProgramResolver.resolve("nosuchprogram", posix(Seq(d.trusted1)), d.roots)) shouldBe
      Some("EXECUTABLE_NOT_FOUND")
    code(ProgramResolver.resolve("cat", ProgramSearch(false, Nil, None, None, None), d.roots)) shouldBe
      Some("EXECUTABLE_NOT_FOUND")
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Windows: system directory, Windows directory, then PATH; `.exe` then `.com`; never the current or Java directory

  "ProgramResolver on Windows" should "prefer the system directory, then the Windows directory, then PATH" in
    inDirs { d =>
      val onPath   = d.program(d.trusted1, "sort.exe")
      val windir   = d.program(d.windowsDir, "sort.exe")
      val system32 = d.program(d.system32, "sort.exe")
      ProgramResolver.resolve("sort", windows(d, Seq(d.trusted1)), d.roots) shouldBe Right(system32)
      Files.delete(system32)
      ProgramResolver.resolve("sort", windows(d, Seq(d.trusted1)), d.roots) shouldBe Right(windir)
      Files.delete(windir)
      ProgramResolver.resolve("sort", windows(d, Seq(d.trusted1)), d.roots) shouldBe Right(onPath)
    }

  it should "look for <name>.exe, then <name>.com, and nothing PATHEXT adds" in inDirs { d =>
    val com = d.program(d.trusted1, "tool.com")
    write(d.trusted1.resolve("tool.bat"), "")
    write(d.trusted1.resolve("tool.cmd"), "")
    write(d.trusted1.resolve("tool"), "")
    ProgramResolver.resolve("tool", windows(d, Seq(d.trusted1)), d.roots) shouldBe Right(com)
    val exe = d.program(d.trusted1, "tool.exe")
    ProgramResolver.resolve("tool", windows(d, Seq(d.trusted1)), d.roots) shouldBe Right(exe)
    write(d.trusted2.resolve("other.bat"), "")
    code(ProgramResolver.resolve("other", windows(d, Seq(d.trusted2)), d.roots)) shouldBe Some("EXECUTABLE_NOT_FOUND")
  }

  it should "never run a git.exe from the runner's current directory or Java directory (the issue)" in inDirs { d =>
    // CreateProcess would search both before PATH, where Git for Windows is
    d.program(d.runnerCwd, "git.exe")
    d.program(d.javaDir, "git.exe")
    val real   = d.program(d.trusted1, "git.exe")
    val search = windows(d, Seq(d.trusted1), cwd = Some(d.runnerCwd), java = Some(d.javaDir))
    ProgramResolver.resolve("git", search, d.roots) shouldBe Right(real)
    // Without the real one: refused, never the stub
    Files.delete(real)
    code(ProgramResolver.resolve("git", search, d.roots)) shouldBe Some("EXECUTABLE_NOT_ALLOWED")
  }

  it should "never run a cat.exe from the workspace, nor a stub shadowing sort or findstr (#1738)" in inDirs { d =>
    d.program(d.workspace, "cat.exe")
    d.program(d.workspace, "sort.exe")
    val sort   = d.program(d.system32, "sort.exe")
    val search = windows(d, Seq(d.workspace), cwd = Some(d.workspace))
    ProgramResolver.resolve("sort", search, d.roots) shouldBe Right(sort)
    code(ProgramResolver.resolve("cat", search, d.roots)) shouldBe Some("EXECUTABLE_NOT_ALLOWED")
  }

  it should "search java's directory when it is on PATH, and the system directory even as the current one" in
    inDirs { d =>
      val java = d.program(d.javaDir, "keytool.exe")
      ProgramResolver.resolve("keytool", windows(d, Seq(d.javaDir), java = Some(d.javaDir)), d.roots) shouldBe
        Right(java)
      // A Windows service starts in System32: that does not make the system directory untrusted
      val where = d.program(d.system32, "where.exe")
      ProgramResolver.resolve("where", windows(d, Nil, cwd = Some(d.system32)), d.roots) shouldBe Right(where)
    }

  it should "accept a PATH entry in double quotes" in inDirs { d =>
    val real = d.program(d.trusted1, "git.exe")
    ProgramResolver.resolve("git", windows(d, Seq("\"" + d.trusted1 + "\"")), d.roots) shouldBe Right(real)
  }

  it should "take cmd.exe, which runs the built-ins, from the system directory only" in inDirs { d =>
    d.program(d.trusted1, "cmd.exe")
    d.program(d.runnerCwd, "cmd.exe")
    val search = windows(d, Seq(d.trusted1), cwd = Some(d.runnerCwd))
    code(ProgramResolver.resolveSystem("cmd", search, d.roots)) shouldBe Some("EXECUTABLE_NOT_ALLOWED")
    val cmd = d.program(d.system32, "cmd.exe")
    ProgramResolver.resolveSystem("cmd", search, d.roots) shouldBe Right(cmd)
  }

  // ---------------------------------------------------------------------------------------------------------------
  // Through the runner

  "The runner" should "start the program by its absolute path" in inDirs { d =>
    val ws       = new WorkspaceAgentInterfaceImpl(d.workspace.toString, isWindowsHost, Some(ReadOnly))
    val prepared = ws.prepareCommand("git --version", None, None, None)
    prepared.foreach { p =>
      val argv0 = p.builder.command().get(0)
      Paths.get(argv0).isAbsolute shouldBe true
      Paths.get(argv0).getFileName.toString.toLowerCase should startWith("git")
    }
    prepared.left.toOption.map(_.code).foreach(_ shouldBe "EXECUTABLE_NOT_FOUND") // a host without git
  }

  it should "not run a fake cat that a PATH entry inside the workspace, or a relative one, would find first" in
    inDirs { d =>
      assume(!isWindowsHost, "the POSIX programs are not on a Windows runner's PATH")
      write(d.workspace.resolve("a.txt"), "real\n")
      val marker = d.base.resolve("fake-cat-ran")
      val fake   = s"#!/bin/sh\necho fake > '$marker'\necho fake\n"
      Files.createDirectories(d.workspace.resolve("bin"))
      d.script(d.workspace, "cat", fake)
      d.script(d.workspace.resolve("bin"), "cat", fake)
      val hostPath = ProgramSearch.host().path.getOrElse("/usr/bin:/bin")
      Seq(s"${d.workspace}:$hostPath", s".:$hostPath", s":$hostPath", s"bin:$hostPath").foreach { path =>
        withClue(path) {
          val search = ProgramSearch(windows = false, Nil, Some(path), Some(d.runnerCwd), None)
          val ws     = new WorkspaceAgentInterfaceImpl(d.workspace.toString, false, Some(ReadOnly), search)
          val out    = ws.executeCommand("cat a.txt", None, Some(30.seconds), None)
          out.stdout shouldBe "real\n"
          Files.exists(marker) shouldBe false
        }
      }
      // With only the workspace on PATH, the command is refused rather than the fake run
      val only = new WorkspaceAgentInterfaceImpl(
        d.workspace.toString,
        false,
        Some(ReadOnly),
        ProgramSearch(windows = false, Nil, Some(d.workspace.toString), None, None)
      )
      val refused = the[WorkspaceAgentException] thrownBy only.executeCommand("cat a.txt", None, Some(30.seconds), None)
      refused.code shouldBe "EXECUTABLE_NOT_ALLOWED"
      Files.exists(marker) shouldBe false
    }

  it should "apply a program's rules to an allowlist entry or command spelled with .exe on Windows (#1761)" in
    inDirs { d =>
      write(d.workspace.resolve("a.txt"), "b\na\n")
      def refusal(allowed: Set[String], command: String): Option[String] = {
        val ws = new WorkspaceAgentInterfaceImpl(
          d.workspace.toString,
          true,
          Some(WorkspaceSandboxConfig(allowedCommands = allowed))
        )
        ws.prepareCommand(command, None, None, None).left.toOption.map(_.code)
      }
      // `sort.exe` on the allowlist is `sort`: its writing switches stay refused
      refusal(Set("sort.exe"), "sort /O out.txt a.txt") shouldBe Some("ARGUMENT_NOT_ALLOWED")
      refusal(Set("sort.exe"), "sort.exe /O out.txt a.txt") shouldBe Some("ARGUMENT_NOT_ALLOWED")
      refusal(Set("SORT.EXE"), "Sort.Exe -o out.txt a.txt") shouldBe Some("ARGUMENT_NOT_ALLOWED")
      refusal(Set("sort"), "sort.com /O out.txt a.txt") shouldBe Some("ARGUMENT_NOT_ALLOWED")
      // and git's: only read subcommands, no -c
      refusal(Set("git.exe"), "git.exe -c core.pager=x log") shouldBe Some("ARGUMENT_NOT_ALLOWED")
      refusal(Set("git.exe"), "git clean -fdx") shouldBe Some("ARGUMENT_NOT_ALLOWED")
      // a routed built-in spelled with .exe still gets the cmd.exe checks
      refusal(Set("type"), "type.exe a.txt,..\\x") shouldBe Some("ARGUMENT_NOT_ALLOWED")
      // other names are still refused
      refusal(Set("sort.exe"), "sorter a.txt") shouldBe Some("EXECUTABLE_NOT_ALLOWED")
    }

  it should "route a built-in through the system directory's cmd.exe, naming the built-in without .exe" in
    inDirs { d =>
      val cmd    = d.program(d.system32, "cmd.exe")
      val search = ProgramSearch(windows = true, Seq(d.system32, d.windowsDir), None, Some(d.runnerCwd), None)
      val ws     = new WorkspaceAgentInterfaceImpl(d.workspace.toString, true, Some(ReadOnly), search)
      val argv   = ws.prepareCommand("ECHO.EXE hello", None, None, None).fold(e => fail(e.error), identity)
      // `/d`: cmd.exe runs no AutoRun command from the registry first (#1792)
      argv.builder.command().asScala.toSeq shouldBe Seq(cmd.toString, "/d", "/c", "echo", "hello")
      argv.builder.environment().get("NoDefaultCurrentDirectoryInExePath") shouldBe "1"
      // No cmd.exe in the system directory: refused, never one from PATH or the current directory
      Files.delete(cmd)
      d.program(d.runnerCwd, "cmd.exe")
      ws.prepareCommand("dir", None, None, None).left.map(_.code) shouldBe Left("EXECUTABLE_NOT_ALLOWED")
    }

  // ---------------------------------------------------------------------------------------------------------------
  // The child's PATH (#1792): only the entries the runner itself would search

  "ProgramResolver.childPath" should "drop empty, relative and in-workspace entries, keeping the rest in order" in
    inDirs { d =>
      val tools = Files.createDirectories(d.workspace.resolve("tools"))
      val path  = Seq(d.trusted1, "", ".", "bin", tools, d.workspace, d.trusted2, "..", d.trusted1)
      ProgramResolver.childPath(posix(path), d.roots) shouldBe
        Some(Seq(d.trusted1, d.trusted2).mkString(File.pathSeparator))
    }

  it should "drop an entry that leads into the workspace through a link, and the runner's own directory" in
    inDirs { d =>
      val tools = Files.createDirectories(d.workspace.resolve("tools"))
      val links = d.link(d.base.resolve("tools-link"), tools).toSeq
      val path  = links ++ Seq(d.runnerCwd, d.trusted1)
      ProgramResolver.childPath(posix(path, cwd = Some(d.runnerCwd)), d.roots) shouldBe Some(d.trusted1.toString)
    }

  it should "fall back to the system directories when no entry is kept, never to no PATH (#1805)" in inDirs { d =>
    // Without PATH, bash and sh search a built-in default ending in `.`: the workspace. (`/usr/bin` is not an
    // absolute path on a Windows host, so the POSIX fallback is checked on a POSIX one.)
    if (!isWindowsHost) {
      val posixFallback = Some(Seq("/usr/bin", "/bin").mkString(File.pathSeparator))
      ProgramResolver.childPath(posix(Seq("", ".", "bin", d.workspace)), d.roots) shouldBe posixFallback
      ProgramResolver.childPath(ProgramSearch(false, Nil, None, None, None), d.roots) shouldBe posixFallback
    }
    // On Windows: the system and Windows directories, which the runner searches first anyway
    val windowsFallback = Some(Seq(d.system32, d.windowsDir).mkString(File.pathSeparator))
    ProgramResolver.childPath(windows(d, Seq("", ".", d.workspace)), d.roots) shouldBe windowsFallback
    ProgramResolver.childPath(ProgramSearch(true, Seq(d.system32, d.windowsDir), None, None, None), d.roots) shouldBe
      windowsFallback
  }

  it should "give no PATH only when even the fallback directories are untrusted, never an empty one" in inDirs { d =>
    // A workspace at the file system's root holds /usr/bin and /bin; an empty PATH would be read as `.`
    val root = Seq(d.workspace.getRoot)
    ProgramResolver.childPath(posix(Seq(".", "")), root) shouldBe None
    ProgramResolver.childPath(ProgramSearch(true, Seq(d.system32), Some("."), None, None), Seq(d.base)) shouldBe None
  }

  it should "drop an entry not made yet under a link into the workspace, judged by where the link leads (#1805)" in
    inDirs { d =>
      d.link(d.base.resolve("tools-link"), d.workspace).foreach { viaLink =>
        val notYet = viaLink.resolve("later").resolve("bin")
        ProgramResolver.childPath(posix(Seq(notYet, d.trusted1)), d.roots) shouldBe Some(d.trusted1.toString)
        // where the agent makes it later, a child would find the agent's program first
        Files.createDirectories(d.workspace.resolve("later").resolve("bin"))
        ProgramResolver.childPath(posix(Seq(notYet, d.trusted1)), d.roots) shouldBe Some(d.trusted1.toString)
      }
      // an entry not made yet under a trusted directory is kept, as written
      val pending = d.trusted2.resolve("not-yet")
      ProgramResolver.childPath(posix(Seq(pending, d.trusted1)), d.roots) shouldBe
        Some(Seq(pending, d.trusted1).mkString(File.pathSeparator))
    }

  it should "give a child a Windows PATH entry that was in double quotes without them" in inDirs { d =>
    val search = windows(d, Seq("\"" + d.trusted1 + "\"", ".", d.workspace, d.trusted2))
    ProgramResolver.childPath(search, d.roots) shouldBe Some(Seq(d.trusted1, d.trusted2).mkString(File.pathSeparator))
  }

  "The runner" should "give the command the sanitised PATH, or none, and keep NoDefaultCurrentDirectoryInExePath " +
    "for every Windows command (#1792)" in inDirs { d =>
      d.program(d.system32, "whoami.exe")
      val path = Seq(".", "", "bin", d.workspace, d.trusted1)
      val search = ProgramSearch(
        windows = true,
        Seq(d.system32, d.windowsDir),
        Some(path.mkString(File.pathSeparator)),
        Some(d.runnerCwd),
        None
      )
      val ws  = new WorkspaceAgentInterfaceImpl(d.workspace.toString, true, Some(ReadOnly), search)
      val env = ws.prepareCommand("whoami", None, None, None).fold(e => fail(e.error), _.builder.environment())
      env.get("PATH") shouldBe d.trusted1.toString
      // not only for cmd.exe: CreateProcess in any child searches its current directory, the workspace, otherwise
      env.get("NoDefaultCurrentDirectoryInExePath") shouldBe "1"

      // No entry kept: the system directories, where sh or a child's own search would otherwise use a default
      // ending in `.` (#1805)
      val onlyWorkspace = ProgramSearch(windows = true, Seq(d.system32, d.windowsDir), Some("."), None, None)
      val bare          = new WorkspaceAgentInterfaceImpl(d.workspace.toString, true, Some(ReadOnly), onlyWorkspace)
      val bareEnv = bare.prepareCommand("whoami", None, None, None).fold(e => fail(e.error), _.builder.environment())
      bareEnv.get("PATH") shouldBe Seq(d.system32, d.windowsDir).mkString(File.pathSeparator)
    }

  it should "leave NoDefaultCurrentDirectoryInExePath alone off Windows" in inDirs { d =>
    val cat = d.program(d.trusted1, "cat")
    val ws  = new WorkspaceAgentInterfaceImpl(d.workspace.toString, false, Some(ReadOnly), posix(Seq(d.trusted1)))
    val p   = ws.prepareCommand("cat x", None, None, None).fold(e => fail(e.error), identity)
    p.builder.command().get(0) shouldBe cat.toString
    p.builder.environment().get("PATH") shouldBe d.trusted1.toString
    // left as the runner's own environment has it (unset, bar a host that sets it itself)
    val inherited = new java.lang.ProcessBuilder().environment().get("NoDefaultCurrentDirectoryInExePath")
    p.builder.environment().get("NoDefaultCurrentDirectoryInExePath") shouldBe inherited
  }

  it should "not let a #!/usr/bin/env script run a workspace program that a relative PATH entry would find" in
    inDirs { d =>
      assume(!isWindowsHost, "a #! script is a POSIX notion")
      assume(Files.isExecutable(Paths.get("/usr/bin/env")), "the script's interpreter is /usr/bin/env")
      val marker = d.base.resolve("workspace-fake-ran")
      // The allowlisted program, in a trusted directory, looks `fake` up on its PATH
      d.script(d.trusted1, "prog", "#!/usr/bin/env fake\n")
      // The agent's `fake`, where the relative entry `bin`, `.`, an empty entry or an in-workspace entry lead
      val fake = s"#!/bin/sh\necho workspace > '$marker'\necho workspace\n"
      Files.createDirectories(d.workspace.resolve("bin"))
      d.script(d.workspace.resolve("bin"), "fake", fake)
      d.script(d.workspace, "fake", fake)
      // The real `fake`, in a trusted directory after them
      d.script(d.trusted2, "fake", "#!/bin/sh\necho trusted\n")
      val withProg = ReadOnly.copy(allowedCommands = ReadOnly.allowedCommands + "prog")
      Seq("bin", ".", "", d.workspace.toString).foreach { untrusted =>
        withClue(s"PATH entry '$untrusted'") {
          val path   = Seq(untrusted, d.trusted1, d.trusted2, "/usr/bin", "/bin").mkString(File.pathSeparator)
          val search = ProgramSearch(windows = false, Nil, Some(path), Some(d.runnerCwd), None)
          val ws     = new WorkspaceAgentInterfaceImpl(d.workspace.toString, false, Some(withProg), search)
          val out    = ws.executeCommand("prog", None, Some(30.seconds), None)
          out.stdout shouldBe "trusted\n"
          Files.exists(marker) shouldBe false
        }
      }
    }

  // ---------------------------------------------------------------------------------------------------------------
  // On a real Windows host: a stub in the runner's current directory never runs

  "On a Windows host the runner" should "run System32's whoami, not a whoami.exe in its current directory" in {
    assume(isWindowsHost, "CreateProcess searches the current directory only on Windows")
    val system32 = ProgramSearch.host().systemDirectories.head
    val hostname = system32.resolve("HOSTNAME.EXE")
    assume(Files.exists(hostname), "the stub is a copy of System32's hostname.exe")
    inDirs { d =>
      val ws       = new WorkspaceAgentInterfaceImpl(d.workspace.toString, true, Some(ReadOnly))
      val expected = ws.executeCommand("whoami", None, Some(30.seconds), None).stdout
      val host     = ws.executeCommand("hostname", None, Some(30.seconds), None).stdout
      assume(expected.trim.nonEmpty && expected.trim != host.trim, "whoami and hostname print the same here")
      // The stub prints the host name: where CreateProcess searched the current directory first, `whoami` printed it
      val cwd = Paths.get(System.getProperty("user.dir"))
      Using.resource(new Stub(cwd.resolve("whoami.exe"), hostname)) { _ =>
        Using.resource(new Stub(d.workspace.resolve("whoami.exe"), hostname)) { _ =>
          val out = ws.executeCommand("whoami", None, Some(30.seconds), None)
          out.exitCode shouldBe 0
          out.stdout shouldBe expected
          ws.prepareCommand("whoami", None, None, None)
            .map(p => Paths.get(p.builder.command().get(0)).getParent.toRealPath()) shouldBe Right(
            system32.toRealPath()
          )
        }
      }
    }
  }

  it should "start a command whose own CreateProcess does not search its current directory, the workspace (#1805)" in {
    assume(isWindowsHost, "CreateProcess searches the current directory only on Windows")
    val system32 = ProgramSearch.host().systemDirectories.head
    val hostname = system32.resolve("HOSTNAME.EXE")
    assume(Files.exists(hostname), "the stub is a copy of System32's hostname.exe")
    val javaExe = Paths.get(System.getProperty("java.home"), "bin", "java.exe")
    assume(Files.exists(javaExe), "the child is a Java program run from source")
    inDirs { d =>
      // A child that starts `whoami` by its bare name: the JDK hands it to CreateProcess with no application name
      val source = write(
        d.base.resolve("StartsWhoami.java"),
        """public class StartsWhoami {
          |  public static void main(String[] args) throws Exception {
          |    Process p = new ProcessBuilder("whoami").redirectErrorStream(true).start();
          |    System.out.print(new String(p.getInputStream().readAllBytes()));
          |    p.waitFor();
          |  }
          |}
          |""".stripMargin
      )
      def output(command: Seq[String], noCurrentDirectory: Boolean): String = {
        val builder = new java.lang.ProcessBuilder(command.asJava).directory(d.workspace.toFile)
        builder.redirectErrorStream(true)
        builder.redirectInput(java.lang.ProcessBuilder.Redirect.from(new File("NUL")))
        builder.environment().put("PATH", Seq(system32, system32.getParent).mkString(File.pathSeparator))
        if (noCurrentDirectory) builder.environment().put("NoDefaultCurrentDirectoryInExePath", "1")
        else builder.environment().remove("NoDefaultCurrentDirectoryInExePath")
        val process = builder.start()
        val out     = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        process.waitFor()
        out.trim
      }
      val host = output(Seq(hostname.toString), noCurrentDirectory = true)
      val user = output(Seq(system32.resolve("whoami.exe").toString), noCurrentDirectory = true)
      assume(user.nonEmpty && user != host, "whoami and hostname print the same here")
      Using.resource(new Stub(d.workspace.resolve("whoami.exe"), hostname)) { _ =>
        val child = Seq(javaExe.toString, source.toString)
        // Without the variable the child's CreateProcess finds the stub in its current directory: the check can tell
        output(child, noCurrentDirectory = false) shouldBe host
        // With it, as the runner starts every Windows command, the child runs System32's whoami
        output(child, noCurrentDirectory = true) shouldBe user
      }
    }
  }

  it should "run Git for Windows' git.exe, not a git.exe in its current directory or the workspace" in {
    assume(isWindowsHost, "CreateProcess searches the current directory only on Windows")
    val system32 = ProgramSearch.host().systemDirectories.head
    val hostname = system32.resolve("HOSTNAME.EXE")
    assume(Files.exists(hostname), "the stub is a copy of System32's hostname.exe")
    inDirs { d =>
      val ws = new WorkspaceAgentInterfaceImpl(d.workspace.toString, true, Some(ReadOnly))
      assume(ws.prepareCommand("git --version", None, None, None).isRight, "git is not installed")
      val cwd = Paths.get(System.getProperty("user.dir"))
      Using.resource(new Stub(cwd.resolve("git.exe"), hostname)) { _ =>
        Using.resource(new Stub(d.workspace.resolve("git.exe"), hostname)) { _ =>
          val out = ws.executeCommand("git --version", None, Some(30.seconds), None)
          out.exitCode shouldBe 0
          out.stdout should startWith("git version")
        }
      }
    }
  }
}

object ProgramResolverSpec {

  val isWindowsHost: Boolean = System.getProperty("os.name").startsWith("Windows")

  val ReadOnly: WorkspaceSandboxConfig =
    WorkspaceSandboxConfig(allowedCommands = WorkspaceSandboxConfig.ReadOnlyCommands)

  def write(p: Path, s: String): Path = Files.write(p, s.getBytes(StandardCharsets.UTF_8))

  /** A copy of `source` at `at`, removed on close. */
  final class Stub(at: Path, source: Path) extends AutoCloseable {
    Files.copy(source, at, StandardCopyOption.REPLACE_EXISTING)
    override def close(): Unit = { Files.deleteIfExists(at); () }
  }

  /** A workspace, the runner's working and Java directories, trusted PATH directories and Windows system ones. */
  final class Dirs extends AutoCloseable {
    val base: Path       = Files.createTempDirectory("program-resolver")
    val workspace: Path  = Files.createDirectory(base.resolve("workspace"))
    val runnerCwd: Path  = Files.createDirectory(base.resolve("runner-cwd"))
    val javaDir: Path    = Files.createDirectory(base.resolve("java-bin"))
    val trusted1: Path   = Files.createDirectory(base.resolve("usr-bin"))
    val trusted2: Path   = Files.createDirectory(base.resolve("bin"))
    val windowsDir: Path = Files.createDirectory(base.resolve("Windows"))
    val system32: Path   = Files.createDirectory(windowsDir.resolve("System32"))

    /** The workspace as the runner passes it: as written and as its real path. */
    def roots: Seq[Path] = Seq(workspace, workspace.toRealPath())

    /** An executable file `name` in `dir`. */
    def program(dir: Path, name: String): Path = script(dir, name, "#!/bin/sh\n")

    def script(dir: Path, name: String, body: String): Path = {
      val file = write(dir.resolve(name), body)
      Try(Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x")))
      file
    }

    /** A symbolic link, where the host lets the test make one. */
    def link(at: Path, target: Path): Option[Path] = Try(Files.createSymbolicLink(at, target)).toOption

    override def close(): Unit = {
      def delete(p: Path): Unit = {
        if (Files.isDirectory(p) && !Files.isSymbolicLink(p))
          Using.resource(Files.list(p))(_.iterator().asScala.foreach(delete))
        Files.deleteIfExists(p)
      }
      delete(base)
    }
  }
}
