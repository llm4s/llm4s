package org.llm4s.runner

import org.llm4s.shared.WorkspaceSandboxConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.Locale
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * Programs an operator commonly adds with `WORKSPACE_EXTRA_COMMANDS` whose options run another program (#1805): `tar`,
 * `zip`, `rsync`, `scp` and `sftp` get per-program option rules, as `sort` and `cp` have. And no spelling of a refused
 * name that the runner or Windows could read as that name gets past the denylist.
 */
class ExtraCommandRulesSpec extends AnyFlatSpec with Matchers {

  private def inRoot[A](f: Path => A): A =
    Using.resource(new TempRoot)(root => f(root.path))

  private def refusal(program: String, command: String, root: Path, windows: Boolean = false): Option[String] =
    CommandPolicy
      .refusal(program, command.split(' ').toSeq.filter(_.nonEmpty), windows, root, root, Map.empty)
      .map(_.code)

  "CommandPolicy" should "refuse the options through which tar runs a program, in every form (#1805)" in inRoot {
    root =>
      Seq(
        "-cf a.tar --to-command=id a.txt",
        "-xf a.tar --to-command id",
        "-xf a.tar --to-c=id", // an abbreviation
        "-cf a.tar --use-compress-program=id a.txt",
        "-cf a.tar --use-compress=id a.txt",
        "-I id -cf a.tar a.txt",
        "-cIid -f a.tar a.txt", // in a cluster, with its value glued
        "-cf a.tar --checkpoint=1 --checkpoint-action=exec=id a.txt",
        "-cf a.tar -F id a.txt",
        "-cf a.tar --info-script=id a.txt",
        "-cf a.tar --new-volume-script=id a.txt",
        "-cf host:a.tar --rsh-command=id a.txt",
        "-cf host:a.tar --rmt-command=id a.txt",
        "cIf id a.tar a.txt", // the traditional form: letters without a dash
        "xFf id a.tar"
      ).foreach(args => withClue(s"tar $args: ")(refusal("tar", args, root) shouldBe Some("ARGUMENT_NOT_ALLOWED")))
  }

  it should "still let tar create, list and extract an archive inside the workspace" in inRoot { root =>
    Seq("-cf a.tar a.txt", "-czf a.tgz a.txt", "-tf a.tar", "-xf a.tar", "cf a.tar a.txt", "tvf a.tar", "--version")
      .foreach(args => withClue(s"tar $args: ")(refusal("tar", args, root) shouldBe None))
  }

  it should "refuse zip's unzip command, rsync's remote shell and rsync path, and scp's and sftp's programs" in inRoot {
    root =>
      Seq(
        "zip"   -> "-T -TT id a.zip a.txt",
        "zip"   -> "-TTid a.zip a.txt",
        "zip"   -> "--unzip-command=id -T a.zip a.txt",
        "zip"   -> "--unzip-command id -T a.zip a.txt",
        "rsync" -> "-e id a.txt host:b",
        "rsync" -> "-avze id a.txt host:b",
        "rsync" -> "--rsh=id a.txt host:b",
        "rsync" -> "--rsync-path=id a.txt host:b",
        "scp"   -> "-S id a.txt host:b",
        "scp"   -> "-o ProxyCommand=id a.txt host:b",
        "scp"   -> "-oProxyCommand=id a.txt host:b",
        "scp"   -> "-F a.txt a.txt host:b",
        "sftp"  -> "-D id host",
        "sftp"  -> "-S id host",
        "sftp"  -> "-o ProxyCommand=id host"
      ).foreach { case (program, args) =>
        withClue(s"$program $args: ")(refusal(program, args, root) shouldBe Some("ARGUMENT_NOT_ALLOWED"))
      }
  }

  it should "still let zip, rsync and scp do their ordinary work" in inRoot { root =>
    Seq(
      "zip"   -> "-r a.zip a.txt",
      "zip"   -> "-q a.zip a.txt",
      "rsync" -> "-av a.txt b.txt",
      "rsync" -> "--archive --delete a.txt b.txt",
      "scp"   -> "-P 2222 a.txt host:b"
    ).foreach { case (program, args) =>
      withClue(s"$program $args: ")(refusal(program, args, root) shouldBe None)
    }
  }

  it should "apply the rules to a Windows spelling of the program (tar.exe, TAR.EXE)" in inRoot { root =>
    Seq("tar", "tar.exe", "TAR.EXE", "Tar.Com").foreach { spelled =>
      val key = ProgramResolver.programKey(spelled, windows = true)
      withClue(spelled)(
        refusal(key, "-xf a.tar --to-command=id", root, windows = true) shouldBe
          Some("ARGUMENT_NOT_ALLOWED")
      )
    }
  }

  it should "refuse rsync's daemon mode and its configuration, in every form rsync parses (#1845)" in inRoot { root =>
    Seq(
      "--daemon",
      "--daemon --no-detach",
      "--daemon --config=a.txt",
      "--daemon --config a.txt",
      "--config=a.txt --daemon",
      "--conf=a.txt", // an abbreviation
      "--da",         // an abbreviation
      "--daemon=yes", // with a value
      "--dparam=pidfile=a.txt",
      "--dparam pidfile=a.txt",
      "-M pidfile=a.txt",
      "-avM--rsync-path=id a.txt b.txt", // in a cluster
      "--remote-option=--rsync-path=id a.txt b.txt",
      "-av a.txt b.txt -- --daemon" // after `--`
    ).foreach(args => withClue(s"rsync $args: ")(refusal("rsync", args, root) shouldBe Some("ARGUMENT_NOT_ALLOWED")))
  }

  it should "refuse an rsync argument naming a file on another host, in every form (#1845)" in inRoot { root =>
    Seq(
      "-av a.txt host:b",
      "-av host:a.txt b.txt",
      "-av a.txt user@host:b",
      "-av a.txt host::module",
      "-av host::module/a.txt b.txt",
      "-av a.txt rsync://host/module",
      "-av a.txt RSYNC://host/module",
      "-av a.txt host:",
      "-av --files-from=host:list a.txt b.txt",
      "-av --files-from host:list a.txt b.txt",
      "-av a.txt [::1]:b"
    ).foreach(args => withClue(s"rsync $args: ")(refusal("rsync", args, root) shouldBe Some("ARGUMENT_NOT_ALLOWED")))
  }

  it should "refuse a tar archive or argument on another host, in every form tar parses (#1845)" in inRoot { root =>
    Seq(
      "-cf host:a.tar a.txt",
      "-xf user@host:a.tar",
      "-cfhost:a.tar a.txt", // glued to the option
      "-tvf host:a.tar",
      "cf host:a.tar a.txt", // the traditional form
      "--file=host:a.tar -c a.txt",
      "--file host:a.tar -c a.txt",
      "--fi=host:a.tar -c a.txt", // an abbreviation
      "-cf a.tar a.txt -- host:b"
    ).foreach(args => withClue(s"tar $args: ")(refusal("tar", args, root) shouldBe Some("ARGUMENT_NOT_ALLOWED")))
  }

  it should "refuse sftp's batch file and scp's local sftp server (#1845)" in inRoot { root =>
    Seq(
      "sftp" -> "-b a.txt host",
      "sftp" -> "-ba.txt host",
      "sftp" -> "-qb a.txt host", // in a cluster
      "sftp" -> "-b - host",
      "scp"  -> "-D id a.txt b.txt"
    ).foreach { case (program, args) =>
      withClue(s"$program $args: ")(refusal(program, args, root) shouldBe Some("ARGUMENT_NOT_ALLOWED"))
    }
  }

  it should "say why a remote argument is refused, and how to name a local file with a ':'" in inRoot { root =>
    val message = CommandPolicy
      .refusal("rsync", Seq("a.txt", "host:b"), false, root, root, Map.empty)
      .map(_.message)
    message.get should (include("'host:b'").and(include("another host")).and(include("./a:b")))
  }

  it should "still let tar and rsync work on local names, including one with a ':' after a '/'" in inRoot { root =>
    Seq(
      "tar"   -> "-cf ./a:b.tar a.txt",
      "tar"   -> "-cf a.tar ./a:b",
      "tar"   -> "--file=./a:b.tar -c a.txt",
      "tar"   -> "-cf :a.tar a.txt", // a leading ':' is not a host
      "tar"   -> "-cf a.tar a.txt",
      "rsync" -> "-av ./a:b c.txt",
      "rsync" -> "-av a.txt b.txt",
      "rsync" -> "-av --delete --dry-run a.txt b.txt",
      "rsync" -> "--checksum --compress a.txt b.txt",
      "rsync" -> "-av --exclude=x a.txt b.txt",
      "sftp"  -> "-P 2222 host",
      "sftp"  -> "-q host",
      "scp"   -> "-P 2222 a.txt host:b",
      "scp"   -> "-r a.txt user@host:b"
    ).foreach { case (program, args) =>
      withClue(s"$program $args: ")(refusal(program, args, root) shouldBe None)
    }
  }

  it should "on Windows, read a drive letter as a drive, not a host, and still refuse a host" in {
    def remote(args: String*)(windows: Boolean) = CommandPolicy.remoteOperandRefusal("tar", args, windows).map(_.code)
    remote("-cf", "C:\\work\\a.tar", "--file=D:/x.tar")(windows = true) shouldBe None
    remote("-cf", "host:a.tar")(windows = true) shouldBe Some("ARGUMENT_NOT_ALLOWED")
    remote("-cf", "hh:a.tar")(windows = true) shouldBe Some("ARGUMENT_NOT_ALLOWED")
    // elsewhere a one-letter host is a host
    remote("-cf", "C:a.tar")(windows = false) shouldBe Some("ARGUMENT_NOT_ALLOWED")
  }

  // -----------------------------------------------------------------------------------------------------------------
  // Unicode spellings: a name Windows or a case- and normalisation-insensitive file system could read as a refused one

  /** Every non-ASCII BMP character that some Windows code page best-fits to, or that folds to, each ASCII character. */
  private lazy val lookAlikes: Map[Char, Seq[Char]] = {
    val nameChars = ('a' to 'z') ++ ('0' to '9') ++ Seq('.', '_', '+', '-')
    val folds     = nameChars.map(c => c -> CommandPolicy.folded(c.toString)).toMap
    (0x80 to 0xffff).iterator
      .map(_.toChar)
      .filterNot(Character.isSurrogate)
      .flatMap { c =>
        val bestFit = WindowsBestFit.substitutes(c).toLowerCase(Locale.ROOT)
        val folded  = CommandPolicy.folded(c.toString)
        nameChars.filter(a => bestFit.contains(a) || (folded.isDefined && folded == folds(a))).map(_ -> c)
      }
      .toSeq
      .groupMap(_._1)(_._2)
  }

  "WorkspaceSandboxConfig.withExtraCommands" should "refuse every Unicode look-alike of a refused name: best-fit " +
    "and case- or NFKD-folded spellings are not bare program names (#1805)" in {
      lookAlikes('k') should contain('K') // Kelvin sign
      lookAlikes('s') should contain('ｓ') // fullwidth s
      lookAlikes('s') should contain('ſ') // long s
      lookAlikes.values.map(_.size).sum should be > 100
      for {
        name <- WorkspaceSandboxConfig.NeverAllowedCommands.toSeq.sorted
        i    <- name.indices
        c    <- lookAlikes.getOrElse(name(i), Nil)
      } {
        val spelled = name.updated(i, c)
        val refused = WorkspaceSandboxConfig.Permissive.withExtraCommands(s"sbt,$spelled")
        if (refused.isRight) fail(f"'$spelled' (U+${c.toInt}%04X for '${name(i)}' in '$name') was accepted")
      }
    }

  it should "leave the runner matching a non-ASCII first word to no refused program's key" in {
    // The runner's key for a command's first word: a look-alike never becomes a refused program's ASCII key, except
    // through the Kelvin sign, whose lower case is `k` - and a refused program is never on the allowlist to match
    val keys = for {
      name <- WorkspaceSandboxConfig.NeverAllowedCommands.toSeq
      i    <- name.indices
      c    <- lookAlikes.getOrElse(name(i), Nil) if c != 'K'
    } yield ProgramResolver.programKey(name.updated(i, c), windows = true)
    keys.filter(WorkspaceSandboxConfig.NeverAllowedCommands.contains) shouldBe empty
  }
}

/** A workspace root holding the files the commands above name. */
final private class TempRoot extends AutoCloseable {
  private val base: Path = Files.createTempDirectory("extra-command-rules")
  Files.write(base.resolve("a.txt"), "a\n".getBytes(StandardCharsets.UTF_8))
  Files.write(base.resolve("b.txt"), "b\n".getBytes(StandardCharsets.UTF_8))
  val path: Path = base.toRealPath()

  override def close(): Unit =
    Using.resource(Files.walk(base))(_.iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists))
}
