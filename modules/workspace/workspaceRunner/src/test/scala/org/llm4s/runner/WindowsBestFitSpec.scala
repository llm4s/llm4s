package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.Charset
import java.nio.file.{ Files, Path }
import scala.concurrent.duration._
import scala.util.Using

/**
 * On Windows a program that reads its command line in an ANSI code page may receive a character as a different ASCII
 * one ("best fit", WorstFit, #1766): `..／..／outside` as `../../outside`. The runner's checks read UTF-16, so on
 * Windows such a character is refused in an argument, the working directory and an environment value.
 *
 * The simulated-Windows cases (`isWindows = true` on any host) fail on the previous runner, which let each of these
 * arguments through its checks; on a Windows host the last case shows the refusal there too.
 */
class WindowsBestFitSpec extends AnyFlatSpec with Matchers {
  import WorkspaceCommandArgumentsSpec.{
    isWindowsHost,
    write,
    ArgumentNotAllowed,
    EnvironmentNotAllowed,
    Fixture,
    ReadOnly
  }

  /** Fullwidth reverse solidus, fullwidth solidus, fullwidth quote, yen, won, division slash. */
  private val Separators = Seq('\uFF3C', '\uFF0F', '\uFF02', '\u00A5', '\u20A9', '\u2215')

  private def inWorkspace[A](f: Fixture => A): A = Using.resource(new Fixture)(f)

  private def refused(
    ws: WorkspaceAgentInterfaceImpl,
    command: String,
    code: String,
    workingDirectory: Option[String] = None,
    environment: Option[Map[String, String]] = None
  ): WorkspaceAgentException = {
    val ex =
      the[WorkspaceAgentException] thrownBy ws.executeCommand(command, workingDirectory, Some(5.seconds), environment)
    withClue(s"'$command' -> ${ex.code}: ${ex.error}\n")(ex.code shouldBe code)
    ex
  }

  private def policy(
    program: String,
    args: Seq[String],
    root: Path,
    windows: Boolean = true,
    ansi: Option[Charset] = None
  ): Option[CommandPolicy.Refusal] =
    CommandPolicy.refusal(program, args, windows, root, root, Map.empty, ansiCodePage = ansi)

  "The workspace runner on Windows" should "refuse a best-fit separator or quote in an operand" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly, windows = true)
    Separators.foreach { c =>
      val ex = refused(ws, s"findstr secret ..${c}outside${c}secret.txt", ArgumentNotAllowed)
      ex.error should include(f"U+${c.toInt}%04X")
      refused(ws, s"type ..${c}outside${c}secret.txt", ArgumentNotAllowed)
      refused(ws, s"sort ..${c}..${c}x", ArgumentNotAllowed)
    }
  }

  it should "refuse one in a value attached to an option or a switch" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly, windows = true)
    Separators.foreach { c =>
      refused(ws, s"findstr /G:..${c}outside${c}secret.txt a.txt", ArgumentNotAllowed)
      refused(ws, s"grep -f..${c}outside${c}secret.txt a.txt", ArgumentNotAllowed)
      refused(ws, s"grep --file=..${c}outside${c}secret.txt a.txt", ArgumentNotAllowed)
    }
  }

  it should "refuse one in the working directory below the workspace root" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly, windows = true)
    Separators.foreach { c =>
      val dir = s"sub${c}x"
      Files.createDirectory(fx.root.resolve(dir))
      val ex = refused(ws, "findstr a a.txt", ArgumentNotAllowed, workingDirectory = Some(dir))
      ex.error should include("working directory")
    }
  }

  it should "refuse one in an environment value" in inWorkspace { fx =>
    val ws = fx.interface(ReadOnly, windows = true)
    Separators.foreach { c =>
      refused(ws, "findstr a a.txt", EnvironmentNotAllowed, environment = Some(Map("LANG" -> s"..${c}..${c}x")))
    }
  }

  it should "refuse an option letter, dash or space a code page best-fits from another character" in inWorkspace { fx =>
    val root = fx.root.toRealPath()
    Seq(
      Seq("-\uFF4F", "out"),     // fullwidth o: `sort -o out` writes a file
      Seq("-\u00F6", "out"),     // o with diaeresis, `o` on code pages 932, 949, 950, 1251, 1253
      Seq("\u00ADo", "out"),     // soft hyphen, `-` on code pages 932, 936, 950
      Seq("a.txt\u3000/o", "x"), // ideographic space, which splits the argument in two
      Seq("/\uFF4F", "out")      // fullwidth o in a native switch: `/o`
    ).foreach { args =>
      withClue(args.mkString(" "))(policy("sort", args, root).map(_.code) shouldBe Some(ArgumentNotAllowed))
    }
  }

  it should "keep allowing accented letters in an operand, which only best-fit to letters" in inWorkspace { fx =>
    val root = fx.root.toRealPath()
    policy("findstr", Seq("caf\u00E9", "r\u00E9sum\u00E9.txt"), root) shouldBe None
    policy("type", Seq("M\u00FCller.txt"), root) shouldBe None
    policy(
      "findstr",
      Seq("x", "r\u00E9sum\u00E9.txt"),
      root,
      ansi = Some(Charset.forName("windows-1252"))
    ) shouldBe None
  }

  it should "refuse a character the host's ANSI code page cannot hold" in inWorkspace { fx =>
    val root   = fx.root.toRealPath()
    val cp1252 = Some(Charset.forName("windows-1252"))
    // Not in code page 1252, and in no best-fit table either: the program would get the default character, `?`
    policy("findstr", Seq("x", "\u4E02.txt"), root, ansi = cp1252).map(_.code) shouldBe Some(ArgumentNotAllowed)
    policy("findstr", Seq("x", "\uD83D\uDE00.txt"), root, ansi = cp1252).map(_.code) shouldBe Some(ArgumentNotAllowed)
    // `é` is not in code page 932, which best-fits it to `e`
    policy("findstr", Seq("x", "r\u00E9sum\u00E9.txt"), root, ansi = Some(Charset.forName("MS932")))
      .map(_.code) shouldBe Some(ArgumentNotAllowed)
    // The same name on a host whose code page holds it
    policy("findstr", Seq("x", "\u4E02.txt"), root, ansi = Some(Charset.forName("GBK"))) shouldBe None
  }

  it should "leave the arguments unchanged on POSIX, where nothing converts them" in inWorkspace { fx =>
    val root = fx.root.toRealPath()
    Separators.foreach { c =>
      policy("cat", Seq(s"..${c}..${c}x"), root, windows = false) shouldBe None
      policy("sort", Seq(s"-k1", s"a${c}b"), root, windows = false) shouldBe None
    }
    assume(!isWindowsHost, "the POSIX programs are not on a Windows runner's PATH")
    write(fx.root.resolve("a\uFF3Cb.txt"), "found\n")
    val out = fx.interface(ReadOnly, windows = false).executeCommand("cat a\uFF3Cb.txt", None, Some(30.seconds), None)
    out.exitCode shouldBe 0
    out.stdout should include("found")
  }

  it should "refuse them on a Windows host" in inWorkspace { fx =>
    assume(isWindowsHost, "only a Windows host converts arguments to an ANSI code page")
    val ws = fx.interface(ReadOnly, windows = true)
    Separators.foreach { c =>
      refused(ws, s"findstr secret ..${c}outside${c}secret.txt", ArgumentNotAllowed)
      refused(ws, s"type ..${c}outside${c}secret.txt", ArgumentNotAllowed)
    }
    // The host's own code page: a character it cannot hold reaches an ANSI program as `?` or a best fit
    WindowsBestFit.hostAnsiCodePage.filterNot(_.newEncoder().canEncode('\u4E02')).foreach { _ =>
      refused(ws, "findstr x \u4E02.txt", ArgumentNotAllowed)
    }
    val typed = ws.executeCommand("type a.txt", None, Some(30.seconds), None)
    typed.exitCode shouldBe 0
  }
}
