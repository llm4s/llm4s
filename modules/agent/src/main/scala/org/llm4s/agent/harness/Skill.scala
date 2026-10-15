package org.llm4s.agent.harness

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, NonRecoverableError }
import org.llm4s.types.Result

import scala.collection.immutable.ListMap

/**
 * One [[https://agentskills.io/ Agent Skill]]: the frontmatter of its `SKILL.md`, at `path`. The model reads the file
 * itself, and the skill's other files, with `read_file`; `SkillsMiddleware` lists the `name` and `description`.
 *
 * @param name the skill's name: at most 64 characters of `a-z`, `0-9` and `-`, equal to its folder's name
 * @param description what the skill does and when to use it
 * @param path the skill's `SKILL.md`
 * @param allowedTools the tools the skill expects (`allowed-tools`), parsed but not enforced
 * @param license the skill's licence, if it names one
 * @param metadata the skill's `metadata` map, in file order
 */
@Experimental
final case class Skill private (
  name: String,
  description: String,
  path: VPath,
  allowedTools: Vector[String],
  license: Option[String],
  metadata: Map[String, String]
):
  def withName(n: String): Skill                  = new Skill(n, description, path, allowedTools, license, metadata)
  def withDescription(d: String): Skill           = new Skill(name, d, path, allowedTools, license, metadata)
  def withPath(p: VPath): Skill                   = new Skill(name, description, p, allowedTools, license, metadata)
  def withAllowedTools(t: Vector[String]): Skill  = new Skill(name, description, path, t, license, metadata)
  def withLicense(l: Option[String]): Skill       = new Skill(name, description, path, allowedTools, l, metadata)
  def withLicense(l: String): Skill               = withLicense(Some(l))
  def withMetadata(m: Map[String, String]): Skill = new Skill(name, description, path, allowedTools, license, m)

@Experimental
object Skill:

  def apply(
    name: String,
    description: String,
    path: VPath,
    allowedTools: Vector[String] = Vector.empty,
    license: Option[String] = None,
    metadata: Map[String, String] = Map.empty
  ): Skill = new Skill(name, description, path, allowedTools, license, metadata)

  /** The file name of a skill's definition in its folder. */
  val FileName: String = "SKILL.md"

  /** The most characters of a `SKILL.md` read for its frontmatter: the frontmatter must close within them. */
  val MaxFrontmatterChars: Int = 16 * 1024

  /** The longest name the standard allows. */
  val MaxNameChars: Int = 64

  /** The longest description the standard allows, in characters (code points). */
  val MaxDescriptionChars: Int = 1024

  /** The longest `compatibility` the standard allows, in characters (code points). */
  val MaxCompatibilityChars: Int = 500

  /**
   * The skill defined by `content`, the text of the `SKILL.md` at `path`: its frontmatter, read by a parser written
   * for it (no YAML library), then checked against the standard. A [[SkillFormatError]] names the line.
   */
  def parse(path: VPath, content: String): Result[Skill] =
    fromLines(path, Text.lines(content), closed = true)

  /** The skill of a file's first lines, `closed` when they are the whole file or end at the closing `---`. */
  private[harness] def fromLines(path: VPath, lines: Vector[String], closed: Boolean): Result[Skill] =
    Frontmatter.parse(path, lines, closed).flatMap(fields => fromFields(path, fields))

  private def fromFields(path: VPath, fields: ListMap[String, Frontmatter.Field]): Result[Skill] =
    def error(line: Int, problem: String) = Left(SkillFormatError(path, line, problem))
    def string(key: String, required: Boolean): Result[Option[String]] =
      fields.get(key) match
        case None if required => error(1, s"`$key` is required")
        case None             => Right(None)
        // a field the standard defines is never empty when given: a key with no value, or a blank one, is malformed
        case Some(Frontmatter.Field(Frontmatter.Value.Empty, line))  => error(line, s"`$key` is empty")
        case Some(Frontmatter.Field(Frontmatter.Value.Str(s), line)) =>
          // as parsed: a plain value is already trimmed, and a quoted one is checked with the spaces it quotes
          if s.trim.isEmpty then error(line, s"`$key` is empty") else Right(Some(s))
        case Some(Frontmatter.Field(_, line)) => error(line, s"`$key` must be a string")
    // the standard bounds a field it defines from 1 character to `max`: empty is as non-conforming as too long
    def longer(key: String, value: String, max: Int): Result[Unit] =
      val line = fields.get(key).fold(1)(_.line)
      if value.isEmpty then error(line, s"`$key` is empty")
      else if value.codePointCount(0, value.length) <= max then Right(())
      else error(line, s"`$key` is longer than $max characters")
    val folder = path.parent.map(_.name).getOrElse("")
    for
      name <- string("name", required = true).map(_.getOrElse(""))
      nameLine = fields.get("name").fold(1)(_.line)
      _           <- nameProblem(name, folder).fold[Result[Unit]](Right(()))(error(nameLine, _))
      description <- string("description", required = true).map(_.getOrElse(""))
      _           <- longer("description", description, MaxDescriptionChars)
      license     <- string("license", required = false)
      // not exposed, but held to the standard like every field it defines
      compatibility <- string("compatibility", required = false)
      _             <- compatibility.fold[Result[Unit]](Right(()))(longer("compatibility", _, MaxCompatibilityChars))
      tools <- fields.get("allowed-tools") match
        case None => Right(Vector.empty)
        case Some(Frontmatter.Field(Frontmatter.Value.Str(s), _)) if s.trim.nonEmpty =>
          Right(s.trim.split("\\s+").toVector.filter(_.nonEmpty))
        case Some(Frontmatter.Field(Frontmatter.Value.Items(xs), _)) => Right(xs.map(_.trim).filter(_.nonEmpty))
        case Some(Frontmatter.Field(_, line)) => error(line, "`allowed-tools` must be a string or a list")
      metadata <- fields.get("metadata") match
        case None                                                     => Right(ListMap.empty[String, String])
        case Some(Frontmatter.Field(Frontmatter.Value.Entries(m), _)) => Right(m)
        case Some(Frontmatter.Field(_, line)) => error(line, "`metadata` must be a map of strings")
    yield Skill(name, description, path, tools, license, metadata)

  private val NamePattern = "[a-z0-9]+(-[a-z0-9]+)*".r

  /** Why `name` is not a valid name of a skill in the folder `folder`, if it is not. */
  private def nameProblem(name: String, folder: String): Option[String] =
    if name.length > MaxNameChars then Some(s"`name` is longer than $MaxNameChars characters")
    else if !NamePattern.matches(name) then
      Some("`name` must be lowercase letters, digits and single hyphens, not starting or ending with one")
    else if name != folder then Some("`name` must equal the name of the skill's folder")
    else None

/**
 * A `SKILL.md` whose frontmatter is not in the restricted format, or breaks the standard's rules: the file, the
 * 1-based line, and the problem in the parser's own words, which never quote the file.
 */
@Experimental
final case class SkillFormatError(path: VPath, line: Int, problem: String) extends LLMError with NonRecoverableError:
  override val message: String = s"${path.value}:$line: $problem"

/**
 * The restricted frontmatter parser: `key: value` lines whose value is a plain scalar, a quoted string or a flow list
 * of scalars; a key with no value followed by a block list, or (one level) by indented `key: scalar` entries. Blank
 * lines and comments are skipped. Anything else is an error naming the line.
 */
private[harness] object Frontmatter:

  enum Value:
    case Str(value: String)
    case Items(values: Vector[String])
    case Entries(values: ListMap[String, String])

    /** A key with no value, and nothing beneath it. */
    case Empty

  /** A key's value and the line its key is on. */
  final case class Field(value: Value, line: Int)

  private val KeyPattern = "[A-Za-z0-9][A-Za-z0-9_.-]*".r

  /** The only key whose value may be a map. */
  private val MapKey = "metadata"

  private type Parsed[A] = Either[(Int, String), A]

  /**
   * The fields of the frontmatter at the start of `lines` (the file's first lines, numbered from 1). `closed` says the
   * lines are the whole file or reach the closing `---`; when they do not, a frontmatter that does not close in them
   * is too long.
   */
  def parse(path: VPath, lines: Vector[String], closed: Boolean): Result[ListMap[String, Field]] =
    val first = lines.headOption.map(_.stripPrefix("\uFEFF").stripTrailing)
    val body  = lines.iterator.zipWithIndex.drop(1).map((l, i) => (l, i + 1))
    val parsed: Parsed[ListMap[String, Field]] =
      if !first.contains("---") then Left((1, "the file must start with a `---` line opening the frontmatter"))
      else
        val upTo = body.takeWhile((l, _) => l.stripTrailing != "---").toVector
        if upTo.size == lines.size - 1 then
          Left(
            (
              1,
              if closed then "the frontmatter is not closed by a `---` line"
              else s"the frontmatter is not closed within ${Skill.MaxFrontmatterChars} characters"
            )
          )
        else
          // every character YAML forbids, checked once over the whole frontmatter, raw or spelled by an escape
          upTo.collectFirst { case (l, n) if !printable(l) => n } match
            case Some(n) => Left((n, "a character YAML does not allow (a control character)"))
            case None    => fields(upTo).flatMap(checkedValues)
    parsed.left.map((line, problem) => SkillFormatError(path, line, problem))

  /**
   * Whether every code point of `s` is one YAML 1.2 allows in a document (c-printable): tab, the printable ASCII range,
   * NEL, and the rest of Unicode but the C1 controls, surrogates and U+FFFE/U+FFFF. (A line holds no LF or CR.)
   */
  private def printable(s: String): Boolean =
    s.codePoints.allMatch(cp =>
      cp == 0x9 || (cp >= 0x20 && cp <= 0x7e) || cp == 0x85 || (cp >= 0xa0 && cp <= 0xd7ff) ||
        (cp >= 0xe000 && cp <= 0xfffd) || (cp >= 0x10000 && cp <= 0x10ffff)
    )

  /** The parsed values, refused when an escape spelled a character YAML forbids raw (other than a line break or tab). */
  private def checkedValues(found: ListMap[String, Field]): Parsed[ListMap[String, Field]] =
    def ok(s: String) = printable(s.replace('\n', ' ').replace('\r', ' '))
    found.collectFirst {
      case (_, Field(Value.Str(s), n)) if !ok(s)                   => n
      case (_, Field(Value.Items(xs), n)) if !xs.forall(ok)        => n
      case (_, Field(Value.Entries(m), n)) if !m.values.forall(ok) => n
    } match
      case Some(n) => Left((n, "a character YAML does not allow (a control character)"))
      case None    => Right(found)

  /** An open block: a key with no inline value, and what has followed it. */
  final private case class Block(
    key: String,
    line: Int,
    items: Vector[String],
    entries: ListMap[String, String],
    indent: Option[Int]
  )

  private def fields(lines: Vector[(String, Int)]): Parsed[ListMap[String, Field]] =
    @annotation.tailrec
    def loop(
      rest: List[(String, Int)],
      done: ListMap[String, Field],
      open: Option[Block]
    ): Parsed[ListMap[String, Field]] =
      rest match
        case Nil => close(done, open)
        case (raw, n) :: tail =>
          val line    = raw.stripTrailing
          val trimmed = line.trim
          if trimmed.isEmpty || trimmed.startsWith("#") then loop(tail, done, open)
          else
            val leading = line.takeWhile(c => c == ' ' || c == '\t')
            if leading.contains('\t') then Left((n, "indentation must be spaces, not tabs"))
            else
              val indent = leading.length
              if trimmed == "-" || trimmed.startsWith("- ") then
                listItem(n, indent, trimmed.drop(1).trim, open) match
                  case Left(e)      => Left(e)
                  case Right(block) => loop(tail, done, Some(block))
              else if indent == 0 then
                close(done, open) match
                  case Left(e) => Left(e)
                  case Right(closed) =>
                    keyValue(n, trimmed) match
                      case Left(e)                                 => Left(e)
                      case Right((key, _)) if closed.contains(key) => Left((n, "a key is given more than once"))
                      case Right((key, None)) =>
                        loop(tail, closed, Some(Block(key, n, Vector.empty, ListMap.empty, None)))
                      case Right((key, Some(value))) => loop(tail, closed.updated(key, Field(value, n)), None)
              else
                mapEntry(n, indent, trimmed, open) match
                  case Left(e)      => Left(e)
                  case Right(block) => loop(tail, done, Some(block))
    loop(lines.toList, ListMap.empty, None)

  private def close(done: ListMap[String, Field], open: Option[Block]): Parsed[ListMap[String, Field]] =
    Right(open.fold(done) { b =>
      val value =
        if b.entries.nonEmpty then Value.Entries(b.entries)
        else if b.items.nonEmpty then Value.Items(b.items)
        else Value.Empty
      done.updated(b.key, Field(value, b.line))
    })

  private def listItem(n: Int, indent: Int, item: String, open: Option[Block]): Parsed[Block] =
    open match
      case None                          => Left((n, "a list item must follow a key with no value"))
      case Some(b) if b.entries.nonEmpty => Left((n, "a list item cannot follow map entries"))
      // an item at another indentation would be nested, or a new list
      case Some(b) if b.indent.exists(_ != indent) =>
        Left((n, "list items must share one indentation; nested lists are not supported"))
      case Some(b) =>
        if item.startsWith("[") then Left((n, "nested lists and maps are not supported"))
        else if item.isEmpty then Left((n, "a list item is empty"))
        else scalar(n, item, inList = true).map(v => b.copy(items = b.items :+ v, indent = Some(indent)))

  private def mapEntry(n: Int, indent: Int, text: String, open: Option[Block]): Parsed[Block] =
    open match
      case None =>
        Left((n, "an indented line must be a list item or a `metadata` entry; multi-line values are not supported"))
      case Some(b) if b.key != MapKey  => Left((n, s"only `$MapKey` may hold a map"))
      case Some(b) if b.items.nonEmpty => Left((n, "map entries cannot follow list items"))
      case Some(b) if b.indent.exists(_ != indent) =>
        Left((n, "map entries must share one indentation; nested maps are not supported"))
      case Some(b) =>
        keyValue(n, text).flatMap {
          case (key, _) if b.entries.contains(key) => Left((n, "a key is given more than once"))
          case (_, None)                           => Left((n, "nested maps are not supported"))
          case (key, Some(Value.Str(v))) => Right(b.copy(entries = b.entries.updated(key, v), indent = Some(indent)))
          case (_, Some(_))              => Left((n, s"a `$MapKey` value must be a string"))
        }

  /** `key: value` or `key:`; the value parsed when there is one. */
  private def keyValue(n: Int, text: String): Parsed[(String, Option[Value])] =
    val colon = text.indexOf(':')
    if colon <= 0 then Left((n, "expected `key: value`"))
    else
      val key  = text.substring(0, colon)
      val rest = text.substring(colon + 1)
      if !KeyPattern.matches(key) then Left((n, "a key must be letters, digits, `_`, `.` and `-`"))
      // a key is a string: one YAML reads as a null, boolean or number (`1`, `true`) is not, and keys are never quoted
      else if NotAString.matches(key) then
        Left((n, "YAML reads this key as a null, boolean or number; keys are strings"))
      else if rest.nonEmpty && rest.head != ' ' then Left((n, "expected a space after the key's `:`"))
      else
        val value = rest.trim
        if value.isEmpty || value.startsWith("#") then Right((key, None))
        else inlineValue(n, value).map(v => (key, Some(v)))

  /** A value on its key's line: a flow list, or a scalar. */
  private def inlineValue(n: Int, value: String): Parsed[Value] =
    if value.startsWith("[") then flowList(n, value).map(Value.Items(_))
    else scalar(n, value, inList = false).map(Value.Str(_))

  /**
   * The plain values YAML 1.2's core schema reads as something other than a string: null, a boolean, an integer
   * (decimal, octal `0o`, hex `0x`) or a float (with `.inf` and `.nan`). Every field here is a string, so such a value
   * must be quoted.
   */
  private val NotAString =
    ("~|null|Null|NULL|true|True|TRUE|false|False|FALSE|[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+|" +
      "[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?|[-+]?\\.(inf|Inf|INF)|\\.(nan|NaN|NAN)").r

  private val Unsupported: Map[Char, String] = Map(
    '{' -> "flow maps are not supported",
    '|' -> "block scalars (`|`, `>`) are not supported",
    '>' -> "block scalars (`|`, `>`) are not supported",
    '&' -> "anchors are not supported",
    '*' -> "aliases are not supported",
    '!' -> "tags are not supported",
    '%' -> "directives are not supported",
    '@' -> "a plain value cannot start with `@`",
    '`' -> "a plain value cannot start with a backtick"
  )

  /** A scalar: quoted, or plain with a ` #` comment dropped. In a list, a plain item cannot be a `key: value`. */
  private def scalar(n: Int, value: String, inList: Boolean): Parsed[String] =
    value.head match
      case '"'                          => quoted(n, value, '"').flatMap((s, rest) => trailing(n, rest).map(_ => s))
      case '\''                         => quoted(n, value, '\'').flatMap((s, rest) => trailing(n, rest).map(_ => s))
      case c if Unsupported.contains(c) => Left((n, Unsupported(c)))
      case _ =>
        val plain = withoutComment(value)
        plainProblem(plain, inList).fold(Right(plain))(problem => Left((n, problem)))

  /**
   * Why `plain`, an unquoted value, is not one YAML reads as the same string, if it is not - one rule for every place a
   * plain value stands (a key's value, a `metadata` value, a block or flow list item): `a: b` or `a:` is a map, a
   * leading `- ` or `? ` an entry, and a leading indicator is not text.
   */
  private def plainProblem(plain: String, inList: Boolean): Option[String] =
    val nested = "nested lists and maps are not supported"
    // a list item that is only a comment (`- # off`) is empty, not the comment's text
    if plain.isEmpty then Some("a value is empty")
    else if plain.contains(": ") || plain.endsWith(":") then
      Some(if inList then nested else "a plain value cannot hold `: `; quote it")
    else if plain == "-" || plain.startsWith("- ") || plain == "?" || plain.startsWith("? ") then Some(nested)
    else if NotAString.matches(plain) then Some("YAML reads this plain value as a null, boolean or number; quote it")
    else
      plain.headOption.flatMap(c =>
        Unsupported.get(c).orElse(Option.when("[]{},#".contains(c))(s"a plain value cannot start with `$c`; quote it"))
      )

  /** `value` up to a ` #` comment, trimmed. */
  private def withoutComment(value: String): String =
    // a `#` at the start, or after YAML whitespace (a space or a tab), starts a comment
    val i = CommentStart.findFirstMatchIn(value).fold(-1)(_.start)
    (if i < 0 then value else value.substring(0, i)).trim

  private val CommentStart = "(?:^|(?<=[ \\t]))#".r

  /** Only a comment may follow a closing quote or bracket. */
  private def trailing(n: Int, rest: String): Parsed[Unit] =
    val r = rest.trim
    if r.isEmpty || r.startsWith("#") then Right(()) else Left((n, "unexpected text after a closing quote or bracket"))

  /**
   * The quoted string at the start of `value`, opened by `quote`, and the text after it. Double quotes take the escapes
   * `\\`, `\"`, `\/`, `\n`, `\t`, `\r`, `\0` and `\uXXXX`; single quotes double a quote to include one.
   */
  private def quoted(n: Int, value: String, quote: Char): Parsed[(String, String)] =
    val out = new java.lang.StringBuilder()
    @annotation.tailrec
    def loop(i: Int): Parsed[(String, String)] =
      if i >= value.length then Left((n, "a quoted string is not closed on its line"))
      else
        val c = value.charAt(i)
        if quote == '\'' && c == '\'' then
          if i + 1 < value.length && value.charAt(i + 1) == '\'' then
            out.append('\'')
            loop(i + 2)
          else Right((out.toString, value.substring(i + 1)))
        else if quote == '"' && c == '"' then
          // an escape can spell what no file backend accepts: a NUL, or half of a surrogate pair
          val text = out.toString
          if Text.binaryReason(text).isDefined then Left((n, "an escape makes a NUL or an unpaired surrogate"))
          else Right((text, value.substring(i + 1)))
        else if quote == '"' && c == '\\' then
          if i + 1 >= value.length then Left((n, "a quoted string is not closed on its line"))
          else
            value.charAt(i + 1) match
              case '\\' => out.append('\\'); loop(i + 2)
              case '"'  => out.append('"'); loop(i + 2)
              case '/'  => out.append('/'); loop(i + 2)
              case 'n'  => out.append('\n'); loop(i + 2)
              case 't'  => out.append('\t'); loop(i + 2)
              case 'r'  => out.append('\r'); loop(i + 2)
              case '0'  => out.append('\u0000'); loop(i + 2)
              case 'u' if i + 6 <= value.length && value.substring(i + 2, i + 6).forall(Character.digit(_, 16) >= 0) =>
                out.append(Integer.parseInt(value.substring(i + 2, i + 6), 16).toChar)
                loop(i + 6)
              case _ => Left((n, "an unsupported escape in a quoted string"))
        else
          out.append(c)
          loop(i + 1)
    loop(1)

  /** `[a, "b", c]`: scalars separated by commas, on one line; nothing nested. */
  private def flowList(n: Int, value: String): Parsed[Vector[String]] =
    @annotation.tailrec
    def loop(rest: String, acc: Vector[String]): Parsed[Vector[String]] =
      val r = rest.dropWhile(_ == ' ')
      if r.isEmpty then Left((n, "a flow list is not closed on its line"))
      else if r.head == ']' then
        if acc.isEmpty then trailing(n, r.tail).map(_ => acc) else Left((n, "a flow list has an empty item"))
      else
        val item: Parsed[(String, String)] =
          r.head match
            case q @ ('"' | '\'') => quoted(n, r, q)
            case '[' | '{'        => Left((n, "nested lists and maps are not supported"))
            case c if Unsupported.contains(c) && c != '>' && c != '|' => Left((n, Unsupported(c)))
            case _ =>
              val end = r.indexWhere(c => c == ',' || c == ']')
              if end < 0 then Left((n, "a flow list is not closed on its line"))
              else
                val plain = r.substring(0, end).trim
                if plain.isEmpty then Left((n, "a flow list has an empty item"))
                // a ` #` starts a comment, which would swallow the rest of the line and the list's `]` with it
                else if CommentStart.findFirstIn(plain).isDefined then
                  Left((n, "a flow list cannot hold a comment; it must close on its line"))
                else if plain.exists(c => c == '[' || c == '{' || c == '}') then
                  Left((n, "nested lists and maps are not supported"))
                else plainProblem(plain, inList = true).fold(Right((plain, r.substring(end))))(p => Left((n, p)))
        item match
          case Left(e) => Left(e)
          case Right((v, after)) =>
            val a = after.dropWhile(_ == ' ')
            if a.startsWith(",") then loop(a.tail, acc :+ v)
            else if a.startsWith("]") then trailing(n, a.tail).map(_ => acc :+ v)
            else Left((n, "expected `,` or `]` in a flow list"))
    loop(value.tail, Vector.empty)
