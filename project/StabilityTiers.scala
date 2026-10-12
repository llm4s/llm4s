import sbt._

import scala.io.Source
import scala.util.Using

/**
 * The stability tier of a frozen module's public types, recorded in the code.
 *
 * `docs/reference/v1-scope.md` says which packages 1.0 freezes. That page drifts from the code it
 * describes, so every top-level public type of a frozen module carries `@Stable` (covered by the
 * 1.x compatibility promise) or `@Experimental` (not covered), from `org.llm4s.annotation` in
 * `llm4s-core`. A Stable companion shares its type's tier; an Experimental companion needs its own annotation
 * because MiMa reads the separate companion class.
 *
 * `check` fails the build for a type that has neither annotation, for one that has both, and for a
 * file the build lists as an exception (a Beta dialect inside a frozen module) whose types are not
 * `@Experimental`. It reads sources, not classes: Scala compiles `private[llm4s]` to a public
 * bytecode member, so the compiled classes cannot say which types are public API.
 *
 * It also fails for a public member of a `@Stable` type whose signature names an `@Experimental` type
 * (#1818): once the baseline exists, renaming or reshaping the Experimental type would break the Stable
 * signature, so the promise would not hold. The member is a `def`, `val`, `var`, `given`, `type`, an enum
 * `case`, a nested type's header or the type's own constructor parameters; its signature is its text up to
 * the `=` or the end of its header, and a member that is `private`, `protected` or itself `@Experimental` is
 * skipped, as are bodies and comments. `selfTest` runs the check against fixtures first, so a change that
 * stops it finding a leak fails the build instead of passing everything.
 */
object StabilityTiers {

  final case class Violation(file: File, line: Int, text: String, problem: String)

  private val Modifier = "(?:(?:final|sealed|abstract|case|implicit|open|transparent|inline|infix)\\s+)*"
  private val Decl =
    ("^(?:@\\w+(?:\\([^)]*\\))?\\s+)*" + Modifier + "(class|trait|object|enum)\\s+([A-Za-z_]\\w*)").r
  private val NonPublic       = "^(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:private|protected)(?:\\[\\w+\\])?\\s".r
  private val StableAnn       = "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*@Stable\\b".r
  private val ExperimentalAnn = "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*@Experimental\\b".r

  // A member of a type: its leading annotations and modifiers, then the keyword that says what it is.
  private val Member = ("^\\s*((?:@\\w+(?:\\([^)]*\\))?\\s+)*)" +
    "((?:(?:override|final|implicit|inline|transparent|lazy|sealed|abstract|open|infix|case|opaque|" +
    "private|protected)(?:\\[\\w+\\])?\\s+)*)" +
    "(def|val|var|given|type|case|class|trait|object|enum)\\b").r
  private val Hidden = "\\b(?:private|protected)\\b".r

  private def sources(dir: File): Seq[File] =
    if (!dir.exists) Nil else (dir ** "*.scala").get.sortBy(_.getPath)

  private def read(file: File): Vector[String] =
    Using.resource(Source.fromFile(file, "UTF-8"))(_.getLines().toVector)

  private def topLevel(lines: Vector[String]): Seq[(Int, String, String)] =
    lines.zipWithIndex.collect {
      case (l, i) if l.nonEmpty && !l.head.isWhitespace && !l.startsWith("package object") =>
        Decl.findFirstMatchIn(l).map(m => (i, m.group(1), m.group(2)))
    }.flatten

  /** Top-level public types of one file, as (zero-based line, kind, name), Stable companions removed. */
  private[this] def declarations(lines: Vector[String]): Seq[(Int, String, String)] = {
    val found = lines.zipWithIndex.collect {
      case (l, i)
          if l.nonEmpty && !l.head.isWhitespace && !l
            .startsWith("package object") && NonPublic.findFirstIn(l).isEmpty =>
        Decl.findFirstMatchIn(l).map(m => (i, m.group(1), m.group(2)))
    }.flatten
    val typed = found.collect {
      case (i, kind, name) if kind != "object" && !annotated(lines, i, ExperimentalAnn) => name
    }.toSet
    found.filterNot { case (_, kind, name) => kind == "object" && typed.contains(name) }
  }

  /** Whether the annotation is on the declaration's own line or on the annotation lines just above it. */
  private[this] def annotated(lines: Vector[String], at: Int, ann: scala.util.matching.Regex): Boolean = {
    def above(i: Int): Boolean =
      i >= 0 && lines(i).trim.startsWith("@") && (ann.findFirstIn(lines(i)).nonEmpty || above(i - 1))
    ann.findFirstIn(lines(at)).nonEmpty || above(at - 1)
  }

  private def indent(line: String): Int = line.takeWhile(_.isWhitespace).length

  private def isComment(line: String): Boolean = {
    val t = line.trim
    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
  }

  /** The line without string literals and a trailing `//` comment, so neither can look like code. */
  private def code(line: String): String =
    line.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"").replaceAll("//.*$", "")

  /**
   * The signature of the member starting at `start`, line by line: for a `def`, `val`, `var` or `given`, its text
   * up to the `=` that starts its body (or to the end of an abstract member); for anything else, its header up to
   * the end of the line on which its brackets close. Returns the (line, text) pieces and the last line read.
   */
  private def signature(lines: Vector[String], start: Int, valueLike: Boolean): (Seq[(Int, String)], Int) = {
    val pieces = Seq.newBuilder[(Int, String)]
    var depth  = 0
    var i      = start
    var done   = false
    while (!done && i < lines.length) {
      val text = code(lines(i))
      val out  = new StringBuilder
      var j    = 0
      while (!done && j < text.length) {
        val c = text.charAt(j)
        if (c == '(' || c == '[') depth += 1
        else if (c == ')' || c == ']') depth -= 1
        else if (depth == 0 && valueLike && c == '{') done = true
        else if (depth == 0 && valueLike && c == '=') {
          val prev = if (j > 0) text.charAt(j - 1) else ' '
          val next = if (j + 1 < text.length) text.charAt(j + 1) else ' '
          if (next != '>' && next != '=' && !"=<>!:".contains(prev)) done = true
        }
        if (!done) out.append(c)
        j += 1
      }
      pieces += ((i, out.toString))
      if (!done && depth <= 0) done = true
      if (!done) i += 1
    }
    (pieces.result(), i)
  }

  /**
   * Public members of `@Stable` types whose signatures name a type in `experimental`.
   *
   * @param lines        one source file
   * @param experimental the names of the frozen modules' `@Experimental` top-level types
   */
  private[this] def leaks(lines: Vector[String], experimental: Set[String]): Seq[(Int, String)] =
    if (experimental.isEmpty) Nil
    else {
      val names   = ("\\b(" + experimental.toSeq.sorted.map(java.util.regex.Pattern.quote).mkString("|") + ")\\b").r
      val decls   = topLevel(lines)
      val stableT = decls.collect { case (i, k, n) if k != "object" && annotated(lines, i, StableAnn) => n }.toSet
      val extents = decls.zip(decls.drop(1).map(_._1) :+ lines.length)
      extents.flatMap { case ((start, kind, name), end) =>
        val stable =
          NonPublic.findFirstIn(lines(start)).isEmpty && !annotated(lines, start, ExperimentalAnn) &&
            (annotated(lines, start, StableAnn) || (kind == "object" && stableT.contains(name)))
        if (!stable) Nil
        else {
          val found      = Seq.newBuilder[(Int, String)]
          var skipIndent = -1
          var i          = start
          while (i < end) {
            val line = lines(i)
            if (line.trim.isEmpty || isComment(line)) i += 1
            else if (skipIndent >= 0 && indent(line) > skipIndent) i += 1
            else {
              skipIndent = -1
              Member.findFirstMatchIn(line) match {
                case Some(m) if !(m.group(3) == "case" && code(line).contains("=>")) =>
                  val keyword        = m.group(3)
                  val valueLike      = Set("def", "val", "var", "given")(keyword)
                  val hidden         = Hidden.findFirstIn(m.group(2)).nonEmpty
                  val (pieces, last) = signature(lines, i, valueLike)
                  // A class whose constructor is private: its plain parameters are not members, so only the
                  // header before them counts. A case class's parameters are public accessors whatever its
                  // constructor.
                  val privateCtor = keyword == "class" && !m.group(2).contains("case") &&
                    "\\bclass\\s+\\w+(?:\\[[^\\]]*\\])?\\s+(?:private|protected)\\b".r
                      .findFirstIn(pieces.map(_._2).mkString(" "))
                      .nonEmpty
                  val visible =
                    if (!privateCtor) pieces
                    else pieces.headOption.map { case (l, t) => (l, t.takeWhile(_ != '(')) }.toSeq
                  if (hidden || annotated(lines, i, ExperimentalAnn)) skipIndent = indent(line)
                  else {
                    visible.iterator
                      .flatMap { case (l, t) => names.findFirstMatchIn(t).map(n => (l, n.group(1))) }
                      .take(1)
                      .foreach(found += _)
                    if (valueLike) skipIndent = indent(line)
                  }
                  i = last + 1
                case _ => i += 1
              }
            }
          }
          found.result()
        }
      }
    }

  /** The names of the `@Experimental` top-level types of these modules. */
  private def experimentalNames(modules: Seq[File]): Set[String] =
    modules
      .flatMap(m => sources(m / "src" / "main" / "scala"))
      .flatMap { file =>
        val lines = read(file)
        topLevel(lines).collect { case (i, _, n) if annotated(lines, i, ExperimentalAnn) => n }
      }
      .toSet

  /**
   * @param modules         the frozen modules' base directories (`modules/<name>`)
   * @param experimentalIn  regexes over a file's path: its types must be `@Experimental`
   */
  def violations(
    modules: Seq[File],
    experimentalIn: Seq[scala.util.matching.Regex]
  ): (Seq[Violation], Map[String, (Int, Int)]) = {
    val experimental = experimentalNames(modules)
    val perModule = modules.map { module =>
      val results = sources(module / "src" / "main" / "scala").flatMap { file =>
        val lines              = read(file)
        val mustBeExperimental = experimentalIn.exists(_.findFirstIn(file.getPath).nonEmpty)
        val tiers = declarations(lines).map { case (i, _, _) =>
          val stable       = annotated(lines, i, StableAnn)
          val experimental = annotated(lines, i, ExperimentalAnn)
          val problem =
            if (stable && experimental) Some("carries both @Stable and @Experimental")
            else if (!stable && !experimental) Some("carries neither @Stable nor @Experimental")
            else if (mustBeExperimental && !experimental)
              Some("is in a file the build lists as Experimental but is @Stable")
            else None
          (problem.map(Violation(file, i + 1, lines(i).trim, _)), stable, experimental)
        }
        val leaked = leaks(lines, experimental).map { case (i, name) =>
          (
            Some(
              Violation(
                file,
                i + 1,
                lines(i).trim,
                s"is public in a @Stable type but its signature names @Experimental $name"
              )
            ),
            false,
            false
          )
        }
        tiers ++ leaked
      }
      module.getName -> ((results.count(_._2), results.count(_._3), results.flatMap(_._1)))
    }
    (perModule.flatMap(_._2._3), perModule.map { case (m, (s, e, _)) => m -> (s -> e) }.toMap)
  }

  /**
   * Runs the check against fixtures with known answers and returns what it got wrong: each fixture line marked
   * `// leak` must be reported, and no other line. Empty when the check works.
   */
  def selfTest(): Seq[String] = IO.withTemporaryDirectory { dir =>
    val module = dir / "fixture"
    val pkg    = module / "src" / "main" / "scala" / "fixture"
    IO.write(
      pkg / "Exp.scala",
      """package fixture
        |
        |import org.llm4s.annotation.Experimental
        |
        |@Experimental
        |final case class Token(value: String)
        |
        |@Experimental
        |trait Source:
        |  def token(): Token
        |""".stripMargin
    )
    IO.write(
      pkg / "Api.scala",
      """package fixture
        |
        |import org.llm4s.annotation.{ Experimental, Stable }
        |
        |/** A Token in a comment is not a signature. */
        |@Stable
        |final case class Config private (
        |  model: String,
        |  token: Option[Token] // leak
        |):
        |  def withToken(token: Token): Config = copy(token = Some(token)) // leak
        |  def describe: String =
        |    val t: Token = Token("x")
        |    t.value
        |  private def raw(t: Token): String = t.value
        |  private[fixture] def internal(t: Token): String = t.value
        |  @Experimental
        |  def source(s: Source): Config = this
        |  // def commented(t: Token): Config
        |  def label: String = "Token(x)"
        |
        |object Config:
        |  def apply(model: String, token: Option[Token] = None): Config = new Config(model, token) // leak
        |  def plain(model: String): Config = new Config(model, None)
        |
        |@Stable
        |trait Client:
        |  def fetch(
        |    s: String
        |  ): Token // leak
        |  def ok(s: String): String
        |
        |@Stable
        |enum Credential:
        |  case Anonymous
        |  case Static(key: String)
        |  case Dynamic(source: Source) // leak
        |  @Experimental case Fetched(source: Source)
        |
        |  def kind: String = this match
        |    case Dynamic(_) => "dynamic"
        |    case _          => "other"
        |
        |@Stable
        |final class Holder private (source: Source):
        |  def size: Int = 1
        |
        |@Stable
        |final class Wrapper(source: Source) // leak
        |
        |@Stable
        |object Factory:
        |  type Alias = Source // leak
        |  val default: Option[Token] = None // leak
        |  final case class Nested(token: Token) // leak
        |
        |@Experimental
        |object Tokens:
        |  def make(): Token = Token("t")
        |""".stripMargin
    )
    val expected = {
      val lines = read(pkg / "Api.scala")
      lines.zipWithIndex.collect { case (l, i) if l.contains("// leak") => i + 1 }.toSet
    }
    val (found, _) = violations(Seq(module), Nil)
    val leaked     = found.filter(_.problem.contains("signature names")).map(_.line).toSet
    val others     = found.filterNot(_.problem.contains("signature names"))
    (expected -- leaked).toSeq.sorted.map(l => s"Api.scala:$l is a leak the check did not report") ++
      (leaked -- expected).toSeq.sorted.map(l => s"Api.scala:$l is not a leak but the check reported it") ++
      others.map(v => s"${v.file.getName}:${v.line} unexpected tier violation: ${v.problem}")
  }

  def check(root: File, moduleNames: Seq[String], experimentalIn: Seq[scala.util.matching.Regex], log: Logger): Unit = {
    val broken = selfTest()
    if (broken.nonEmpty)
      throw new MessageOnlyException(
        s"""stabilityTierCheck's own fixtures failed, so its result cannot be trusted:
           |${broken.map("  " + _).mkString("\n")}
           |See StabilityTiers.selfTest in project/StabilityTiers.scala.""".stripMargin
      )
    val (bad, counts) = violations(moduleNames.map(n => root / "modules" / n), experimentalIn)
    log.info("Stability tier per frozen module (top-level public types):")
    counts.toSeq.sortBy(_._1).foreach { case (m, (s, e)) => log.info(f"  $m%-20s $s%4d @Stable  $e%3d @Experimental") }
    if (bad.nonEmpty) {
      val rows =
        bad.map(v => s"  ${v.file.relativeTo(root).getOrElse(v.file)}:${v.line}  ${v.text.take(80)}  ${v.problem}")
      throw new MessageOnlyException(
        s"""${bad.size} stability tier problem(s) in the frozen modules:
           |${rows.mkString("\n")}
           |Every top-level public type of a frozen module needs exactly one of
           |  import org.llm4s.annotation.Stable        // covered by the 1.x compatibility promise
           |  import org.llm4s.annotation.Experimental  // not covered; also required for the files the build lists
           |above its declaration (an Experimental companion needs its own annotation). A type that is
           |private or private[x] needs neither. A public member of a @Stable type must not name an
           |@Experimental type in its signature: promote that type to @Stable, or mark the member (or the
           |nested type holding it) @Experimental. See docs/reference/api-stability.md.""".stripMargin
      )
    }
  }
}
