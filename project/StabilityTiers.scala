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
 *
 * Finally it fails for a growth-unsafe case class (RFC 0002, #2369): a `case class` in a `@Stable` (or untiered)
 * public type, top-level or nested, whose primary constructor is not `private` / `private[x]` and has fields, and a
 * public `copy` defined in one. Adding a field to either would break binary compatibility after the baseline.
 * `GrowthExempted` lists the deliberately closed types; an entry that matches nothing is itself reported. The
 * cases of a `@Stable` `enum` are not checked.
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
   * A public member of a `@Stable` type: its keyword (`def`, `class`, ...), its modifiers, the name it declares,
   * the types enclosing it as (name, keyword, is a case class) from the outermost, its visible signature (the
   * (line, text) pieces `signature` returns) and its whole header.
   */
  final private case class Found(
    keyword: String,
    modifiers: String,
    name: String,
    owners: List[(String, String, Boolean)],
    visible: Seq[(Int, String)],
    header: Seq[(Int, String)]
  )

  private val DeclaredAfterKeyword = "^\\s+([A-Za-z_]\\w*)".r

  /**
   * Each public member of the file's `@Stable` types (and of their companions). A member that is `private`,
   * `protected` or `@Experimental` is left out with everything nested under it, and so are the bodies of `def`s,
   * `val`s, `var`s and `given`s. With `untiered`, a public top-level type with neither annotation counts as
   * `@Stable` too (the tier check reports it separately).
   */
  private[this] def stableMembers(lines: Vector[String], untiered: Boolean = false): Seq[Found] = {
    val decls            = topLevel(lines)
    def tierless(i: Int) = untiered && !annotated(lines, i, StableAnn) && !annotated(lines, i, ExperimentalAnn)
    val stableT = decls.collect {
      case (i, k, n) if k != "object" && (annotated(lines, i, StableAnn) || tierless(i)) => n
    }.toSet
    val extents = decls.zip(decls.drop(1).map(_._1) :+ lines.length)
    extents.flatMap { case ((start, kind, name), end) =>
      val stable =
        NonPublic.findFirstIn(lines(start)).isEmpty && !annotated(lines, start, ExperimentalAnn) &&
          (annotated(lines, start, StableAnn) || tierless(start) || (kind == "object" && stableT.contains(name)))
      if (!stable) Nil
      else {
        val found      = Seq.newBuilder[Found]
        var skipIndent = -1
        var owners     = List.empty[(Int, (String, String, Boolean))] // innermost first, with its indent
        var i          = start
        while (i < end) {
          val line = lines(i)
          if (line.trim.isEmpty || isComment(line)) i += 1
          else if (skipIndent >= 0 && indent(line) > skipIndent) i += 1
          else {
            skipIndent = -1
            owners = owners.dropWhile(_._1 >= indent(line))
            Member.findFirstMatchIn(line) match {
              case Some(m) if !(m.group(3) == "case" && code(line).contains("=>")) =>
                val keyword        = m.group(3)
                val valueLike      = Set("def", "val", "var", "given")(keyword)
                val hidden         = Hidden.findFirstIn(m.group(2)).nonEmpty
                val (pieces, last) = signature(lines, i, valueLike)
                val declared       = DeclaredAfterKeyword.findFirstMatchIn(line.drop(m.end)).fold("")(_.group(1))
                // A class whose constructor is private: its plain parameters are not members, so only the
                // header before them counts. A case class's parameters are public accessors whatever its
                // constructor.
                val isCase = "\\bcase\\b".r.findFirstIn(m.group(2)).nonEmpty
                val privateCtor = keyword == "class" && !isCase &&
                  "\\bclass\\s+\\w+(?:\\[[^\\]]*\\])?\\s+(?:private|protected)\\b".r
                    .findFirstIn(pieces.map(_._2).mkString(" "))
                    .nonEmpty
                val visible =
                  if (!privateCtor) pieces
                  else pieces.headOption.map { case (l, t) => (l, t.takeWhile(_ != '(')) }.toSeq
                if (hidden || annotated(lines, i, ExperimentalAnn)) skipIndent = indent(line)
                else {
                  found += Found(keyword, m.group(2), declared, owners.reverse.map(_._2), visible, pieces)
                  if (valueLike) skipIndent = indent(line)
                  else if (Set("class", "trait", "object", "enum")(keyword))
                    owners = (indent(line), (declared, keyword, keyword == "class" && isCase)) :: owners
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
        member.visible.iterator.flatMap { case (l, t) => names.findFirstMatchIn(t).map(n => (l, n.group(1))) }.take(1)
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
      member.visible.iterator
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

  // ---- growth safety of @Stable case classes (RFC 0002, #2369) ----

  /** A `@Stable` case class (by fully qualified name) that may keep a public constructor and `copy`, and why. */
  final case class GrowthExempt(name: String, reason: String)

  /**
   * `@Stable` case classes that keep a public constructor. RFC 0002 makes a growth-prone data type
   * `final case class X private (...)` with a companion `apply` and `with*` setters, because adding a field to a
   * public constructor or `copy` breaks binary compatibility once the MiMa baseline exists. An entry here is a type
   * that is deliberately closed (a single-value wrapper, a fixed record); one marked `TODO(#2369)` predates the check
   * and should be converted before the baseline, then removed from this list. An entry that matches no failing type
   * fails the check, so a converted type cannot leave its entry behind.
   */
  val GrowthExempted: Seq[GrowthExempt] = Seq(
    GrowthExempt(
      "org.llm4s.config.Llm4sConfig.TextEmbeddingModelSettings",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.config.ProvidersConfigModel.ProvidersConfig",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.context.CompressionRule",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt("org.llm4s.context.ContextStep", "TODO(#2369): predates the check; convert to a private constructor"),
    GrowthExempt(
      "org.llm4s.context.ConversationWindow",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.context.HistoryDigest",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.context.ManagedConversation",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.context.MessageTokenInfo",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt("org.llm4s.context.PipelineStep", "TODO(#2369): predates the check; convert to a private constructor"),
    GrowthExempt(
      "org.llm4s.context.SemanticBlock",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.context.StructuredInfo",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.context.TokenBreakdown",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.context.TokenUsageInfo",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt("org.llm4s.context.tokens.Token", "a single token id"),
    GrowthExempt(
      "org.llm4s.context.tokens.TokenizerAccuracy.Approximate",
      "a fixed accuracy case: a description and an accuracy"
    ),
    GrowthExempt(
      "org.llm4s.context.tokens.TokenizerAccuracy.Exact",
      "a fixed accuracy case holding only its description"
    ),
    GrowthExempt(
      "org.llm4s.context.tokens.TokenizerAccuracy.Unknown",
      "a fixed accuracy case holding only its description"
    ),
    GrowthExempt(
      "org.llm4s.error.AssistantError.CommandParseError",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.error.AssistantError.DisplayError",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.error.AssistantError.EOFError",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.error.AssistantError.FileError",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.error.AssistantError.IOError",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.error.AssistantError.SerializationError",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.error.AssistantError.SessionError",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.http.MultipartPart.FilePart",
      "a multipart file part is exactly a name, a path and a filename"
    ),
    GrowthExempt("org.llm4s.http.MultipartPart.TextField", "a multipart text field is exactly a name and a value"),
    GrowthExempt("org.llm4s.identity.TokenizerId", "a single-name identifier"),
    GrowthExempt(
      "org.llm4s.llmconnect.JsonlProviderExchangeSink",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.caching.CacheEntry",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.caching.CacheStats",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.model.AudioInput",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.model.Conversation",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.model.EmbeddingVector",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.model.ImageInput",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.model.MultimediaEmbeddingRequest",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.model.RawMediaInput",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.model.ResponseFormat.JsonSchema",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.model.SystemMessage",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt("org.llm4s.llmconnect.model.TextChunkInput", "a single-value wrapper over the text chunks"),
    GrowthExempt(
      "org.llm4s.llmconnect.model.ToolCall",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.model.VideoInput",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.provider.OpenAICompatibleClient.Settings",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.spi.EmbeddingProviderSection",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.spi.ProviderConfigKey",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.spi.ProviderDiscoveryFailure",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.spi.ProviderIdCollision",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.spi.ProviderModuleReport",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.spi.ProviderRegistryReport",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.llmconnect.streaming.SSEParser.SSEEvent",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt("org.llm4s.metrics.Outcome.Error", "an outcome case holding only its ErrorKind"),
    GrowthExempt(
      "org.llm4s.model.TransformationResult",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt("org.llm4s.toolapi.NullableSchema", "a single-value wrapper over the schema it makes nullable"),
    GrowthExempt("org.llm4s.toolapi.ToolCallError.Cancelled", "an error case holding only the tool name"),
    GrowthExempt(
      "org.llm4s.toolapi.ToolCallError.ExecutionError",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.toolapi.ToolCallError.HandlerError",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.toolapi.ToolCallError.InvalidArguments",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt("org.llm4s.toolapi.ToolCallError.NullArguments", "an error case holding only the tool name"),
    GrowthExempt(
      "org.llm4s.toolapi.ToolCallError.Timeout",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.toolapi.ToolCallError.UnknownFunction",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.toolapi.ToolExecutionStrategy.ParallelWithLimit",
      "a strategy case holding only its concurrency limit"
    ),
    GrowthExempt(
      "org.llm4s.toolapi.ToolParameterError.InvalidNesting",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.toolapi.ToolParameterError.MissingParameter",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.toolapi.ToolParameterError.MultipleErrors",
      "an error case holding only the errors it aggregates"
    ),
    GrowthExempt(
      "org.llm4s.toolapi.ToolParameterError.NullParameter",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.toolapi.ToolParameterError.TypeMismatch",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.AgentInitialized",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.AgentRunEnded",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.CacheHit",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.CacheMiss",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.CompletionReceived",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.CostRecorded",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.CustomEvent",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.EmbeddingUsageRecorded",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.ErrorOccurred",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.ImageGenerationCompleted",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.RAGOperationCompleted",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.TokenUsageRecorded",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt(
      "org.llm4s.trace.TraceEvent.ToolExecuted",
      "TODO(#2369): predates the check; convert to a private constructor"
    ),
    GrowthExempt("org.llm4s.trace.TracingMode.Named", "a TracingMode case holding only the backend name"),
    GrowthExempt(
      "org.llm4s.util.SecretPatterns.SecretMatch",
      "TODO(#2369): predates the check; convert to a private constructor"
    )
  )

  private val PackageLine = "^package\\s+([\\w.]+)$".r
  private val CaseMod     = "\\bcase\\b".r

  /** The file's package, joining chained `package a` / `package b` clauses. */
  private def packageOf(lines: Vector[String]): String =
    lines.map(l => code(l).trim.stripSuffix(";").trim).collect { case PackageLine(p) => p }.mkString(".")

  /** From `at`, the index just past the bracketed group that starts there (`(...)` or `[...]`), nesting aware. */
  private def closeOf(text: String, at: Int): Int = {
    var depth = 0
    var j     = at
    var done  = false
    while (!done && j < text.length) {
      val c = text.charAt(j)
      if (c == '(' || c == '[') depth += 1
      else if (c == ')' || c == ']') { depth -= 1; if (depth == 0) done = true }
      j += 1
    }
    j
  }

  /**
   * A case class header's primary constructor: whether it is `private` / `private[x]`, and the text of its first
   * parameter list (empty for `case class X()`).
   */
  private def constructor(header: String, name: String): (Boolean, String) =
    ("\\bclass\\s+" + java.util.regex.Pattern.quote(name) + "\\b").r.findFirstMatchIn(header) match {
      case None => (false, "")
      case Some(m) =>
        def ws(j: Int): Int = { var k = j; while (k < header.length && header.charAt(k).isWhitespace) k += 1; k }
        var j               = ws(m.end)
        if (j < header.length && header.charAt(j) == '[') j = ws(closeOf(header, j))
        val isPrivate = "^private\\b".r.findFirstIn(header.drop(j)).nonEmpty
        if (isPrivate) {
          j = ws(j + "private".length)
          if (j < header.length && header.charAt(j) == '[') j = ws(closeOf(header, j))
        }
        val params =
          if (j < header.length && header.charAt(j) == '(') header.substring(j + 1, closeOf(header, j) - 1) else ""
        (isPrivate, params)
    }

  /**
   * Case classes of the file's `@Stable` (or untiered) public types, nested ones included, whose constructor is not
   * private and whose first parameter list is not empty, and public `copy` methods defined in such a type's case
   * classes, as (zero-based line, fully qualified type name, problem).
   */
  private[this] def growthUnsafe(lines: Vector[String]): Seq[(Int, String, String)] = {
    val pkg                           = packageOf(lines)
    def qualified(names: Seq[String]) = (pkg +: names).filter(_.nonEmpty).mkString(".")
    stableMembers(lines, untiered = true).flatMap { f =>
      if (f.keyword == "class" && CaseMod.findFirstIn(f.modifiers).nonEmpty && f.header.nonEmpty) {
        val (isPrivate, params) = constructor(f.header.map(_._2).mkString(" "), f.name)
        if (isPrivate || params.trim.isEmpty) None
        else
          Some(
            (
              f.header.head._1,
              qualified(f.owners.map(_._1) :+ f.name),
              "is a @Stable case class whose constructor is public, so a field cannot be added after the baseline"
            )
          )
      } else if (f.keyword == "def" && f.name == "copy" && f.owners.lastOption.exists(_._3))
        f.header.headOption.map { case (l, _) =>
          (l, qualified(f.owners.map(_._1)), "is a public copy on a @Stable case class; make it private")
        }
      else None
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
   * @param growthExempt    `@Stable` case classes that may keep a public constructor; an entry that matches nothing
   *                        is reported, against `stableTiersFile`
   */
  def violations(
    modules: Seq[File],
    experimentalIn: Seq[scala.util.matching.Regex],
    denied: Seq[Denied] = ThirdPartyDenied,
    allowed: Seq[Allowed] = ThirdPartyAllowed,
    growthExempt: Seq[GrowthExempt] = GrowthExempted,
    stableTiersFile: File = file("project") / "StabilityTiers.scala"
  ): (Seq[Violation], Map[String, (Int, Int)]) = {
    val experimental = experimentalNames(modules)
    val exempt       = growthExempt.map(_.name).toSet
    val exemptUsed   = scala.collection.mutable.Set.empty[String]
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
        val growth = growthUnsafe(lines).flatMap { case (i, name, problem) =>
          if (exempt(name)) { exemptUsed += name; None }
          else Some((Some(Violation(file, i + 1, lines(i).trim, s"$name $problem")), false, false))
        }
        tiers ++ leaked ++ foreign ++ growth
      }
      module.getName -> ((results.count(_._2), results.count(_._3), results.flatMap(_._1)))
    }
    val stale = growthExempt.filterNot(e => exemptUsed(e.name)).map { e =>
      Violation(
        stableTiersFile,
        0,
        s"GrowthExempt(${e.name})",
        "is a GrowthExempted entry that matches no @Stable case class with a public constructor or copy; remove it"
      )
    }
    (perModule.flatMap(_._2._3) ++ stale, perModule.map { case (m, (s, e, _)) => m -> (s -> e) }.toMap)
  }

  /**
   * Runs the check against fixtures with known answers and returns what it got wrong: each fixture line marked
   * `// leak`, `// third-party` or `// growth` must be reported as that, and no other line; of the growth allow-list,
   * only the entry that matches nothing must be reported as stale. Empty when the check works.
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
    IO.write(
      pkg / "Growth.scala",
      """package fixture
        |
        |import org.llm4s.annotation.{ Experimental, Stable }
        |
        |@Stable
        |final case class Safe private (a: String, b: Int):
        |  def withA(a: String): Safe = copy(a = a)
        |  private def copy(a: String = a, b: Int = b): Safe = new Safe(a, b)
        |
        |object Safe:
        |  def apply(a: String, b: Int = 0): Safe = new Safe(a, b)
        |  final case class Inner private[fixture] (x: Int)
        |  final case class Open(x: Int) // growth
        |  private[fixture] final case class Hidden(x: Int)
        |
        |@Stable
        |final case class Scoped[F[_], A: Ordering] private[fixture] (a: A)
        |
        |@Stable
        |final case class Public(a: String, b: Int) // growth
        |
        |@Stable
        |case class Generic[F[_], A]( // growth
        |  a: F[A],
        |  b: String = "(x)"
        |) extends Serializable
        |
        |@Stable
        |final case class Copyable private (a: String):
        |  def copy(a: String = a): Copyable = new Copyable(a) // growth
        |
        |@Stable
        |final case class Empty()
        |
        |@Stable
        |final case class Closed(value: String)
        |
        |@Experimental
        |final case class Free(a: String)
        |
        |@Stable
        |final class NotCase(a: String)
        |
        |@Stable
        |sealed trait Shape
        |
        |object Shape:
        |  final case class Circle(r: Double) extends Shape // growth
        |  case object Dot extends Shape
        |
        |@Stable
        |object Holder {
        |  case class Deep(x: Int) // growth
        |  object Nested {
        |    final case class Deeper(x: Int) // growth
        |    final case class Allowed(x: Int)
        |  }
        |  @Experimental case class Exp(x: Int)
        |  def make(x: Int): Deep = Deep(x)
        |}
        |""".stripMargin
    )
    IO.write(
      pkg / "Untiered.scala",
      """package fixture.untiered
        |
        |final case class Bare(a: Int) // growth
        |""".stripMargin
    )
    def marked(file: String, marker: String): Set[Int] =
      read(pkg / file).zipWithIndex.collect { case (l, i) if l.contains(marker) => i + 1 }.toSet
    val fixtureAllowed = ThirdPartyAllowed ++ Seq(
      Allowed("org.slf4j.event.Level", "fixture: an exception to the deny-list", Some("ThirdParty\\.scala$".r)),
      Allowed("fansi.Color", "fixture: limited to another file, so it does not apply", Some("Elsewhere\\.scala$".r))
    )
    val fixtureExempt = Seq(
      GrowthExempt("fixture.Closed", "fixture: a deliberately closed wrapper"),
      GrowthExempt("fixture.Holder.Nested.Allowed", "fixture: a nested exemption"),
      GrowthExempt("fixture.Factory.Nested", "fixture: Api.scala's leak fixture, not under test here"),
      GrowthExempt("fixture.Settings", "fixture: ThirdParty.scala's fixture, not under test here"),
      GrowthExempt("fixture.Gone", "fixture: matches nothing, so it is reported as stale")
    )
    val (found, _)               = violations(Seq(module), Nil, ThirdPartyDenied, fixtureAllowed, fixtureExempt)
    def isStale(v: Violation)    = v.problem.contains("GrowthExempted entry")
    def isGrowth(v: Violation)   = v.problem.contains("@Stable case class") && !isStale(v)
    def isUntiered(v: Violation) = v.file.getName == "Untiered.scala" && v.problem.contains("carries neither")
    def isLeak(v: Violation)     = v.problem.contains("signature names")
    def isForeign(v: Violation)  = v.problem.contains("signature exposes third-party")
    def compare(file: String, expected: Set[Int], reported: Set[Int]): Seq[String] =
      (expected -- reported).toSeq.sorted.map(l => s"$file:$l is a violation the check did not report") ++
        (reported -- expected).toSeq.sorted.map(l => s"$file:$l is not a violation but the check reported it")
    val foreign     = found.filter(isForeign)
    val unexercised = ThirdPartyDenied.map(_.pkg).filterNot(p => foreign.exists(_.problem.contains(s"($p is deny")))
    compare("Api.scala", marked("Api.scala", "// leak"), found.filter(isLeak).map(_.line).toSet) ++
      compare("ThirdParty.scala", marked("ThirdParty.scala", "// third-party"), foreign.map(_.line).toSet) ++
      unexercised.map(p => s"deny-listed $p has no fixture in ThirdParty.scala that the check reports") ++
      compare(
        "Growth.scala",
        marked("Growth.scala", "// growth"),
        found.filter(isGrowth).filter(_.file.getName == "Growth.scala").map(_.line).toSet
      ) ++
      compare(
        "Untiered.scala",
        marked("Untiered.scala", "// growth"),
        found.filter(isGrowth).filter(_.file.getName == "Untiered.scala").map(_.line).toSet
      ) ++
      found
        .filter(isGrowth)
        .filterNot(v => Set("Growth.scala", "Untiered.scala")(v.file.getName))
        .map(v => s"${v.file.getName}:${v.line} unexpected growth violation: ${v.problem}") ++
      (if (found.filter(isStale).map(_.text) == Seq("GrowthExempt(fixture.Gone)")) Nil
       else
         Seq(
           s"stale GrowthExempt entries reported: ${found.filter(isStale).map(_.text)}, expected fixture.Gone only"
         )) ++
      found
        .filterNot(v => isLeak(v) || isForeign(v) || isGrowth(v) || isStale(v) || isUntiered(v))
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
    val (bad, counts) = violations(
      moduleNames.map(n => root / "modules" / n),
      experimentalIn,
      stableTiersFile = root / "project" / "StabilityTiers.scala"
    )
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
           |project/StabilityTiers.scala. A @Stable case class with fields needs a private constructor
           |(RFC 0002): `final case class X private (...)` with a companion `apply` and `with*` setters, and
           |no public `copy`; a type that is deliberately closed goes in GrowthExempted with its reason.
           |See docs/reference/api-stability.md.""".stripMargin
      )
    }
  }
}
