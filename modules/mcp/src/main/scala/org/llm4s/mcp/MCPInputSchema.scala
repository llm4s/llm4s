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
 *    `exclusiveMaximum` (the number form, and the older boolean form), `multipleOf`, a string `enum` and `const`,
 *    `minItems`, `maxItems`, `uniqueItems`.
 *  - Anything else is kept as the server wrote it: a schema is converted only when every keyword it has is one its
 *    type's typed schema carries faithfully, with a value of a shape it holds (an allowlist, [[expressible]]).
 *  - `oneOf` is read as `anyOf`, and a `type` of several names as an `anyOf` of each.
 *  - A missing `description` is the property's `title`, or empty; a `default`, `format` or `examples` the model cannot
 *    carry as a keyword is added to the description, so the model still learns it.
 */
private[mcp] object MCPInputSchema {

  private type Fields = Map[String, Value]

  /** How many references one path through the schema may follow; a deeper or recursive one accepts any value. */
  private val MaxRefDepth = 16

  /**
   * The tool's argument schema, an object whatever the server sent: an `ObjectSchema` when the model can express it,
   * otherwise the server's own JSON (see [[asWritten]]), with `type: object` ensured.
   */
  def toSchema(toolName: String, inputSchema: Value): SchemaDefinition[_] = {
    val fields = inputSchema.objOpt.map(_.toMap).getOrElse(Map.empty[String, Value])
    val defs   = definitions(fields)
    // Tool arguments are always an object: a top level with no `type` is one
    val top = (fields -- Seq("$defs", "definitions")) ++
      Option.when(!fields.contains("type"))("type" -> ujson.Str("object"))
    if (!expressible(top)) {
      val written = asWritten(top, defs, Nil)
      written("type") = ujson.Str("object")
      RawJsonSchema(written)
    } else objectSchema(top, defs, Nil, Some(s"Parameters for $toolName"))
  }

  /** Keywords that only describe: the model is told them in the description, wherever they appear. */
  private val Annotations: Set[String] = Set("description", "title", "default", "examples", "format", "$comment")

  /**
   * The keywords the typed schema of each type carries faithfully. A schema is converted to a typed one only when every
   * keyword it has, annotations aside, is on its type's list and of a shape the type holds ([[expressible]]); any
   * other is kept as the server wrote it, so no restriction is lost (#3076).
   */
  private val Faithful: Map[String, Set[String]] = Map(
    "string"  -> Set("type", "enum", "const", "minLength", "maxLength", "pattern"),
    "number"  -> Set("type", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf"),
    "integer" -> Set("type", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf"),
    "boolean" -> Set("type"),
    "array"   -> Set("type", "items", "minItems", "maxItems", "uniqueItems"),
    "object"  -> Set("type", "properties", "required", "additionalProperties")
  )

  private def isCount(v: Value)  = v.numOpt.exists(n => n.isWhole && n >= 0 && n.isValidInt)
  private def isNumber(v: Value) = v.numOpt.isDefined
  private def isInt(v: Value)    = v.numOpt.exists(n => n.isWhole && n.isValidInt)
  private def isBound(v: Value)  = isNumber(v) || v.boolOpt.isDefined // the number form, or the older boolean one
  private def strings(v: Value)  = v.arrOpt.exists(_.forall(_.strOpt.isDefined))
  private def listed(v: Value)   = v.arrOpt.exists(_.nonEmpty)

  /** Whether `value`, the keyword `key` of a schema of type `schemaType`, has a shape that type's typed schema holds. */
  private def holds(schemaType: String, key: String, value: Value): Boolean = (schemaType, key) match {
    case (_, "type")                                          => true
    case ("string", "enum")                                   => strings(value)
    case ("string", "const")                                  => value.strOpt.isDefined
    case ("string", "minLength" | "maxLength")                => isCount(value)
    case ("string", "pattern")                                => value.strOpt.isDefined
    case ("number", "multipleOf")                             => isNumber(value)
    case ("number", "exclusiveMinimum" | "exclusiveMaximum")  => isBound(value)
    case ("number", _)                                        => isNumber(value)
    case ("integer", "exclusiveMinimum" | "exclusiveMaximum") => isInt(value) || value.boolOpt.isDefined
    case ("integer", _)                                       => isInt(value)
    case ("array", "items")                                   => value.objOpt.isDefined
    case ("array", "uniqueItems")                             => value.boolOpt.isDefined
    case ("array", _)                                         => isCount(value)
    case ("object", "properties")                             => value.objOpt.isDefined
    case ("object", "additionalProperties")                   => value.boolOpt.isDefined
    case ("object", "required")                               => strings(value)
    case _                                                    => false
  }

  /**
   * Whether the typed schema model can carry `fields` without losing anything: an allowlist, per type, of keywords and
   * of the shapes of their values. A `required` name must be a property; an `anyOf` / `oneOf` stands alone.
   */
  private def expressible(fields: Fields): Boolean = {
    val keys = fields.keySet -- Annotations
    val types = fields.get("type") match {
      case Some(ujson.Str(name))  => Some(List(name))
      case Some(ujson.Arr(names)) => Some(names.toList.flatMap(_.strOpt)).filter(_.size == names.size)
      case None                   => Some(Nil)
      case _                      => None
    }
    val nonNull = types.map(_.filterNot(_ == "null"))
    def faithfulAs(t: String) =
      Faithful.get(t).exists(allowed => keys.subsetOf(allowed) && keys.forall(k => holds(t, k, fields(k))))
    val requiredDeclared = {
      val properties = fields.get("properties").flatMap(_.objOpt).map(_.keySet.toSet).getOrElse(Set.empty[String])
      fields.get("required").flatMap(_.arrOpt).toSeq.flatMap(_.flatMap(_.strOpt)).toSet.subsetOf(properties)
    }
    val union = Seq("anyOf", "oneOf").filter(fields.contains)
    nonNull match {
      case None => false
      case Some(_) if union.nonEmpty =>
        union.size == 1 && listed(fields(union.head)) && keys.subsetOf(Set(union.head, "type")) &&
        types.forall(_.forall(_ == "null"))
      case Some(List(single)) => faithfulAs(single) && requiredDeclared
      // `properties` with no `type` allows any value that is not an object too: only the JSON says so
      case Some(Nil) if fields.contains("properties") => false
      case Some(Nil)                                  => keys.subsetOf(Set("type"))
      case Some(_)                                    => keys.subsetOf(Set("type")) // several types: no keyword
    }
  }

  /** The keywords whose value is a schema, or a map or list of schemas: the only places a `$ref` is followed. */
  private val SchemaKeywords: Set[String] = Set(
    "items",
    "additionalProperties",
    "unevaluatedProperties",
    "unevaluatedItems",
    "contains",
    "propertyNames",
    "not",
    "if",
    "then",
    "else",
    "additionalItems"
  )
  private val SchemaMapKeywords: Set[String]  = Set("properties", "patternProperties", "dependentSchemas")
  private val SchemaListKeywords: Set[String] = Set("anyOf", "oneOf", "allOf", "prefixItems")

  /**
   * `fields` as the server wrote them, for a schema the model cannot express: every keyword kept, except that a
   * subschema (only under a schema-valued keyword, never inside `enum`, `const`, `default` or `examples`, which are
   * data) is converted in turn, so its references are resolved and its annotations described; the annotations of this
   * one go into its `description`, which an agent's argument validator reads, and `note` after them.
   */
  private def asWritten(
    fields: Fields,
    defs: Map[String, Value],
    seen: List[String],
    note: Option[String] = None
  ): ujson.Obj = {
    // a boolean schema (`true`, `false`) stays as it is
    def sub(v: Value): Value = if (v.objOpt.isDefined) convert(v, defs, seen).toJsonSchema(strict = false) else v
    val out                  = ujson.Obj()
    fields.foreach { case (key, value) =>
      if (Annotations.contains(key) || key == "$ref" || key == "$defs" || key == "definitions") ()
      else if (SchemaKeywords.contains(key))
        out(key) = value.arrOpt.fold(sub(value))(items => ujson.Arr.from(items.map(sub)))
      else if (SchemaMapKeywords.contains(key))
        out(key) = value.objOpt.fold(value)(m => ujson.Obj.from(m.toSeq.map { case (k, v) => k -> sub(v) }))
      else if (SchemaListKeywords.contains(key)) out(key) = value.arrOpt.fold(value)(l => ujson.Arr.from(l.map(sub)))
      else out(key) = value
    }
    val description = (Seq(describe(fields)).filter(_.nonEmpty) ++ note).mkString(" ")
    if (description.nonEmpty) out("description") = description
    out
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
            RawJsonSchema(asWritten(raw.toMap, defs, seen, Some(s"($reason: any value)")))
          case Right((fields, path)) if !expressible(fields) => RawJsonSchema(asWritten(fields, defs, path))
          case Right((fields, path))                         => typed(fields, defs, path)
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
      if (types.contains("null")) orNull(schema, description) else schema

    (alternatives, nonNull) match {
      case (Some(alts), _) =>
        val (nulls, others) = alts.partition(_.objOpt.flatMap(_.get("type")).contains(ujson.Str("null")))
        val converted       = others.map(convert(_, defs, seen))
        val union = converted match {
          case Nil           => RawJsonSchema(ujson.Obj("type" -> "null", "description" -> description))
          case single :: Nil => single
          case first :: rest => AnyOfSchema[Value](description, first, rest: _*)
        }
        if (nulls.nonEmpty && converted.nonEmpty) orNull(union, description) else union
      case (None, List(single))                         => nullable(typedOne(single, description, fields, defs, seen))
      case (None, Nil) if fields.contains("properties") => nullable(objectSchema(fields, defs, seen, None))
      // `{"type":"null"}`, or no type and no keyword: as written (a null type), or any value
      case (None, Nil) => RawJsonSchema(asWritten(fields, defs, seen))
      case (None, first :: rest) =>
        val each = (first :: rest).map(t => typedOne(t, "", fields, defs, seen))
        nullable(AnyOfSchema[Value](description, each.head, each.tail: _*))
    }
  }

  /**
   * `schema` or null. A typed schema widens safely (`NullableSchema` adds `null` to its type and enum); one kept as
   * JSON may have a `const` or `enum` the widening cannot cover, so it gets an explicit `{"type":"null"}` alternative.
   */
  private def orNull(schema: SchemaDefinition[_], description: String): SchemaDefinition[_] = schema match {
    case raw: RawJsonSchema => AnyOfSchema[Value](description, raw, RawJsonSchema(ujson.Obj("type" -> "null")))
    case typed              => NullableSchema(typed)
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
      case "string" =>
        StringSchema(description)
          .withEnum(values.map(_.flatMap(_.strOpt)))
          .withMinLength(int("minLength"))
          .withMaxLength(int("maxLength"))
          .withPattern(fields.get("pattern").flatMap(_.strOpt))
      case "number" =>
        NumberSchema(description)
          .withMinimum(inclusive("exclusiveMinimum", "minimum"))
          .withMaximum(inclusive("exclusiveMaximum", "maximum"))
          .withExclusiveMinimum(exclusive("exclusiveMinimum", "minimum"))
          .withExclusiveMaximum(exclusive("exclusiveMaximum", "maximum"))
          .withMultipleOf(double("multipleOf"))
      case "integer" =>
        def intExclusive(flag: String, bound: String) = exclusive(flag, bound).map(_.toInt)
        def intInclusive(flag: String, bound: String) = inclusive(flag, bound).map(_.toInt)
        IntegerSchema(description)
          .withMinimum(intInclusive("exclusiveMinimum", "minimum"))
          .withMaximum(intInclusive("exclusiveMaximum", "maximum"))
          .withExclusiveMinimum(intExclusive("exclusiveMinimum", "minimum"))
          .withExclusiveMaximum(intExclusive("exclusiveMaximum", "maximum"))
          .withMultipleOf(int("multipleOf"))
      case "boolean" => BooleanSchema(description)
      case "array" =>
        val items = fields.get("items").map(convert(_, defs, seen)).getOrElse(RawJsonSchema(ujson.Obj()))
        val array = ArraySchema(description, items)
          .withMinItems(int("minItems"))
          .withMaxItems(int("maxItems"))
        if (fields.get("uniqueItems").contains(ujson.Bool(true))) array.withUniqueItems() else array
      case "object" => objectSchema(fields, defs, seen, None)
      // a type the model has no case for (`expressible` sends none here): as written
      case _ => RawJsonSchema(asWritten(fields + ("type" -> ujson.Str(schemaType)), defs, seen))
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
      case Some(_)                   => true // a schema for the extra keys: not expressible, so not reached
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
