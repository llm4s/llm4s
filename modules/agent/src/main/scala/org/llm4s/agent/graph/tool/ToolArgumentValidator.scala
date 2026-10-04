package org.llm4s.agent.graph.tool

/**
 * Checks raw tool-call arguments against the JSON Schema a tool sent to the provider.
 *
 * The default implementation supports exactly the subset core's `SchemaDefinition` emits:
 * `type` (a string or an array of strings), `description` (ignored), `properties`, `required`,
 * `additionalProperties` (boolean), `enum`, `minLength`, `maxLength`, `minimum`, `maximum`,
 * `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`, `items`, `minItems`, `maxItems` and
 * `uniqueItems`. Any other keyword, and a supported one with a malformed value (a bound that is not
 * a number, a length or count that is not a non-negative whole number, a non-boolean `uniqueItems`,
 * a `required` that is not an array of strings, an `enum` that is not an array, `properties` that
 * is not an object), is reported by [[unsupported]] so a tool set can refuse it when it is built,
 * rather than silently skip the constraint on every call.
 */
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
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "multipleOf",
    "items",
    "minItems",
    "maxItems",
    "uniqueItems"
  )

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
          fields.toVector.flatMap { case (key, value) =>
            if (!Supported.contains(key)) Vector(s"$path.$key")
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
                case "required" =>
                  value match {
                    case ujson.Arr(names) if names.forall(_.strOpt.isDefined) => Vector.empty
                    case _                                                    => Vector(s"$path.required")
                  }
                case "enum" =>
                  value match {
                    case _: ujson.Arr => Vector.empty
                    case _            => Vector(s"$path.enum")
                  }
                case _ => Vector.empty
              }
          }
        case _ => Vector(path)
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
          else if (value == ujson.Null && types.contains("null")) Vector.empty
          else
            enumViolations(fields, value, path) ++ (value match {
              case ujson.Str(s) => stringViolations(fields, s, path)
              case ujson.Num(n) => numberViolations(fields, n, path)
              case ujson.Obj(o) => objectViolations(fields, o, path)
              case ujson.Arr(a) => arrayViolations(fields, a.toVector, path)
              case _            => Vector.empty
            })
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
          .toVector
    }

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
      val nested = props.flatMap { case (name, sub) =>
        obj.get(name).toVector.flatMap(validateAt(sub, _, s"$path.$name"))
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
