package org.llm4s.schema

import org.llm4s.annotation.Experimental

/**
 * A tolerant parser for the JSON a model has written so far: given a prefix of a JSON document, it closes the open
 * strings, arrays and objects and returns the value the prefix describes.
 *
 * What the prefix does not finish is left out rather than guessed:
 *  - a property whose value has not started, or whose key is still being written, is absent;
 *  - a number is surfaced only once a character after it shows that it is complete, so `12.5` never shows as `1`;
 *  - `true`, `false` and `null` are surfaced only once complete;
 *  - a string still being written is surfaced as its prefix (without an escape that has not finished), or left out
 *    with `partialStrings = false`.
 *
 * So the value for a prefix is always a prefix of the value for the whole document: every property it has, the whole
 * document has with the same or a longer value. Text after the first value, or after JSON that is not valid, is
 * ignored: the value is what came before it. Nesting deeper than 512 levels is cut there, as `BoundedJson` refuses it.
 * The parser never throws.
 *
 * `client.streamStructuredOf[A]` uses it to turn a streamed reply into typed partial values.
 */
@Experimental
object PartialJson {

  /**
   * The value described by `text`, a prefix of a JSON document, or `None` when no value has started yet (empty or
   * blank text, or a number or literal still being written at the root).
   *
   * @param text           the JSON written so far
   * @param partialStrings whether a string still being written is surfaced as its prefix (the default) or left out
   */
  def parse(text: String, partialStrings: Boolean = true): Option[ujson.Value] =
    parseTree(text).flatMap(toValue(_, partialStrings))

  /** The parsed prefix, keeping which strings, arrays and objects are complete. */
  private[schema] def parseTree(text: String): Option[Node] = new Parser(text).root()

  /** The most deeply nested value the parser reads; deeper values are cut, as `BoundedJson` refuses them. */
  private[schema] val MaxDepth = 512

  /** A JSON value read from a prefix: strings, arrays and objects say whether the prefix closed them. */
  private[schema] enum Node {
    case Obj(fields: Vector[(String, Node)], complete: Boolean)
    case Arr(items: Vector[Node], complete: Boolean)
    case Str(value: String, complete: Boolean)
    case Num(raw: String)
    case Bool(value: Boolean)
    case Null

    /** Whether the whole value has arrived: a container is complete only once closed, after all it holds. */
    def isComplete: Boolean = this match {
      case Obj(_, complete) => complete
      case Arr(_, complete) => complete
      case Str(_, complete) => complete
      case _                => true
    }

    /** The value of the property `key`; the last one when the object repeats it, as `ujson` reads it. */
    def field(key: String): Option[Node] = this match {
      case Obj(fields, _) => fields.reverseIterator.collectFirst { case (`key`, value) => value }
      case _              => None
    }

    /** The JSON text of a complete value, numbers as written, so that a `Long` is read from it exactly. */
    def render: String = {
      val out = new StringBuilder
      def loop(node: Node): Unit = node match {
        case Obj(fields, _) =>
          out.append('{')
          fields.zipWithIndex.foreach { case ((key, value), i) =>
            if (i > 0) out.append(',')
            out.append(ujson.write(ujson.Str(key))).append(':')
            loop(value)
          }
          out.append('}')
        case Arr(items, _) =>
          out.append('[')
          items.zipWithIndex.foreach { case (item, i) =>
            if (i > 0) out.append(',')
            loop(item)
          }
          out.append(']')
        case Str(value, _) => out.append(ujson.write(ujson.Str(value)))
        case Num(raw)      => out.append(raw)
        case Bool(value)   => out.append(value)
        case Null          => out.append("null")
      }
      loop(this)
      out.toString
    }
  }

  private def toValue(node: Node, partialStrings: Boolean): Option[ujson.Value] = node match {
    case Node.Obj(fields, _) =>
      val obj = ujson.Obj()
      fields.foreach { case (key, value) => toValue(value, partialStrings).foreach(obj(key) = _) }
      Some(obj)
    case Node.Arr(items, _) =>
      Some(ujson.Arr.from(items.iterator.map(toValue(_, partialStrings)).takeWhile(_.isDefined).flatten))
    case Node.Str(value, complete) => Option.when(complete || partialStrings)(ujson.Str(value))
    case Node.Num(raw)             => Some(ujson.Num(raw.toDouble))
    case Node.Bool(value)          => Some(ujson.Bool(value))
    case Node.Null                 => Some(ujson.Null)
  }

  private val NumberPattern = "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?".r

  /**
   * A recursive-descent parser that stops at the end of the text or at the first character that is not valid JSON.
   * `stopped` is set then, and every enclosing container returns what it has read, marked incomplete.
   */
  final private class Parser(text: String) {
    private var pos     = 0
    private var stopped = false

    def root(): Option[Node] = value(0)

    private def skipSpace(): Unit =
      while (pos < text.length && " \t\r\n".indexOf(text.charAt(pos).toInt) >= 0) pos += 1

    private def stop(): Option[Node] = { stopped = true; None }

    /** The value at `pos`: `None` when nothing usable has arrived, with `stopped` set. */
    private def value(depth: Int): Option[Node] = {
      skipSpace()
      if (pos >= text.length || depth > MaxDepth) stop()
      else
        text.charAt(pos) match {
          case '{'                                     => pos += 1; Some(obj(depth))
          case '['                                     => pos += 1; Some(arr(depth))
          case '"'                                     => pos += 1; Some(str())
          case 't'                                     => literal("true", Node.Bool(true))
          case 'f'                                     => literal("false", Node.Bool(false))
          case 'n'                                     => literal("null", Node.Null)
          case c if c == '-' || (c >= '0' && c <= '9') => number()
          case _                                       => stop()
        }
    }

    private def literal(word: String, node: Node): Option[Node] =
      if (text.startsWith(word, pos)) { pos += word.length; Some(node) }
      else stop()

    /** A number counts only once a character after it ends it: at the end of the text it may still grow. */
    private def number(): Option[Node] = {
      val start = pos
      while (pos < text.length && "0123456789+-.eE".indexOf(text.charAt(pos).toInt) >= 0) pos += 1
      val raw = text.substring(start, pos)
      if (pos >= text.length || !NumberPattern.matches(raw)) stop() else Some(Node.Num(raw))
    }

    private def obj(depth: Int): Node = {
      val fields                  = Vector.newBuilder[(String, Node)]
      def done(complete: Boolean) = Node.Obj(fields.result(), complete)
      var result: Option[Node]    = None
      var first                   = true
      while (result.isEmpty) {
        skipSpace()
        if (pos >= text.length) { stopped = true; result = Some(done(complete = false)) }
        else if (text.charAt(pos) == '}' && first) { pos += 1; result = Some(done(complete = true)) }
        else if (text.charAt(pos) != '"') { stopped = true; result = Some(done(complete = false)) }
        else {
          pos += 1
          val key = str()
          skipSpace()
          if (!key.isComplete || pos >= text.length || text.charAt(pos) != ':') {
            stopped = true; result = Some(done(complete = false))
          } else {
            pos += 1
            val member = value(depth + 1)
            member.foreach(node => fields += (key.value -> node))
            if (stopped) result = Some(done(complete = false))
            else {
              skipSpace()
              if (pos < text.length && text.charAt(pos) == ',') { pos += 1; first = false }
              else if (pos < text.length && text.charAt(pos) == '}') { pos += 1; result = Some(done(complete = true)) }
              else { stopped = true; result = Some(done(complete = false)) }
            }
          }
        }
      }
      result.getOrElse(done(complete = false))
    }

    private def arr(depth: Int): Node = {
      val items                   = Vector.newBuilder[Node]
      def done(complete: Boolean) = Node.Arr(items.result(), complete)
      var result: Option[Node]    = None
      var first                   = true
      while (result.isEmpty) {
        skipSpace()
        if (pos >= text.length) { stopped = true; result = Some(done(complete = false)) }
        else if (text.charAt(pos) == ']' && first) { pos += 1; result = Some(done(complete = true)) }
        else {
          value(depth + 1).foreach(items += _)
          if (stopped) result = Some(done(complete = false))
          else {
            skipSpace()
            if (pos < text.length && text.charAt(pos) == ',') { pos += 1; first = false }
            else if (pos < text.length && text.charAt(pos) == ']') { pos += 1; result = Some(done(complete = true)) }
            else { stopped = true; result = Some(done(complete = false)) }
          }
        }
      }
      result.getOrElse(done(complete = false))
    }

    /** A string after its opening quote; at the end of the text, its prefix without an unfinished escape. */
    private def str(): Node.Str = {
      val out                      = new StringBuilder
      var result: Option[Node.Str] = None
      def partial(): Node.Str = {
        stopped = true
        // a high surrogate whose low half has not arrived is not shown on its own
        if (out.nonEmpty && Character.isHighSurrogate(out.last)) out.setLength(out.length - 1)
        Node.Str(out.toString, complete = false)
      }
      while (result.isEmpty)
        if (pos >= text.length) result = Some(partial())
        else {
          val c = text.charAt(pos)
          if (c == '"') { pos += 1; result = Some(Node.Str(out.toString, complete = true)) }
          else if (c != '\\') { out.append(c); pos += 1 }
          else if (pos + 1 >= text.length) result = Some(partial())
          else {
            text.charAt(pos + 1) match {
              case '"'  => out.append('"'); pos += 2
              case '\\' => out.append('\\'); pos += 2
              case '/'  => out.append('/'); pos += 2
              case 'b'  => out.append('\b'); pos += 2
              case 'f'  => out.append('\f'); pos += 2
              case 'n'  => out.append('\n'); pos += 2
              case 'r'  => out.append('\r'); pos += 2
              case 't'  => out.append('\t'); pos += 2
              case 'u' =>
                val hex = text.slice(pos + 2, pos + 6)
                // an escape still arriving, or one that is not valid, ends the string here
                if (hex.length == 4 && hex.forall(isHex)) {
                  out.append(Integer.parseInt(hex, 16).toChar); pos += 6
                } else result = Some(partial())
              case _ => result = Some(partial())
            }
          }
        }
      result.getOrElse(partial())
    }

    private def isHex(c: Char): Boolean = Character.digit(c, 16) >= 0
  }
}
