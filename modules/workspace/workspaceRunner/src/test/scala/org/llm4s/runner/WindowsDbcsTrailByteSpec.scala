package org.llm4s.runner

import org.llm4s.shared._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.Charset
import java.nio.file.Path
import scala.concurrent.duration._
import scala.util.Using

/**
 * On a Windows host whose ANSI code page is a double-byte one (932, 936, 950), some characters encode with a second
 * byte of 0x5C, the byte of `\` (#1807): `表` is 0x95 0x5C on code page 932, `功` 0xA5 0x5C on 950. The JDK puts an
 * argument holding a space in quotes, so `a 表` reaches the command line as `"a 表"`, whose bytes end `5C 22`, and a
 * program that splits its ANSI command line byte by byte reads `\"` as a literal quote and splits the arguments after
 * it differently from what the checks saw. Such an argument is refused when the JDK may quote it.
 *
 * The cases pass the code page explicitly, so they run on any host; the previous runner let every refused argument
 * below through its checks.
 */
class WindowsDbcsTrailByteSpec extends AnyFlatSpec with Matchers {
  import WorkspaceCommandArgumentsSpec.{ isWindowsHost, ArgumentNotAllowed, Fixture, ReadOnly }

  private val MS932 = Charset.forName("MS932")
  private val GBK   = Charset.forName("GBK")
  private val MS936 = Charset.forName("x-mswin-936")
  private val MS950 = Charset.forName("MS950")

  /** Per code page, characters it holds whose encoding ends in 0x5C. */
  private val TrailBackslash: Seq[(Charset, Seq[Char])] = Seq(
    MS932 -> Seq('表', '十', 'ソ', '予', '―'), // 表 十 ソ 予 ―
    GBK   -> Seq('乗', '淺'),                // 乗 淺
    MS936 -> Seq('乗', '淺'),
    MS950 -> Seq('功', '許')                 // 功 許
  )

  private def inWorkspace[A](f: Fixture => A): A = Using.resource(new Fixture)(f)

  private def policy(
    program: String,
    args: Seq[String],
    root: Path,
    ansi: Option[Charset],
    windows: Boolean = true
  ): Option[CommandPolicy.Refusal] =
    CommandPolicy.refusal(program, args, windows, root, root, Map.empty, ansiCodePage = ansi)

  "The workspace runner on a double-byte Windows host" should "refuse an argument the JDK quotes that holds a character ending in 0x5C" in inWorkspace {
    fx =>
      val root = fx.root.toRealPath()
      TrailBackslash.foreach { case (cs, chars) =>
        chars.foreach { c =>
          Seq(s"a $c", s"a $c.txt", s"$c b", s"a\t$c", s"$c\"x", s"x$c<y").foreach { arg =>
            withClue(s"${cs.name}: '$arg'") {
              val refusal = policy("findstr", Seq("x", arg), root, Some(cs))
              refusal.map(_.code) shouldBe Some(ArgumentNotAllowed)
              if (!arg.contains('"')) {
                refusal.get.message should include("0x5C")
                refusal.get.message should include(cs.name)
              }
            }
          }
        }
      }
      // The examples of #1807
      policy("findstr", Seq("x", "a 表"), root, Some(MS932)).map(_.code) shouldBe Some(ArgumentNotAllowed)
      policy("findstr", Seq("x", "x 十"), root, Some(MS932)).map(_.code) shouldBe Some(ArgumentNotAllowed)
      policy("findstr", Seq("x", "a 功"), root, Some(MS950)).map(_.code) shouldBe Some(ArgumentNotAllowed)
  }

  it should "keep allowing such a character in an argument the JDK does not quote" in inWorkspace { fx =>
    val root = fx.root.toRealPath()
    TrailBackslash.foreach { case (cs, chars) =>
      chars.foreach { c =>
        Seq(s"$c.txt", s"sub\\$c", s"$c$c", s"a-$c").foreach { arg =>
          withClue(s"${cs.name}: '$arg'")(policy("findstr", Seq("x", arg), root, Some(cs)) shouldBe None)
        }
      }
    }
  }

  it should "keep allowing a quoted argument whose characters do not end in 0x5C" in inWorkspace { fx =>
    val root = fx.root.toRealPath()
    // 漢 and 字 hold no 0x5C byte on any of the three code pages
    Seq(MS932, GBK, MS950).foreach { cs =>
      withClue(cs.name)(policy("findstr", Seq("x", "a 漢字.txt"), root, Some(cs)) shouldBe None)
    }
    // 功 is 0xB9 0xA6 on code page 936, and 表 0xB1 0xED: only the host's own code page matters
    policy("findstr", Seq("x", "a 功"), root, Some(GBK)) shouldBe None
    policy("findstr", Seq("x", "a 表"), root, Some(MS950)) shouldBe None
  }

  it should "leave hosts whose code page has no such character unchanged" in inWorkspace { fx =>
    val root = fx.root.toRealPath()
    // Code page 949 holds 表 and encodes no character with a 0x5C second byte; UTF-8 (`None`) converts exactly
    policy("findstr", Seq("x", "a 表"), root, Some(Charset.forName("x-windows-949"))) shouldBe None
    policy("findstr", Seq("x", "a 表"), root, None) shouldBe None
    policy("findstr", Seq("x", "a résumé"), root, Some(Charset.forName("windows-1252"))) shouldBe None
  }

  it should "treat an argument as quoted as the JDK 21 Windows ProcessImpl does, in any of its modes" in {
    // needsEscaping: empty, a space or whitespace character, and `"` `<` `>` (`&` `|` `^` for a batch file)
    Seq("", "a b", "a\tb", "a　b", "a b", "a\nb", "a\"b", "\"ab\"", "a<b", "a>b", "a&b", "a|b", "a^b")
      .foreach(arg => withClue(s"'$arg'")(WindowsBestFit.mayBeQuoted(arg) shouldBe true))
    Seq("ab", "a.txt", "-x", "/x", "a\\b", "a-b_c", "表.txt", "a,b", "a=b", "a%b")
      .foreach(arg => withClue(s"'$arg'")(WindowsBestFit.mayBeQuoted(arg) shouldBe false))
  }

  it should "find every character of the code page that ends in 0x5C through the JDK's encoder" in {
    // The counts of #1807 for the characters each code page holds exactly; characters it only best-fits to such a
    // byte pair are refused already, as ones the host's code page cannot hold
    def count(cs: Charset): Int =
      (0x80 until 0x10000).count(cp =>
        !Character.isSurrogate(cp.toChar) &&
          WindowsBestFit.quotedTrailByteRefusal(s"a ${cp.toChar}", Some(cs)).isDefined
      )
    count(MS932) shouldBe 50
    count(GBK) shouldBe 126
    count(MS950) shouldBe 126
    count(Charset.forName("x-windows-949")) shouldBe 0
    count(Charset.forName("windows-1252")) shouldBe 0
  }

  it should "leave POSIX unchanged, where nothing converts or quotes the arguments" in inWorkspace { fx =>
    val root = fx.root.toRealPath()
    TrailBackslash.foreach { case (cs, chars) =>
      chars.foreach(c => policy("grep", Seq("x", s"a $c"), root, Some(cs), windows = false) shouldBe None)
    }
  }

  it should "refuse them on a Windows host whose ANSI code page is double-byte" in inWorkspace { fx =>
    assume(isWindowsHost, "only a Windows host converts arguments to an ANSI code page")
    val host = WindowsBestFit.hostAnsiCodePage
    val char = host.flatMap(cs => TrailBackslash.find(_._1.name == cs.name).map(_._2.head))
    assume(char.isDefined, s"the host's ANSI code page (${host.fold("UTF-8")(_.name)}) is not 932, 936 or 950")
    val ws = fx.interface(ReadOnly, windows = true)
    val ex =
      the[WorkspaceAgentException] thrownBy ws.executeCommand(
        s"findstr x 'a ${char.get}.txt'",
        None,
        Some(5.seconds),
        None
      )
    ex.code shouldBe ArgumentNotAllowed
  }
}
