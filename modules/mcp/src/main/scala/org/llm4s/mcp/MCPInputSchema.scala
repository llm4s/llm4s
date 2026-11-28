package org.llm4s.mcp

import org.llm4s.toolapi._
import ujson.Value

/**
 * An MCP tool's `inputSchema` as the schema model (#3076), keeping what the server declared:
 *
 *  - `additionalProperties` as the server wrote it. Where it is absent, an object with declared properties stays
 *    closed (`false`), which is what providers' strict modes require, and a free-form object (`{"type":"object"}`
 *    with no properties) is open, so a tool that takes arbitrary arguments can be given some.
 *  - A `$ref` into the schema's own `$defs` or `definitions` is replaced by the definition it names, so the schema
 *    sent to the provider has no dangling reference. A reference that cannot be resolved, or a recursive one past
 *    the first use, becomes a schema that accepts any value, described as such.
 *  - Constraints the model carries: `minLength`, `maxLength`, `pattern`, `minimum`, `maximum`, `exclusiveMinimum`,
 *    `exclusiveMaximum` (the number form, and the older boolean form), `multipleOf`, `enum` and `const`, `minItems`,
 *    `maxItems`, `uniqueItems`. An `enum` of other values than strings is kept as JSON with the keywords an agent's
 *    argument validator checks.
 *  - `oneOf` is read as `anyOf`, and a `type` of several names as an `anyOf` of each.
 *  - A missing `description` is the property's `title`, or empty; a `default`, `format` or `examples` the model cannot
 *    carry as a keyword is added to the description, so the model still learns it.
 */
private[mcp] object MCPInputSchema {

  private type Fields = Map[String, Value]

  /** How many references one path through the schema may follow; a deeper or recursive one accepts any value. */
  private val MaxRefDepth = 16

  /** The tool's argument schema, an object whatever the server sent. */
  def toObjectSchema(toolName: String, inputSchema: Value): ObjectSchema[Value] = {
    val fields = inputSchema.objOpt.map(_.toMap).getOrElse(Map.empty[String, Value])
    objectSchema(fields, definitions(fields), Nil, Some(s"Parameters for $toolName"))
  }

  // `#/$defs/name` and `#/definitions/name`, from the top of the schema, where the specification and generators put them
  private def definitions(top: Fields): Map[String, Value] =
    Seq("$defs", "definitions").flatMap { key =>
      top.get(key).flatMap(_.objOpt).toSeq.flatMap(_.toSeq.map { case (name, schema) => s"#/$key/$name" -> schema })
    }.toMap

  /** `fields` with its `$ref` replaced by the definition, sibling keywords (a description) kept over it. */
  @scala.annotation.tailrec
  private def resolve(
    fields: Fields,
    defs: Map[String, Value],
    seen: List[String]
  ): Either[String, (Fields, List[String])] =
    fields.get("$ref").flatMap(_.strOpt) match {
      case None => Right(fields -> seen)
      case Some(ref) if seen.contains(ref) || seen.size >= MaxRefDepth =>
        Left(s"recursive reference $ref")
      case Some(ref) =>
        defs.get(ref).flatMap(_.objOpt) match {
          case Some(target) => resolve(target.toMap ++ (fields - "$ref"), defs, ref :: seen)
          case None         => Left(s"unresolved reference $ref")
        }
    }

  private def convert(json: Value, defs: Map[String, Value], seen: List[String]): SchemaDefinition[_] =
    json.objOpt match {
      case None => RawJsonSchema(ujson.Obj()) // `true`, or a malformed schema: any value
      case Some(raw) =>
        resolve(raw.toMap, defs, seen) match {
          case Left(reason) =>
            // Any value the keywords beside the reference allow: those are still checked
            val text = describe(raw.toMap)
            this.raw(raw.toMap, s"${if (text.isEmpty) "" else s"$text "}($reason: any value)")
          case Right((fields, path)) => typed(fields, defs, path)
        }
    }

  private def typed(fields: Fields, defs: Map[String, Value], seen: List[String]): SchemaDefinition[_] = {
    val description = describe(fields)
    val alternatives =
      fields.get("anyOf").orElse(fields.get("oneOf")).flatMap(_.arrOpt).map(_.toList).filter(_.nonEmpty)
    val types = fields.get("type") match {
      case Some(ujson.Str(name))  => List(name)
      case Some(ujson.Arr(names)) => names.toList.flatMap(_.strOpt)
      case _                      => Nil
    }
    val nonNull = types.filterNot(_ == "null")
    def nullable(schema: SchemaDefinition[_]): SchemaDefinition[_] =
      if (types.contains("null")) NullableSchema(schema) else schema

    (alternatives, nonNull) match {
      case (Some(alts), _) =>
        val (nulls, others) = alts.partition(_.objOpt.flatMap(_.get("type")).contains(ujson.Str("null")))
        val converted       = others.map(convert(_, defs, seen))
        val union = converted match {
          case Nil           => RawJsonSchema(ujson.Obj("type" -> "null", "description" -> description))
          case single :: Nil => single
          case first :: rest => AnyOfSchema[Value](description, first, rest: _*)
        }
        if (nulls.nonEmpty && converted.nonEmpty) NullableSchema(union) else union
      case (None, List(single))                         => nullable(typedOne(single, description, fields, defs, seen))
      case (None, Nil) if fields.contains("properties") => nullable(objectSchema(fields, defs, seen, None))
      // `{"type":"null"}`, or constraints with no `type` (`{"minLength":3}`): the checkable keywords as written, so
      // no restriction is lost; with none, any value
      case (None, Nil) => raw(fields, description)
      case (None, first :: rest) =>
        val each = (first :: rest).map(t => typedOne(t, "", fields, defs, seen))
        nullable(AnyOfSchema[Value](description, each.head, each.tail: _*))
    }
  }

  private def typedOne(
    schemaType: String,
    description: String,
    fields: Fields,
    defs: Map[String, Value],
    seen: List[String]
  ): SchemaDefinition[_] = {
    def int(name: String)    = fields.get(name).flatMap(_.numOpt).filter(n => n.isWhole && n.isValidInt).map(_.toInt)
    def double(name: String) = fields.get(name).flatMap(_.numOpt)
    // `exclusiveMinimum: true` is the older form: the bound is `minimum`, exclusive
    def exclusive(flag: String, bound: String) = fields.get(flag) match {
      case Some(ujson.Bool(true)) => double(bound)
      case Some(n: ujson.Num)     => Some(n.value)
      case _                      => None
    }
    def inclusive(flag: String, bound: String) =
      if (fields.get(flag).contains(ujson.Bool(true))) None else double(bound)
    val values = fields.get("enum").flatMap(_.arrOpt).map(_.toList).orElse(fields.get("const").map(List(_)))

    schemaType match {
      case "string" if values.forall(_.forall(_.strOpt.isDefined)) =>
        StringSchema(description)
          .withEnum(values.map(_.flatMap(_.strOpt)))
          .withMinLength(int("minLength"))
          .withMaxLength(int("maxLength"))
          .withPattern(fields.get("pattern").flatMap(_.strOpt))
      case "number" if values.isEmpty =>
        NumberSchema(description)
          .withMinimum(inclusive("exclusiveMinimum", "minimum"))
          .withMaximum(inclusive("exclusiveMaximum", "maximum"))
          .withExclusiveMinimum(exclusive("exclusiveMinimum", "minimum"))
          .withExclusiveMaximum(exclusive("exclusiveMaximum", "maximum"))
          .withMultipleOf(double("multipleOf"))
      case "integer" if values.isEmpty && integerBounds(fields) =>
        def intExclusive(flag: String, bound: String) = exclusive(flag, bound).map(_.toInt)
        def intInclusive(flag: String, bound: String) = inclusive(flag, bound).map(_.toInt)
        IntegerSchema(description)
          .withMinimum(intInclusive("exclusiveMinimum", "minimum"))
          .withMaximum(intInclusive("exclusiveMaximum", "maximum"))
          .withExclusiveMinimum(intExclusive("exclusiveMinimum", "minimum"))
          .withExclusiveMaximum(intExclusive("exclusiveMaximum", "maximum"))
          .withMultipleOf(int("multipleOf"))
      case "boolean" if values.isEmpty => BooleanSchema(description)
      case "array" =>
        val items = fields.get("items").map(convert(_, defs, seen)).getOrElse(RawJsonSchema(ujson.Obj()))
        val array = ArraySchema(description, items)
          .withMinItems(int("minItems"))
          .withMaxItems(int("maxItems"))
        if (fields.get("uniqueItems").contains(ujson.Bool(true))) array.withUniqueItems() else array
      case "object" => objectSchema(fields, defs, seen, None)
      // an enum of numbers or booleans, `null`, or a type the model has no case for: the checkable keywords as JSON
      case _ => raw(fields + ("type" -> ujson.Str(schemaType)), description)
    }
  }

  // Integer bounds the model can hold (whole numbers that fit an Int); otherwise the schema is kept as JSON
  private def integerBounds(fields: Fields): Boolean =
    Seq("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf").forall { key =>
      fields.get(key) match {
        case None | Some(_: ujson.Bool) => true
        case Some(v)                    => v.numOpt.exists(n => n.isWhole && n.isValidInt)
      }
    }

  private def objectSchema(
    fields: Fields,
    defs: Map[String, Value],
    seen: List[String],
    fallbackDescription: Option[String]
  ): ObjectSchema[Value] = {
    val required = fields.get("required").flatMap(_.arrOpt).toSeq.flatMap(_.flatMap(_.strOpt)).toSet
    val properties = fields.get("properties").flatMap(_.objOpt).toSeq.flatMap(_.toSeq).map { case (name, schema) =>
      PropertyDefinition(name, convert(schema, defs, seen), required.contains(name))
    }
    val additional = fields.get("additionalProperties") match {
      case Some(ujson.Bool(allowed)) => allowed
      case Some(_)                   => true // a schema for the extra keys: they are allowed
      case None                      => properties.isEmpty
    }
    val described = describe(fields)
    ObjectSchema[Value](
      if (described.nonEmpty) described else fallbackDescription.getOrElse(""),
      properties,
      additional
    )
  }

  /**
   * The keywords an agent's argument validator checks that carry a value, not a schema, as the server wrote them,
   * with `description`: what a schema the model has no case for keeps, so no restriction is lost.
   */
  private def raw(fields: Fields, description: String): RawJsonSchema = {
    val kept = Seq(
      "type",
      "enum",
      "minLength",
      "maxLength",
      "pattern",
      "minimum",
      "maximum",
      "exclusiveMinimum",
      "exclusiveMaximum",
      "multipleOf",
      "minItems",
      "maxItems",
      "uniqueItems"
    ).flatMap(key => fields.get(key).map(key -> _))
    val withConst = fields.get("const").filterNot(_ => fields.contains("enum")).map(c => "enum" -> ujson.Arr(c))
    RawJsonSchema(ujson.Obj.from((kept ++ withConst.toSeq) :+ ("description" -> ujson.Str(description))))
  }

  /**
   * The description the model is shown: the schema's `description`, else its `title`, followed by what the model
   * cannot be told as a keyword: the `default`, the `format`, `examples`.
   */
  private def describe(fields: Fields): String = {
    def text(key: String) = fields.get(key).flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
    val base              = text("description").orElse(text("title")).getOrElse("")
    val notes = Seq(
      fields.get("default").map(d => s"default: ${d.render()}"),
      text("format").map(f => s"format: $f"),
      fields
        .get("examples")
        .flatMap(_.arrOpt)
        .filter(_.nonEmpty)
        .map(e => s"examples: ${e.map(_.render()).mkString(", ")}")
    ).flatten
    if (notes.isEmpty) base
    else if (base.isEmpty) s"(${notes.mkString("; ")})"
    else s"$base (${notes.mkString("; ")})"
  }
}
