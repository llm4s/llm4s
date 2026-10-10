package org.llm4s.runner

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.Locale
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * What stops a `git` command the policy allows from running a program its repository names (#1721).
 *
 * git reads the repository's own `.git/config` and attributes, and runs the programs they name: an fsmonitor hook
 * (`core.fsmonitor`), hooks (`.git/hooks`, `core.hooksPath`), an external diff (`diff.external`,
 * `diff.<driver>.command`), a textconv driver (`diff.<driver>.textconv`), a filter driver (`filter.<driver>.clean`,
 * `smudge`, `process`) named by `.gitattributes` or `.git/info/attributes`, a signature verifier (`gpg.program`, run by
 * `log.showSignature` or a `%G?` / `%(signature)` format), the transport a partial clone fetches a missing object with
 * (`remote.<name>.url` with `ext::`, `core.sshCommand`, a remote helper), or a submodule's own `git`, which reads that
 * submodule's configuration. `git status`, `git diff`, `git log -p`, `git show`, `git blame` and `git ls-files -m` run
 * them; an agent that can write files could plant them, and a repository cloned into the workspace can arrive with them.
 *
 * Writes into `.git` are refused (`CommandPolicy.gitMetadataRefusal`), but a repository that is already hostile is
 * not written by the agent, so every `git` the runner starts is held by these layers on their own:
 *
 *  1. '''Environment''' ([[environment]]): no system or global configuration or attributes (`GIT_CONFIG_NOSYSTEM`,
 *     `GIT_CONFIG_GLOBAL` the null device, `GIT_ATTR_NOSYSTEM`); `HOME` and `XDG_CONFIG_HOME` an empty directory the
 *     runner owns; no lazy fetch and no transport at all (`GIT_NO_LAZY_FETCH`, `GIT_ALLOW_PROTOCOL` naming none), so
 *     nothing reaches `core.sshCommand`, a credential helper, `core.askPass` or a remote helper; no prompt, no pager,
 *     no optional index write (which runs `post-index-change`).
 *  2. '''Fixed overrides''' ([[fixedOverrides]]), given as `-c` before the subcommand, where they win over any file:
 *     `core.fsmonitor=false`, `core.hooksPath` the empty directory, `diff.external` empty, `log.showSignature=false`,
 *     every `gpg.*.program` the empty directory (which cannot be run), `diff.ignoreSubmodules=all` (no `git` started
 *     in a submodule), `safe.bareRepository=explicit` (a bare repository written into the workspace is not used), and
 *     `--no-pager`; and after the subcommand ([[SubcommandOptions]]) `--no-ext-diff`, `--no-textconv` and
 *     `--ignore-submodules=all` where it takes them. The policy refuses the options that would undo them, and
 *     `git status -v` / `--verbose`, whose staged diff runs textconv drivers and which takes no `--no-textconv`.
 *  3. '''Drivers''' ([[driverOverrides]]): a filter or diff driver is named by an attribute and defined in the
 *     configuration, so it cannot be listed in advance. Before the command, the runner lists the configuration the
 *     command will read (`git config --list --no-includes`, with layers 1 and 2) and blanks every driver it defines:
 *     `filter.<name>.clean`, `smudge` and `process` empty and `required=false` (git then passes content through), and
 *     `diff.<name>.textconv` and `command` empty. Configuration it cannot account for refuses the command
 *     ([[GitConfigNotAllowed]]): an `include.path` or `includeIf` (an included file may lie in the work tree and change
 *     after the listing), a `hook.*` key (configuration-defined hooks), or a driver name that `-c` cannot express
 *     exactly. The listing is read as bytes, and a `filter`, `diff` or `merge` subsection is blanked only when every
 *     byte is printable ASCII other than `=` and `"` (0x21-0x7E): a name with any other byte - one that is not UTF-8
 *     (`[filter "\xE9vil"]`), any non-ASCII character, a space or a control character - could reach git as a different
 *     name once decoded and re-encoded for the command line (as U+FFFD, or in the JVM's `sun.jnu.encoding`), leaving the
 *     driver `.gitattributes` names untouched, so it refuses the command instead.
 *
 * '''Limits.''' The listing runs just before the command, from the same directory: a concurrent command that replaces
 * the repository between the two (an `mv` of whole repositories, the read-write list) is not seen. What git reads but
 * does not run - `core.worktree`, `.git/commondir`, alternates, `mailmap.file` - is not changed here.
 */
private[runner] object GitSandbox {

  /** The code of a command refused because of what the repository's configuration holds. */
  val GitConfigNotAllowed = "GIT_CONFIG_NOT_ALLOWED"

  /** How long listing the configuration may take; a `.git/config` that is a FIFO would otherwise block. */
  val ListingTimeout: Long = 10000L

  /** The most output the listing may produce, and the most drivers a command may blank. */
  private val MaxListing = 1 << 20
  private val MaxDrivers = 200

  /**
   * An empty directory the runner made outside any workspace, for `HOME`, `XDG_CONFIG_HOME`, `core.hooksPath` and the
   * `gpg.*.program` overrides (a directory cannot be run). Made once; `Left` when it cannot be.
   */
  lazy val emptyDirectory: Either[String, Path] =
    Try {
      val dir = Files.createTempDirectory("llm4s-git-").toRealPath()
      dir.toFile.deleteOnExit()
      dir
    }.toEither.left.map(e => s"could not make the empty directory git is run with (${e.getMessage})")

  /** The null device of the host the runner runs on, for `GIT_CONFIG_GLOBAL`. */
  private def nullDevice: String =
    if (System.getProperty("os.name", "").startsWith("Windows")) "NUL" else "/dev/null"

  /**
   * The configuration given to every `git` with `-c`, before the subcommand: command-line configuration wins over every
   * file. `gpg.*.program` name `empty`, a directory, so a signature check fails rather than runs a program; textconv,
   * an external diff and filters are switched off with empty values (see [[driverOverrides]]).
   */
  def fixedOverrides(empty: Path): Seq[(String, String)] = Seq(
    "core.fsmonitor"        -> "false",
    "core.hooksPath"        -> empty.toString,
    "diff.external"         -> "",
    "log.showSignature"     -> "false",
    "gpg.program"           -> empty.toString,
    "gpg.openpgp.program"   -> empty.toString,
    "gpg.x509.program"      -> empty.toString,
    "gpg.ssh.program"       -> empty.toString,
    "diff.ignoreSubmodules" -> "all",
    "safe.bareRepository"   -> "explicit",
    "protocol.allow"        -> "never"
  )

  /** Options given after the subcommand: `git diff`, `log` and `show` run textconv and external diffs by default. */
  val SubcommandOptions: Map[String, Seq[String]] = Map(
    "diff"   -> Seq("--no-ext-diff", "--no-textconv", "--ignore-submodules=all"),
    "log"    -> Seq("--no-ext-diff", "--no-textconv", "--ignore-submodules=all"),
    "show"   -> Seq("--no-ext-diff", "--no-textconv", "--ignore-submodules=all"),
    "status" -> Seq("--ignore-submodules=all"),
    "blame"  -> Seq("--no-textconv"),
    "grep"   -> Seq("--no-textconv")
  )

  /**
   * Sets the variables of layer 1 in a `git` process's environment, after [[CommandPolicy.confineGit]] has removed
   * every `GIT_*` variable the runner's own environment carried.
   */
  def environment(env: java.util.Map[String, String], empty: Path): Unit = {
    // Names are compared ignoring case, as Windows does, so a `Home` or `pager` already there is replaced, not doubled.
    val replaced = Set("HOME", "XDG_CONFIG_HOME", "PAGER")
    env.keySet.asScala.toList.filter(k => replaced.contains(k.toUpperCase(Locale.ROOT))).foreach(env.remove)
    Seq(
      "GIT_CONFIG_NOSYSTEM" -> "1",
      "GIT_CONFIG_GLOBAL"   -> nullDevice,
      "GIT_ATTR_NOSYSTEM"   -> "1",
      "HOME"                -> empty.toString,
      "XDG_CONFIG_HOME"     -> empty.toString,
      "GIT_TERMINAL_PROMPT" -> "0",
      "GIT_PAGER"           -> "cat",
      "PAGER"               -> "cat",
      "GIT_OPTIONAL_LOCKS"  -> "0",
      "GIT_NO_LAZY_FETCH"   -> "1",
      // A list naming no transport: every protocol, `file` and remote helpers included, is refused, overriding any
      // `protocol.<name>.allow` the repository sets (which a `-c protocol.allow=never` does not).
      "GIT_ALLOW_PROTOCOL" -> "llm4s-none"
    ).foreach { case (k, v) => env.put(k, v) }
  }

  /**
   * The arguments to start `git` with: `--no-pager` and the `-c` overrides, the caller's global options, the
   * subcommand, its injected options, then the caller's arguments.
   */
  def arguments(args: Seq[String], overrides: Seq[(String, String)]): Seq[String] = {
    val (globals, rest) = args.span(_.startsWith("-"))
    val configured      = overrides.flatMap { case (k, v) => Seq("-c", s"$k=$v") }
    val injected        = rest.headOption.toSeq.flatMap(SubcommandOptions.getOrElse(_, Nil))
    Seq("--no-pager") ++ configured ++ globals ++ rest.take(1) ++ injected ++ rest.drop(1)
  }

  /** Whether the command has a subcommand (`git --version` has none and reads no repository's drivers). */
  def hasSubcommand(args: Seq[String]): Boolean = args.dropWhile(_.startsWith("-")).nonEmpty

  /**
   * The `-c` overrides that blank every filter and diff driver a configuration listing defines, or the refusal when the
   * listing holds what they cannot cover.
   *
   * @param bytes the output of `git config --list --no-includes --show-scope -z`, as git wrote it: a scope, NUL, the
   *              key, then a newline and the value when it has one, NUL. It is decoded one byte to one character
   *              (ISO-8859-1), so a name git holds as bytes is never replaced or merged with another; only a driver name
   *              of [[isExpressible]] bytes is turned into an override.
   */
  def driverOverrides(bytes: Array[Byte]): Either[CommandPolicy.Refusal, Seq[(String, String)]] = {
    val listing = new String(bytes, StandardCharsets.ISO_8859_1)
    val tokens  = listing.split("\u0000", -1).toList.dropRight(if (listing.endsWith("\u0000")) 1 else 0)
    val keys = tokens
      .grouped(2)
      .collect { case List(scope, entry) if scope != "command" => entry.takeWhile(_ != '\n') }
      .toList

    def refuse(key: String, why: String): Left[CommandPolicy.Refusal, Nothing] =
      Left(
        CommandPolicy.Refusal(
          GitConfigNotAllowed,
          s"The repository's git configuration has '$key', which the runner cannot account for: $why git is not run " +
            "in a repository whose configuration could make it run a program (#1721)."
        )
      )

    /** The section (lower-cased), the subsection as written, and the key (lower-cased). */
    def split(key: String): (String, Option[String], String) = {
      val first = key.indexOf('.')
      val last  = key.lastIndexOf('.')
      if (first < 0) (key.toLowerCase(Locale.ROOT), None, "")
      else if (first == last)
        (key.take(first).toLowerCase(Locale.ROOT), None, key.drop(last + 1).toLowerCase(Locale.ROOT))
      else
        (
          key.take(first).toLowerCase(Locale.ROOT),
          Some(key.substring(first + 1, last)),
          key.drop(last + 1).toLowerCase(Locale.ROOT)
        )
    }

    val parsed = keys.map(k => k -> split(k))
    parsed.collectFirst {
      case (key, (section, _, _)) if section == "include" || section == "includeif" =>
        refuse(key, "an included file is not listed with the rest, and may lie in the work tree and change.")
      case (key, (section, _, _)) if section == "hook" =>
        refuse(key, "it defines a hook in the configuration, which core.hooksPath does not switch off.")
      case (key, (section, Some(name), _)) if DriverSections.contains(section) && !isExpressible(name) =>
        refuse(
          key.map(c => if (c >= 0x20 && c < 0x7f) c else '?'),
          "its driver name holds a byte other than printable ASCII (a space, '=', '\"', a control character, a " +
            "non-ASCII character, or a byte that is not UTF-8), which a -c override may not reach git as exactly."
        )
    } match {
      case Some(refused) => refused
      case None =>
        val filters = parsed.collect { case (_, ("filter", Some(name), _)) => name }.distinct
        val diffs = parsed.collect {
          case (_, ("diff", Some(name), key)) if key == "textconv" || key == "command" => name
        }.distinct
        if (filters.size + diffs.size > MaxDrivers)
          refuse(s"${filters.size + diffs.size} drivers", s"more than $MaxDrivers filter and diff drivers.")
        else
          Right(
            filters.flatMap { name =>
              Seq(
                s"filter.$name.clean"    -> "",
                s"filter.$name.smudge"   -> "",
                s"filter.$name.process"  -> "",
                s"filter.$name.required" -> "false"
              )
            } ++ diffs.flatMap(name => Seq(s"diff.$name.textconv" -> "", s"diff.$name.command" -> ""))
          )
    }
  }

  /** The sections whose subsection names a driver an attribute selects. */
  private val DriverSections: Set[String] = Set("filter", "diff", "merge")

  /**
   * Whether a driver name, decoded one byte to one character, goes through a `-c` argument to git unchanged: every
   * byte printable ASCII (0x21-0x7E) other than `=` (which ends the key) and `"`. ASCII is encoded the same in every
   * charset the JVM may use for a command line, so the bytes git receives are the bytes the listing held.
   */
  private[runner] def isExpressible(name: String): Boolean =
    name.nonEmpty && name.forall(c => c >= 0x21 && c <= 0x7e && c != '=' && c != '"')

  /**
   * Lists the configuration a `git` command started by `builder` would read: the same program, working directory and
   * environment, the fixed overrides, and `config --list --no-includes --show-scope -z`. `Left` with the reason when
   * it cannot be listed, which refuses the command.
   */
  def listConfiguration(builder: java.lang.ProcessBuilder, git: String, empty: Path): Either[String, Array[Byte]] = {
    val lister = new java.lang.ProcessBuilder(
      (Seq(git) ++ arguments(
        Seq("config", "--list", "--no-includes", "--show-scope", "-z"),
        fixedOverrides(empty)
      )).asJava
    )
    lister.directory(builder.directory())
    lister.environment().clear()
    lister.environment().putAll(builder.environment())
    lister.redirectInput(builder.redirectInput())
    lister.redirectError(java.lang.ProcessBuilder.Redirect.DISCARD)
    Try(lister.start()).toEither.left
      .map(e => s"git could not be started to list its configuration (${e.getMessage})")
      .flatMap { process =>
        val out = new ByteArrayOutputStream()
        val reader = new Thread(() =>
          Using.resource(process.getInputStream) { in =>
            val buffer = new Array[Byte](8192)
            Iterator.continually(in.read(buffer)).takeWhile(_ >= 0).foreach { n =>
              if (out.size() <= MaxListing) out.write(buffer, 0, n)
            }
          }
        )
        reader.setDaemon(true)
        reader.start()
        val finished = process.waitFor(ListingTimeout, TimeUnit.MILLISECONDS)
        if (!finished) {
          process.destroyForcibly()
          Left(s"listing git's configuration did not finish within ${ListingTimeout / 1000} seconds")
        } else {
          reader.join(2000)
          if (process.exitValue() != 0) Left(s"git could not list its configuration (exit code ${process.exitValue()})")
          else if (out.size() > MaxListing) Left(s"git's configuration is larger than $MaxListing bytes")
          else Right(out.toByteArray)
        }
      }
  }

  /**
   * The empty directory, checked: it must still be empty and lie outside the workspace, or `core.hooksPath` and `HOME`
   * would name files the agent could write.
   */
  def checkedEmptyDirectory(realRoot: Path): Either[String, Path] =
    emptyDirectory.flatMap { dir =>
      if (dir.startsWith(realRoot))
        Left(s"the empty directory git is run with ('$dir') lies inside the workspace; set java.io.tmpdir elsewhere")
      else if (!Try(Using.resource(Files.list(dir))(_.findAny().isEmpty)).getOrElse(false))
        Left(s"the directory git is run with ('$dir') is not empty")
      else Right(dir)
    }
}
