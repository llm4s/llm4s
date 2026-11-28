package org.llm4s.agent.graph.tool

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, ValidationError }
import org.llm4s.security.RegexSafetyManager

/**
 * Checks raw tool-call arguments against the JSON Schema a tool sent to the provider.
 *
 * The default implementation supports exactly the subset core's `SchemaDefinition` emits:
 * `type` (a string or an array of strings), `description` (ignored), `properties`, `required`,
 * `additionalProperties` (boolean), `enum`, `minLength`, `maxLength`, `pattern`, `minimum`, `maximum`,
 * `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`, `items`, `minItems`, `maxItems`,
 * `uniqueItems` and `anyOf` (a value must satisfy at least one alternative). Annotation and metadata keywords, which
 * never constrain a value, are accepted and ignored wherever they appear (#3300): `$schema`, `$id`, `$comment`,
 * `title`, `examples`, `default`, `deprecated`, `readOnly` and `writeOnly`. A property that is not
 * required may be `null`, which is how a client that sends tools strict has the model leave it out.
 * Any other keyword, which only a `RawJsonSchema` writes, and a supported one with a malformed value (a bound that is not
 * a number, a length or count that is not a non-negative whole number, a non-boolean `uniqueItems`,
 * a `required` that is not an array of strings, an `enum` that is not an array, `properties` that
 * is not an object, a `pattern` that cannot be compiled), is reported by [[unsupported]] so a tool set can refuse
 * it when it is built, rather than silently skip the constraint on every call.
 *
 * `pattern` (#2954) is matched as JSON Schema says, anywhere in the string unless the expression is anchored, but
 * with `java.util.regex` rather than ECMA-262, through [[org.llm4s.security.RegexSafetyManager]]: a pattern that
 * is not valid there, is empty, is longer than 1000 characters or has a shape known to backtrack catastrophically
 * is reported by [[unsupported]], with the reason after its path. A match is bounded: one that exceeds the
 * manager's step budget, or a string of more than 100000 characters, is a violation saying the value could not be
 * checked, never a hang and never a pass.
 */
@Experimental
trait ToolArgumentValidator {

  /**
   * Keywords this validator cannot check, unknown or with a malformed value, as JSON paths into
   * `schema` (empty when fully supported).
   */
  def unsupported(schema: ujson.Value): Vector[String]

  /**
   * Every violation of `schema` by `arguments`, as messages prefixed with a JSON path
   * (`$.limit: 500 is above maximum 100`). Violations are ordered by schema property order, then
   * array index. Empty when the arguments are valid.
   */
  def validate(schema: ujson.Value, arguments: ujson.Value): Vector[String]
}

@Experimental
object ToolArgumentValidator {

  /** The in-house validator for the JSON Schema subset core emits. */
  val default: ToolArgumentValidator = SubsetValidator

  private val Supported: Set[String] = Set(
    "type",
    "description",
    "properties",
    "required",
    "additionalProperties",
    "enum",
    "minLength",
    "maxLength",
    "pattern",
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "multipleOf",
    "items",
    "minItems",
    "maxItems",
    "uniqueItems",
    "anyOf"
  )

  /**
   * Keywords that only annotate (#3300): a schema an MCP server or a generator writes carries them - the MCP reference
   * server puts `$schema` on every tool - and no value is ever refused for them, so they are never unsupported.
   */
  private val Annotations: Set[String] =
    Set("$schema", "$id", "$comment", "title", "examples", "default", "deprecated", "readOnly", "writeOnly")

  /** How much of a string a pattern violation quotes. */
  private val MaxQuotedCodePoints = 80

  private val JsonTypes: Set[String] =
    Set("string", "number", "integer", "boolean", "array", "object", "null")

  private object SubsetValidator extends ToolArgumentValidator {

    override def unsupported(schema: ujson.Value): Vector[String] = unsupportedAt(schema, "$")

    override def validate(schema: ujson.Value, arguments: ujson.Value): Vector[String] =
      validateAt(schema, arguments, "$")

    // ---- unsupported keywords ----

    private def unsupportedAt(schema: ujson.Value, path: String): Vector[String] =
      schema match {
        case ujson.Obj(fields) =>
          satisfiability(fields, path) ++ fields.toVector.flatMap { case (key, value) =>
            if (Annotations.contains(key)) Vector.empty
            else if (!Supported.contains(key)) Vector(s"$path.$key")
            else
              key match {
                case "type" =>
                  if (validType(value)) Vector.empty else Vector(s"$path.type")
                case "properties" =>
                  value match {
                    case ujson.Obj(props) =>
                      props.toVector.flatMap { case (name, sub) => unsupportedAt(sub, s"$path.properties.$name") }
                    case _ => Vector(s"$path.properties")
                  }
                case "items" =>
                  value match {
                    case _: ujson.Obj => unsupportedAt(value, s"$path.items")
                    case _            => Vector(s"$path.items")
                  }
                case "additionalProperties" | "uniqueItems" =>
                  value match {
                    case _: ujson.Bool => Vector.empty
                    case _             => Vector(s"$path.$key")
                  }
                case "multipleOf" =>
                  value match {
                    case ujson.Num(n) if n > 0 && !n.isInfinity => Vector.empty
                    case _                                      => Vector(s"$path.$key")
                  }
                case "minimum" | "maximum" | "exclusiveMinimum" | "exclusiveMaximum" =>
                  value match {
                    case ujson.Num(n) if !n.isNaN && !n.isInfinity => Vector.empty
                    case _                                         => Vector(s"$path.$key")
                  }
                case "minLength" | "maxLength" | "minItems" | "maxItems" =>
                  value match {
                    case ujson.Num(n) if n >= 0 && isWhole(n) => Vector.empty
                    case _                                    => Vector(s"$path.$key")
                  }
                case "pattern" =>
                  value match {
                    case ujson.Str(regex) =>
                      RegexSafetyManager
                        .safeCompile(regex)
                        .fold(e => Vector(s"$path.pattern (${reason(e)})"), _ => Vector.empty)
                    case _ => Vector(s"$path.pattern")
                  }
                case "required" =>
                  value match {
                    case ujson.Arr(names) if names.forall(_.strOpt.isDefined) && names.distinct.size == names.size =>
                      Vector.empty
                    case _ => Vector(s"$path.required")
                  }
                case "enum" =>
                  value match {
                    case ujson.Arr(entries) if entries.nonEmpty && entries.distinct.size == entries.size =>
                      Vector.empty
                    case _ => Vector(s"$path.enum")
                  }
                case "anyOf" =>
                  value match {
                    case ujson.Arr(alternatives) if alternatives.nonEmpty =>
                      alternatives.toVector.zipWithIndex.flatMap { case (alternative, i) =>
                        unsupportedAt(alternative, s"$path.anyOf[$i]")
                      }
                    case _ => Vector(s"$path.anyOf")
                  }
                case _ => Vector.empty
              }
          }
        case _ => Vector(path)
      }

    /** Well-formed keywords that together admit no value: a tool with such an argument can never be called. */
    private def satisfiability(fields: scala.collection.Map[String, ujson.Value], path: String): Vector[String] = {
      def num(key: String): Option[Double] = fields.get(key).collect { case ujson.Num(n) if !n.isNaN => n }
      def order(lo: String, hi: String): Vector[String] =
        (num(lo), num(hi)) match {
          case (Some(l), Some(h)) if l > h => Vector(s"$path: $lo ${this.num(l)} is above $hi ${this.num(h)}")
          case _                           => Vector.empty
        }
      val range = {
        val lows  = num("minimum").map(_ -> false).toVector ++ num("exclusiveMinimum").map(_ -> true)
        val highs = num("maximum").map(_ -> false).toVector ++ num("exclusiveMaximum").map(_ -> true)
        val empty = lows.exists { case (l, lx) =>
          highs.exists { case (h, hx) => l > h || (l == h && (lx || hx)) }
        }
        if (empty) Vector(s"$path: the bounds leave no number") else Vector.empty
      }
      val enumVsType = (fields.get("enum"), fields.get("type")) match {
        case (Some(ujson.Arr(entries)), Some(t)) if entries.nonEmpty && validType(t) =>
          val names = t match {
            case ujson.Str(s) => Seq(s)
            case ujson.Arr(a) => a.toSeq.collect { case ujson.Str(s) => s }
            case _            => Seq.empty
          }
          if (entries.exists(e => names.exists(matchesType(_, e)))) Vector.empty
          else Vector(s"$path: no enum entry matches the type")
        case _ => Vector.empty
      }
      val undeclared =
        if (fields.get("additionalProperties").contains(ujson.Bool(false))) {
          val declared =
            fields.get("properties").collect { case ujson.Obj(p) => p.keySet.toSet }.getOrElse(Set.empty[String])
          fields
            .get("required")
            .collect { case ujson.Arr(r) => r.toVector.flatMap(_.strOpt) }
            .getOrElse(Vector.empty)
            .filterNot(declared.contains)
            .map(name => s"$path: required property '$name' is not in properties")
        } else Vector.empty
      order("minLength", "maxLength") ++ order("minItems", "maxItems") ++ range ++ enumVsType ++ undeclared
    }

    private def validType(t: ujson.Value): Boolean =
      t match {
        case ujson.Str(s) => JsonTypes.contains(s)
        case ujson.Arr(a) =>
          a.nonEmpty && a.distinct.size == a.size && a.forall {
            case ujson.Str(s) => JsonTypes.contains(s)
            case _            => false
          }
        case _ => false
      }

    // ---- validation ----

    private def validateAt(schema: ujson.Value, value: ujson.Value, path: String): Vector[String] =
      schema match {
        case ujson.Obj(fields) =>
          val types = fields.get("type").map(typeNames).getOrElse(Vector.empty)
          if (types.nonEmpty && !types.exists(matchesType(_, value)))
            Vector(s"$path: expected ${types.mkString(" or ")}, got ${actualType(value)}")
          else
            anyOfViolations(fields, value, path) ++ enumViolations(fields, value, path) ++ (value match {
              case ujson.Str(s) => stringViolations(fields, s, path)
              case ujson.Num(n) => numberViolations(fields, n, path)
              case ujson.Obj(o) => objectViolations(fields, o, path)
              case ujson.Arr(a) => arrayViolations(fields, a.toVector, path)
              case _            => Vector.empty
            })
        case _ => Vector.empty
      }

    /** No violation when at least one alternative accepts the value; otherwise the closest alternative's. */
    private def anyOfViolations(
      fields: scala.collection.Map[String, ujson.Value],
      value: ujson.Value,
      path: String
    ): Vector[String] =
      fields.get("anyOf") match {
        case Some(ujson.Arr(alternatives)) if alternatives.nonEmpty =>
          val attempts = alternatives.toVector.map(validateAt(_, value, path))
          if (attempts.exists(_.isEmpty)) Vector.empty
          else
            Vector(
              s"$path: ${ujson.write(value)} matches none of the ${attempts.size} alternatives (closest: " +
                attempts.minBy(_.size).mkString("; ") + ")"
            )
        case _ => Vector.empty
      }

    private def typeNames(t: ujson.Value): Vector[String] =
      t match {
        case ujson.Str(s) => Vector(s)
        case ujson.Arr(a) => a.toVector.collect { case ujson.Str(s) => s }
        case _            => Vector.empty
      }

    private def matchesType(name: String, value: ujson.Value): Boolean =
      (name, value) match {
        case ("string", _: ujson.Str)   => true
        case ("number", _: ujson.Num)   => true
        case ("integer", ujson.Num(n))  => isWhole(n)
        case ("boolean", _: ujson.Bool) => true
        case ("array", _: ujson.Arr)    => true
        case ("object", _: ujson.Obj)   => true
        case ("null", ujson.Null)       => true
        case _                          => false
      }

    private def actualType(value: ujson.Value): String =
      value match {
        case _: ujson.Str  => "string"
        case ujson.Num(n)  => if (isWhole(n)) "integer" else "number"
        case _: ujson.Bool => "boolean"
        case _: ujson.Arr  => "array"
        case _: ujson.Obj  => "object"
        case ujson.Null    => "null"
      }

    private def isWhole(n: Double): Boolean = !n.isNaN && !n.isInfinity && n == Math.rint(n)

    private def num(n: Double): String =
      if (isWhole(n)) BigDecimal(n).toBigInt.toString else n.toString

    private def enumViolations(
      fields: scala.collection.Map[String, ujson.Value],
      value: ujson.Value,
      path: String
    ): Vector[String] =
      fields.get("enum") match {
        case Some(ujson.Arr(allowed)) if !allowed.contains(value) =>
          Vector(s"$path: ${ujson.write(value)} is not one of ${allowed.map(ujson.write(_)).mkString("[", ",", "]")}")
        case _ => Vector.empty
      }

    private def intKeyword(fields: scala.collection.Map[String, ujson.Value], key: String): Option[Double] =
      fields.get(key).collect { case ujson.Num(n) => n }

    private def stringViolations(
      fields: scala.collection.Map[String, ujson.Value],
      s: String,
      path: String
    ): Vector[String] = {
      val length = s.codePointCount(0, s.length)
      intKeyword(fields, "minLength")
        .filter(length < _)
        .map(m => s"$path: length $length is below minLength ${num(m)}")
        .toVector ++
        intKeyword(fields, "maxLength")
          .filter(length > _)
          .map(m => s"$path: length $length is above maxLength ${num(m)}")
          .toVector ++
        fields.get("pattern").collect { case ujson.Str(regex) => regex }.toVector.flatMap { regex =>
          // JSON Schema's match: found anywhere in the string. Compiled on each check; a pattern is short.
          RegexSafetyManager.safeCompile(regex).flatMap(RegexSafetyManager.safeFind(_, s)) match {
            case Right(true)  => Vector.empty
            case Right(false) => Vector(s"$path: ${quoted(s, length)} does not match the pattern $regex")
            case Left(e)      => Vector(s"$path: could not be checked against the pattern $regex (${reason(e)})")
          }
        }
    }

    /** The reason of a `RegexSafetyManager` refusal, without the error's own framing. */
    private def reason(error: LLMError): String = error match {
      case v: ValidationError if v.violations.nonEmpty => v.violations.mkString("; ")
      case other                                       => other.message
    }

    /** `s` as JSON, cut to `MaxQuotedCodePoints` so the pattern after it survives a caller's truncation. */
    private def quoted(s: String, codePoints: Int): String =
      if (codePoints <= MaxQuotedCodePoints) ujson.write(ujson.Str(s))
      else ujson.write(ujson.Str(s.substring(0, s.offsetByCodePoints(0, MaxQuotedCodePoints)))) + "..."

    private def numberViolations(
      fields: scala.collection.Map[String, ujson.Value],
      n: Double,
      path: String
    ): Vector[String] =
      intKeyword(fields, "minimum").filter(n < _).map(m => s"$path: ${num(n)} is below minimum ${num(m)}").toVector ++
        intKeyword(fields, "maximum").filter(n > _).map(m => s"$path: ${num(n)} is above maximum ${num(m)}").toVector ++
        intKeyword(fields, "exclusiveMinimum")
          .filter(n <= _)
          .map(m => s"$path: ${num(n)} is not above exclusiveMinimum ${num(m)}")
          .toVector ++
        intKeyword(fields, "exclusiveMaximum")
          .filter(n >= _)
          .map(m => s"$path: ${num(n)} is not below exclusiveMaximum ${num(m)}")
          .toVector ++
        intKeyword(fields, "multipleOf")
          .filter(m => m > 0 && !m.isInfinity && !isMultiple(n, m))
          .map(m => s"$path: ${num(n)} is not a multiple of ${num(m)}")
          .toVector

    // Exact in decimal: the shortest decimal text of each double, so 0.3 is a multiple of 0.1.
    private def isMultiple(n: Double, m: Double): Boolean =
      BigDecimal(n.toString).remainder(BigDecimal(m.toString)).signum == 0

    private def objectViolations(
      fields: scala.collection.Map[String, ujson.Value],
      obj: scala.collection.Map[String, ujson.Value],
      path: String
    ): Vector[String] = {
      val props    = fields.get("properties").collect { case ujson.Obj(p) => p.toVector }.getOrElse(Vector.empty)
      val declared = props.map(_._1).toSet
      val missing = fields
        .get("required")
        .collect { case ujson.Arr(r) => r.toVector.collect { case ujson.Str(name) => name } }
        .getOrElse(Vector.empty)
        .filterNot(obj.contains)
        .map(name => s"$path.$name: required property missing")
      val extra =
        if (fields.get("additionalProperties").contains(ujson.Bool(false)))
          obj.keys.toVector.filterNot(declared.contains).map(k => s"$path.$k: property not allowed")
        else Vector.empty
      val required = fields
        .get("required")
        .collect { case ujson.Arr(r) => r.toVector.collect { case ujson.Str(name) => name } }
        .getOrElse(Vector.empty)
        .toSet
      val nested = props.flatMap { case (name, sub) =>
        obj.get(name).toVector.flatMap {
          // null for a property that is not required is the property left out: a strict client lists every
          // property as required and nullable, so that is how its model omits one
          case ujson.Null if !required(name) => Vector.empty
          case present                       => validateAt(sub, present, s"$path.$name")
        }
      }
      missing ++ extra ++ nested
    }

    private def arrayViolations(
      fields: scala.collection.Map[String, ujson.Value],
      items: Vector[ujson.Value],
      path: String
    ): Vector[String] = {
      val count = items.size
      val sizes =
        intKeyword(fields, "minItems")
          .filter(count < _)
          .map(m => s"$path: $count items is below minItems ${num(m)}")
          .toVector ++
          intKeyword(fields, "maxItems")
            .filter(count > _)
            .map(m => s"$path: $count items is above maxItems ${num(m)}")
            .toVector
      val unique =
        if (fields.get("uniqueItems").contains(ujson.Bool(true)) && items.distinct.size != count)
          Vector(s"$path: items are not unique")
        else Vector.empty
      val elements = fields.get("items") match {
        case Some(sub) => items.zipWithIndex.flatMap { case (item, i) => validateAt(sub, item, s"$path[$i]") }
        case None      => Vector.empty
      }
      sizes ++ unique ++ elements
    }
  }
}
