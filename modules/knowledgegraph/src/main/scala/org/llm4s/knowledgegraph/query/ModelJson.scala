package org.llm4s.knowledgegraph.query

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.llm4s.util.BoundedJson

/**
 * Reading JSON out of a model's reply, which may wrap it in a code fence or in prose (#2973). Every accessor is a
 * `Result`, so a reply of the wrong shape is a `Left`, never an exception.
 */
private[knowledgegraph] object ModelJson {

  /**
   * The first JSON value of the expected kind (`{` for an object, `[` for an array) in the reply: the whole reply when
   * it parses, else the first parsable span from an opening bracket to a matching close. Nested more than 512 levels
   * deep it is refused, since a value that deep overflows the stack of whatever renders it (#1562).
   */
  def first(reply: String, open: Char, context: String): Result[ujson.Value] = {
    val close                           = if (open == '{') '}' else ']'
    val trimmed                         = reply.trim
    def ofKind(v: ujson.Value): Boolean = if (open == '{') v.objOpt.isDefined else v.arrOpt.isDefined
    val read                            = BoundedJson.read(trimmed)
    val whole                           = read.toOption.filter(ofKind)
    whole
      .orElse {
        val starts = trimmed.indices.iterator.filter(i => trimmed.charAt(i) == open).take(20)
        starts
          .flatMap { start =>
            val end = trimmed.lastIndexOf(close)
            // From the widest span inwards: the first close that gives a value of the right kind
            Iterator
              .iterate(end)(e => trimmed.lastIndexOf(close, e - 1))
              .takeWhile(e => e > start)
              .flatMap(e => BoundedJson.read(trimmed.substring(start, e + 1)).toOption.filter(ofKind))
          }
          .nextOption()
      }
      .toRight {
        val why = read.left.toOption.map(e => s" (${e.message})").getOrElse("")
        ProcessingError("query_parse", s"$context: no JSON ${if (open == '{') "object" else "array"} in the reply$why")
      }
  }

  def obj(reply: String, context: String): Result[ujson.Obj] =
    first(reply, '{', context).map(v => ujson.Obj.from(v.obj))

  /** A required string field; a number is accepted as its text, since node ids are often numeric. */
  def string(json: ujson.Value, key: String, context: String): Result[String] =
    optString(json, key, context).flatMap(_.toRight(ProcessingError("query_parse", s"$context requires '$key'")))

  def optString(json: ujson.Value, key: String, context: String): Result[Option[String]] =
    json.objOpt.flatMap(_.get(key)) match {
      case None | Some(ujson.Null)          => Right(None)
      case Some(ujson.Str(s)) if s.nonEmpty => Right(Some(s))
      case Some(ujson.Str(_))               => Right(None)
      case Some(n: ujson.Num) =>
        Right(Some(if (n.value.isWhole) n.value.toLong.toString else n.value.toString))
      case Some(other) => Left(ProcessingError("query_parse", s"$context: '$key' must be a string, got $other"))
    }

  /** An optional whole-number field; a numeric string such as `"2"` is accepted. */
  def optInt(json: ujson.Value, key: String, context: String): Result[Option[Int]] =
    json.objOpt.flatMap(_.get(key)) match {
      case None | Some(ujson.Null)                                  => Right(None)
      case Some(ujson.Num(n)) if n.isWhole && n.abs <= Int.MaxValue => Right(Some(n.toInt))
      case Some(ujson.Str(s)) if s.trim.toIntOption.isDefined       => Right(s.trim.toIntOption)
      case Some(other) => Left(ProcessingError("query_parse", s"$context: '$key' must be a whole number, got $other"))
    }

  def optBool(json: ujson.Value, key: String, context: String): Result[Option[Boolean]] =
    json.objOpt.flatMap(_.get(key)) match {
      case None | Some(ujson.Null) => Right(None)
      case Some(ujson.Bool(b))     => Right(Some(b))
      case Some(ujson.Str(s)) if s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false") =>
        Right(Some(s.equalsIgnoreCase("true")))
      case Some(other) => Left(ProcessingError("query_parse", s"$context: '$key' must be a boolean, got $other"))
    }
}
