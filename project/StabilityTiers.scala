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
 *
 * And it fails for such a member whose signature names a third-party type from `ThirdPartyDenied` that no
 * `ThirdPartyAllowed` entry covers (RFC 0004, #2357): freezing a signature freezes the library behind it. A
 * reference counts when it is written fully qualified (`cats.Show`, `_root_.com.openai.X`) or through a name the
 * file imports (`import cats.data.NonEmptyList`, `import com.typesafe.config.{Config => TsConfig}`,
 * `import x.Y as Z`, or a package import such as `import com.typesafe` followed by `typesafe.config.Config`). A
 * name followed by a single `:` is a parameter being declared, and the name a member declares is skipped, so
 * neither is mistaken for a type. Being a source scanner it has limits:
 *  - a name that reaches the file through a wildcard import (`import cats.data.*`), through the package the file
 *    is in, or through an `export` is not resolved, so it is not reported;
 *  - a type alias declared elsewhere is not expanded: `@Stable def f: MyAlias` passes even when `MyAlias` is a
 *    `cats` type, although the alias itself is reported where it is a public member of a `@Stable` type;
 *  - imports are read file-wide, so an import inside a block applies to the whole file;
 *  - an inferred result type (a public `val` or `def` with no type ascription) is not seen;
 *  - inherited members, and parents written on a line after the type's header, are not read.
 * MiMa and code review cover what this misses.
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
   * The visible signature of each public member of the file's `@Stable` types (and of their companions), as the
   * (line, text) pieces `signature` returns. A member that is `private`, `protected` or `@Experimental` is left out
   * with everything nested under it, and so are the bodies of `def`s, `val`s, `var`s and `given`s.
   */
  private[this] def stableMembers(lines: Vector[String]): Seq[Seq[(Int, String)]] = {
    val decls   = topLevel(lines)
    val stableT = decls.collect { case (i, k, n) if k != "object" && annotated(lines, i, StableAnn) => n }.toSet
    val extents = decls.zip(decls.drop(1).map(_._1) :+ lines.length)
    extents.flatMap { case ((start, kind, name), end) =>
      val stable =
        NonPublic.findFirstIn(lines(start)).isEmpty && !annotated(lines, start, ExperimentalAnn) &&
          (annotated(lines, start, StableAnn) || (kind == "object" && stableT.contains(name)))
      if (!stable) Nil
      else {
        val found      = Seq.newBuilder[Seq[(Int, String)]]
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
                  found += visible
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

  /**
   * Public members of `@Stable` types whose signatures name a type in `experimental`.
   *
   * @param lines        one source file
   * @param experimental the names of the frozen modules' `@Experimental` top-level types
   */
  private[this] def leaks(lines: Vector[String], experimental: Set[String]): Seq[(Int, String)] =
    if (experimental.isEmpty) Nil
    else {
      val names = ("\\b(" + experimental.toSeq.sorted.map(java.util.regex.Pattern.quote).mkString("|") + ")\\b").r
      stableMembers(lines).flatMap { member =>
        member.iterator.flatMap { case (l, t) => names.findFirstMatchIn(t).map(n => (l, n.group(1))) }.take(1)
      }
    }

  // ---- third-party types in @Stable signatures (RFC 0004, #2357) ----

  /** A package a frozen module's `@Stable` signatures must not expose, and why. */
  final case class Denied(pkg: String, reason: String)

  /**
   * A third-party reference that is deliberately part of the frozen API. `prefix` is a package or a fully
   * qualified type; `paths`, when given, limits the entry to source files whose path it matches.
   */
  final case class Allowed(prefix: String, reason: String, paths: Option[scala.util.matching.Regex] = None)

  /**
   * RFC 0004 (docs/rfcs/README.md, #1133 pass 8): a frozen module's public signatures expose no third-party type,
   * because freezing a signature freezes the library behind it. These are the libraries a frozen module (or a
   * provider SDK next to one) actually has on its classpath, so the ones a signature could plausibly leak.
   */
  val ThirdPartyDenied: Seq[Denied] = Seq(
    Denied("fansi", "console styling; pass 8 made the agent console UI private[assistant] for it"),
    Denied("cats", "type classes and data types; the API takes plain Scala types"),
    Denied("pureconfig", "config loading is an implementation detail; loaders return Result"),
    Denied("com.typesafe.config", "config loading is an implementation detail; loaders return Result"),
    Denied("com.knuddels.jtokkit", "the tokenizer behind core's token counting"),
    Denied("org.slf4j", "logging is an implementation detail"),
    Denied("okhttp3", "HTTP transport is an implementation detail"),
    Denied("com.squareup.okhttp", "HTTP transport is an implementation detail"),
    Denied("com.fasterxml.jackson", "the JSON library of the vendor SDKs; the API uses ujson"),
    Denied("com.openai", "the OpenAI SDK, an implementation detail of llm4s-openai"),
    Denied("com.anthropic", "the Anthropic SDK, an implementation detail of llm4s-anthropic"),
    Denied("software.amazon", "the AWS SDK, an implementation detail of the Bedrock provider")
  )

  /**
   * Third-party types the frozen API exposes on purpose. An entry here is checked before the deny-list, so it
   * can also make an exception to it; give every one its reason.
   */
  val ThirdPartyAllowed: Seq[Allowed] = Seq(
    Allowed(
      "ujson",
      "JSON values (tool arguments and schemas, structured output) are ujson.Value throughout the API by design"
    ),
    Allowed(
      "upickle",
      "the API's JSON codecs are upickle ReadWriters (macroRW, DurationJson.millisRW); upickle and ujson are core's " +
        "one JSON dependency"
    ),
    // TODO(#2360): decide before the baseline whether OpenAIToolHelper stays Stable over openai-java types,
    // becomes private[llm4s] or becomes @Experimental; then remove or reword this entry.
    Allowed(
      "com.openai.models.chat.completions",
      "OpenAIToolHelper (llm4s-openai) converts a ToolRegistry to openai-java's ChatCompletionTool and sets it on a " +
        "ChatCompletionCreateParams.Builder; it predates RFC 0004's check and is pending #2360",
      Some("/org/llm4s/toolapi/OpenAIToolHelper\\.scala$".r)
    )
  )

  private val ImportLine = "^\\s*import\\s+(.*)$".r
  private val Ident      = "[A-Za-z_]\\w*"
  // A name or a dotted path to one, not preceded by a dot or a word character (so not the tail of another path).
  // A path followed by a single `:` is a name being declared (a parameter or a given), not a type.
  private val PathRef = ("(?<![\\w.])((?:_root_\\.)?" + Ident + "+(?:\\." + Ident + ")*+)(?!\\s*:(?!:))").r
  // The name a member or a type declares is not a reference.
  private val DeclaredName =
    "\\b(?:(?:case\\s+)?(?:def|val|var|type|class|trait|object|enum)|case)\\s+[A-Za-z_]\\w*".r

  private def splitTop(text: String, sep: Char): Seq[String] = {
    val parts = Seq.newBuilder[String]
    val cur   = new StringBuilder
    var depth = 0
    text.foreach { c =>
      if (c == '{') depth += 1
      if (c == '}') depth -= 1
      if (c == sep && depth == 0) { parts += cur.toString; cur.clear() }
      else cur.append(c)
    }
    parts += cur.toString
    parts.result().map(_.trim).filter(_.nonEmpty)
  }

  /**
   * What each simple name a file imports stands for, fully qualified: `import a.b.C` binds `C`, `import a.b.{C => D}`
   * and `import a.b.C as D` bind `D`, and `import a.b` binds `b` to the package. An import whose path starts with a
   * name an earlier import bound is resolved through it. Wildcards bind nothing (see `check`'s limitations).
   */
  private[this] def imports(lines: Vector[String]): Map[String, String] = {
    val bound = scala.collection.mutable.LinkedHashMap.empty[String, String]
    def resolve(path: String): String = {
      val p     = path.stripPrefix("_root_.")
      val first = p.takeWhile(_ != '.')
      bound.get(first).filter(_ => !path.startsWith("_root_.")).fold(p)(full => full + p.drop(first.length))
    }
    def bind(prefix: String, selector: String): Unit = {
      val parts = selector.split("\\s*(?:=>|\\bas\\b)\\s*").map(_.trim)
      val name  = parts.head
      val alias = parts.lift(1).getOrElse(name)
      if (name.matches(Ident) && name != "_" && name != "given" && alias.matches(Ident) && alias != "_")
        bound(alias) = if (prefix.isEmpty) resolve(name) else s"$prefix.$name"
    }
    var i = 0
    while (i < lines.length) {
      ImportLine.findFirstMatchIn(code(lines(i))) match {
        case Some(m) if !isComment(lines(i)) =>
          var text = m.group(1)
          while (text.count(_ == '{') > text.count(_ == '}') && i + 1 < lines.length) {
            i += 1
            text += " " + code(lines(i))
          }
          splitTop(text, ',').foreach { clause =>
            val brace = clause.indexOf('{')
            if (brace >= 0) {
              val prefix = resolve(clause.take(brace).trim.stripSuffix("."))
              splitTop(clause.drop(brace + 1).takeWhile(_ != '}'), ',').foreach(bind(prefix, _))
            } else {
              val parts  = clause.split("\\s+as\\s+").map(_.trim)
              val path   = parts.head
              val dot    = path.lastIndexOf('.')
              val prefix = if (dot < 0) "" else resolve(path.take(dot))
              val sel    = path.drop(dot + 1) + parts.lift(1).fold("")(a => s" as $a")
              bind(prefix, sel)
            }
          }
        case _ => ()
      }
      i += 1
    }
    bound.toMap
  }

  /**
   * Public members of `@Stable` types whose signatures name a deny-listed third-party type that no allow-list entry
   * covers, as (zero-based line, fully qualified reference, deny-listed package).
   */
  private[this] def thirdParty(
    file: File,
    lines: Vector[String],
    denied: Seq[Denied],
    allowed: Seq[Allowed]
  ): Seq[(Int, String, String)] = {
    val bound                               = imports(lines)
    def under(full: String, prefix: String) = full == prefix || full.startsWith(prefix + ".")
    def offending(full: String): Option[String] =
      if (allowed.exists(a => under(full, a.prefix) && a.paths.forall(_.findFirstIn(file.getPath).nonEmpty))) None
      else denied.find(d => under(full, d.pkg)).map(_.pkg)
    stableMembers(lines).flatMap { member =>
      member.iterator
        .flatMap { case (l, t) =>
          PathRef.findAllMatchIn(DeclaredName.replaceAllIn(t, " ")).flatMap { m =>
            val path  = m.group(1)
            val root  = path.startsWith("_root_.")
            val p     = path.stripPrefix("_root_.")
            val first = p.takeWhile(_ != '.')
            val full  = (if (root) None else bound.get(first)).fold(p)(_ + p.drop(first.length))
            offending(full).map(pkg => (l, full, pkg))
          }
        }
        .take(1)
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
   * @param denied          packages a `@Stable` public signature must not name
   * @param allowed         third-party references that are deliberately API, checked before `denied`
   */
  def violations(
    modules: Seq[File],
    experimentalIn: Seq[scala.util.matching.Regex],
    denied: Seq[Denied] = ThirdPartyDenied,
    allowed: Seq[Allowed] = ThirdPartyAllowed
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
        val foreign = thirdParty(file, lines, denied, allowed).map { case (i, full, pkg) =>
          (
            Some(
              Violation(
                file,
                i + 1,
                lines(i).trim,
                s"is public in a @Stable type but its signature exposes third-party $full ($pkg is deny-listed by RFC 0004)"
              )
            ),
            false,
            false
          )
        }
        tiers ++ leaked ++ foreign
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
    IO.write(
      pkg / "ThirdParty.scala",
      """package fixture
        |
        |import org.llm4s.annotation.{ Experimental, Stable }
        |import cats.data.NonEmptyList
        |import com.typesafe.config.{ Config => TsConfig, ConfigFactory }
        |import com.knuddels.jtokkit.api.Encoding as TokEncoding
        |import org.slf4j.Logger, scala.util.Try
        |import pureconfig
        |import com.openai.client.{
        |  OpenAIClient,
        |  *
        |}
        |import com.typesafe
        |import org.slf4j.event.Level
        |import cats.syntax.all.*
        |
        |/** A fansi.Str in a comment is not a signature. */
        |@Stable
        |trait Leaky:
        |  def a: fansi.Str // third-party
        |  def b(xs: cats.data.NonEmptyList[String]): Unit // third-party
        |  def c: NonEmptyList[Int] // third-party
        |  def d(
        |    name: String,
        |    c: TsConfig // third-party
        |  ): Unit
        |  def e: TokEncoding // third-party
        |  def f(log: Logger): Unit // third-party
        |  def g: pureconfig.ConfigReader[String] // third-party
        |  def h: okhttp3.OkHttpClient // third-party
        |  def h2: com.squareup.okhttp.OkHttpClient // third-party
        |  def i: com.fasterxml.jackson.databind.JsonNode // third-party
        |  def j: OpenAIClient // third-party
        |  def k: _root_.com.anthropic.client.AnthropicClient // third-party
        |  def l: software.amazon.awssdk.regions.Region // third-party
        |  def m: typesafe.config.ConfigValue // third-party
        |  def n: fansi.Color // third-party
        |  def json: ujson.Value
        |  def codec: upickle.default.ReadWriter[Int]
        |  def level: Level
        |  def config(cats: Int, typesafe: String, pureconfig: Try[Int]): String
        |  def label: String = "cats.Show"
        |  def body: Int =
        |    val logger: Logger = ???
        |    1
        |  private def p: fansi.Str
        |  protected def q: cats.Show[Int]
        |  @Experimental def r: cats.Show[Int]
        |  // def s: cats.Show[Int]
        |
        |@Stable
        |final case class Settings(config: TsConfig) // third-party
        |
        |object Settings:
        |  def load(): Either[pureconfig.error.ConfigReaderFailures, Settings] = ??? // third-party
        |  def fromString(s: String): Settings = Settings(ConfigFactory.parseString(s))
        |  private[fixture] def raw: TsConfig = ???
        |
        |@Stable
        |final class Typed private (config: TsConfig):
        |  def size: Int = 1
        |
        |@Stable
        |object Aliases:
        |  type Show[A] = cats.Show[A] // third-party
        |  given cats.Eq[Int] = ??? // third-party
        |
        |@Experimental
        |trait Free:
        |  def a: fansi.Str
        |
        |private[fixture] object Internal:
        |  def a: cats.Show[Int] = ???
        |""".stripMargin
    )
    def marked(file: String, marker: String): Set[Int] =
      read(pkg / file).zipWithIndex.collect { case (l, i) if l.contains(marker) => i + 1 }.toSet
    val fixtureAllowed = ThirdPartyAllowed ++ Seq(
      Allowed("org.slf4j.event.Level", "fixture: an exception to the deny-list", Some("ThirdParty\\.scala$".r)),
      Allowed("fansi.Color", "fixture: limited to another file, so it does not apply", Some("Elsewhere\\.scala$".r))
    )
    val (found, _)              = violations(Seq(module), Nil, ThirdPartyDenied, fixtureAllowed)
    def isLeak(v: Violation)    = v.problem.contains("signature names")
    def isForeign(v: Violation) = v.problem.contains("signature exposes third-party")
    def compare(file: String, expected: Set[Int], reported: Set[Int]): Seq[String] =
      (expected -- reported).toSeq.sorted.map(l => s"$file:$l is a leak the check did not report") ++
        (reported -- expected).toSeq.sorted.map(l => s"$file:$l is not a leak but the check reported it")
    val foreign     = found.filter(isForeign)
    val unexercised = ThirdPartyDenied.map(_.pkg).filterNot(p => foreign.exists(_.problem.contains(s"($p is deny")))
    compare("Api.scala", marked("Api.scala", "// leak"), found.filter(isLeak).map(_.line).toSet) ++
      compare("ThirdParty.scala", marked("ThirdParty.scala", "// third-party"), foreign.map(_.line).toSet) ++
      unexercised.map(p => s"deny-listed $p has no fixture in ThirdParty.scala that the check reports") ++
      found
        .filterNot(v => isLeak(v) || isForeign(v))
        .map(v => s"${v.file.getName}:${v.line} unexpected tier violation: ${v.problem}")
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
           |nested type holding it) @Experimental. Nor may it expose a deny-listed third-party type
           |(RFC 0004): use an llm4s or Scala type instead, make the member private[llm4s], or, if the
           |type is deliberately API, add a ThirdPartyAllowed entry with its reason in
           |project/StabilityTiers.scala. See docs/reference/api-stability.md.""".stripMargin
      )
    }
  }
}
