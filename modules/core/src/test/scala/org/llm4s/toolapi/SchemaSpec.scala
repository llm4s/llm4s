package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * What a provider receives: the JSON Schema that `Schema` and `SchemaDefinition` generate, as it travels in a
 * tool definition. [[SchemaDefinitionSpec]] checks each schema type on its own; this spec checks the whole
 * document - its shape, strict mode through nesting, key order, number formatting and the round trip through
 * the wire format - because a malformed schema fails tool calls without any error on our side.
 */
class SchemaSpec extends AnyFlatSpec with Matchers {

  private type Params = Map[String, Any]

  /** One schema of every kind, nested: the shape a real tool takes. */
  private val address: ObjectSchema[Params] =
    Schema
      .`object`[Params]("Postal address")
      .withRequiredField("street", Schema.string("Street and number"))
      .withOptionalField("unit", Schema.nullable(Schema.string("Flat or suite")))

  private val everything: ObjectSchema[Params] =
    Schema
      .`object`[Params]("Everything")
      .withRequiredField("name", Schema.string("The user's name").withLengthConstraints(Some(1), Some(80)))
      .withRequiredField("color", Schema.string("A colour").withEnum(Seq("red", "green", "blue")))
      .withRequiredField("count", Schema.integer("Number of items").withRange(Some(0), Some(100)))
      .withRequiredField("ratio", Schema.number("A ratio").withRange(Some(0.5), Some(1.5)))
      .withRequiredField("enabled", Schema.boolean("Whether to enable"))
      .withRequiredField(
        "tags",
        Schema.array("Tags", Schema.string("A tag")).withSizeConstraints(Some(1), Some(5)).withUniqueItems()
      )
      .withRequiredField("home", address)
      .withOptionalField("previous", Schema.array("Earlier addresses", address))
      .withOptionalField("nickname", Schema.nullable(Schema.string("A nickname")))

  private def tool(schema: SchemaDefinition[Params]): ToolFunction[Params, String] =
    ToolBuilder[Params, String]("everything", "Takes every kind of parameter", schema)
      .withHandler(_ => Right("ok"))
      .buildSafe()
      .fold(error => fail(error.formatted), identity)

  /**
   * The structural rules a provider applies to a JSON Schema node: it has a type, an object lists its
   * properties and requires only properties it has, an array has items. Returns every violation found.
   */
  private def shapeProblems(node: ujson.Value, path: String = "$"): Seq[String] =
    node match {
      case schema: ujson.Obj =>
        val types = schema.value.get("type") match {
          case Some(ujson.Str(single)) => Seq(single)
          case Some(ujson.Arr(many))   => many.toSeq.collect { case ujson.Str(one) => one }
          case _                       => Seq.empty
        }
        val own =
          if (types.isEmpty) Seq(s"$path has no type") else Seq.empty
        val objectRules =
          if (types.contains("object")) {
            val properties = schema.value.get("properties").collect { case o: ujson.Obj => o }
            val required   = schema.value.get("required").collect { case a: ujson.Arr => a.value.toSeq }
            val missing =
              (properties, required) match {
                case (Some(props), Some(names)) =>
                  names.collect {
                    case ujson.Str(name) if !props.value.contains(name) => s"$path requires unknown $name"
                  }
                case _ => Seq(s"$path is an object without properties and required")
              }
            val additional =
              if (schema.value.contains("additionalProperties")) Seq.empty
              else Seq(s"$path has no additionalProperties")
            missing ++ additional ++ properties.toSeq.flatMap(_.value.toSeq.flatMap { case (key, child) =>
              shapeProblems(child, s"$path.$key")
            })
          } else Seq.empty
        val arrayRules =
          if (types.contains("array"))
            schema.value.get("items") match {
              case Some(items) => shapeProblems(items, s"$path[]")
              case None        => Seq(s"$path is an array without items")
            }
          else Seq.empty
        own ++ objectRules ++ arrayRules
      case _ => Seq(s"$path is not an object")
    }

  private def required(node: ujson.Value): Seq[String] = node("required").arr.map(_.str).toSeq

  // ---- shape

  "A tool's parameter schema" should "be a well-formed JSON Schema at every depth, strict or not" in {
    Seq(true, false).foreach(strict => shapeProblems(everything.toJsonSchema(strict)) shouldBe Seq.empty)
  }

  it should "be caught by the shape check when a schema is malformed" in {
    // a guard on the guard: the check above must be able to fail
    shapeProblems(ujson.Obj("type" -> "array")) shouldBe Seq("$ is an array without items")
    shapeProblems(ujson.Obj("description" -> "no type")) shouldBe Seq("$ has no type")
    shapeProblems(
      ujson.Obj(
        "type"                 -> "object",
        "properties"           -> ujson.Obj(),
        "required"             -> ujson.Arr("ghost"),
        "additionalProperties" -> false
      )
    ) shouldBe Seq("$ requires unknown ghost")
  }

  it should "carry the description of every node it describes" in {
    val json = everything.toJsonSchema(strict = true)

    json("description").str shouldBe "Everything"
    json("properties")("name")("description").str shouldBe "The user's name"
    json("properties")("tags")("items")("description").str shouldBe "A tag"
    json("properties")("home")("properties")("street")("description").str shouldBe "Street and number"
  }

  it should "give each kind of schema its JSON Schema type" in {
    val properties = everything.toJsonSchema(strict = false)("properties")

    properties("name")("type").str shouldBe "string"
    properties("color")("type").str shouldBe "string"
    properties("count")("type").str shouldBe "integer"
    properties("ratio")("type").str shouldBe "number"
    properties("enabled")("type").str shouldBe "boolean"
    properties("tags")("type").str shouldBe "array"
    properties("home")("type").str shouldBe "object"
    properties("nickname")("type").arr.map(_.str).toSeq shouldBe Seq("string", "null")
  }

  it should "emit its constraints under the JSON Schema keywords" in {
    val properties = everything.toJsonSchema(strict = false)("properties")

    properties("name")("minLength").num shouldBe 1
    properties("name")("maxLength").num shouldBe 80
    properties("color")("enum").arr.map(_.str).toSeq shouldBe Seq("red", "green", "blue")
    properties("count")("minimum").num shouldBe 0
    properties("count")("maximum").num shouldBe 100
    properties("ratio")("minimum").num shouldBe 0.5
    properties("ratio")("maximum").num shouldBe 1.5
    properties("tags")("minItems").num shouldBe 1
    properties("tags")("maxItems").num shouldBe 5
    properties("tags")("uniqueItems").bool shouldBe true
  }

  it should "leave a constraint out of the document when it was not set" in {
    val json = Schema.integer("Plain").toJsonSchema(strict = true).obj

    json.keySet shouldBe Set("type", "description")
    Schema.string("Plain").toJsonSchema(strict = true).obj.keySet shouldBe Set("type", "description")
    Schema.array("Plain", Schema.string("x")).toJsonSchema(strict = true).obj.keySet shouldBe
      Set("type", "description", "items")
  }

  // ---- strict mode through nesting

  "Strict mode" should "require every property of the top-level object and of each nested object" in {
    val json = everything.toJsonSchema(strict = true)

    required(json) shouldBe Seq("name", "color", "count", "ratio", "enabled", "tags", "home", "previous", "nickname")
    required(json("properties")("home")) shouldBe Seq("street", "unit")
    required(json("properties")("previous")("items")) shouldBe Seq("street", "unit")
  }

  it should "leave optional properties optional when it is off, at every depth" in {
    val json = everything.toJsonSchema(strict = false)

    required(json) shouldBe Seq("name", "color", "count", "ratio", "enabled", "tags", "home")
    required(json("properties")("home")) shouldBe Seq("street")
    required(json("properties")("previous")("items")) shouldBe Seq("street")
  }

  it should "reach an object that is wrapped in a nullable schema" in {
    val wrapped = Schema.`object`[Params]("Holder").withOptionalField("maybe", Schema.nullable(address))

    val strict = wrapped.toJsonSchema(strict = true)("properties")("maybe")
    strict("type").arr.map(_.str).toSeq shouldBe Seq("object", "null")
    required(strict) shouldBe Seq("street", "unit")

    required(wrapped.toJsonSchema(strict = false)("properties")("maybe")) shouldBe Seq("street")
  }

  it should "not change the schema it renders from" in {
    val before = everything.toJsonSchema(strict = false)
    everything.toJsonSchema(strict = true)

    everything.toJsonSchema(strict = false) shouldBe before
  }

  // ---- ordering

  "The document" should "list properties, and required names, in the order they were declared" in {
    val json = everything.toJsonSchema(strict = true)

    json("properties").obj.keys.toSeq shouldBe
      Seq("name", "color", "count", "ratio", "enabled", "tags", "home", "previous", "nickname")
    json("properties")("home")("properties").obj.keys.toSeq shouldBe Seq("street", "unit")
  }

  it should "be byte-for-byte the same on every rendering" in {
    val renderings = (1 to 5).map(_ => ujson.write(tool(everything).toOpenAITool()))

    renderings.distinct should have size 1
  }

  // ---- numbers on the wire

  "Numeric constraints" should "be written as integers for an integer schema and as decimals for a number schema" in {
    val written = ujson.write(everything.toJsonSchema(strict = false))

    // whatever follows the value (`,` or the closing `}`), the value itself is spelled without a decimal point
    (written should include).regex("\"minimum\":0[,}]")
    (written should include).regex("\"maximum\":100[,}]")
    (written should include).regex("\"minimum\":0\\.5[,}]")
    (written should include).regex("\"maximum\":1\\.5[,}]")
    (written should not).include("\"minimum\":0.0")
    (written should not).include("\"maximum\":100.0")
    (written should not).include("\"minLength\":1.0")
    (written should not).include("\"maxItems\":5.0")
  }

  // ---- the tool definition

  "A ToolFunction carrying every schema" should "serialise to JSON that parses back to the same document" in {
    val definition = tool(everything).toOpenAITool()

    ujson.read(ujson.write(definition)) shouldBe definition
    ujson.read(ujson.write(definition, indent = 2)) shouldBe definition
  }

  it should "present itself to a provider as a function whose parameters are the schema" in {
    val definition = tool(everything).toOpenAITool()

    definition.obj.keySet shouldBe Set("type", "function")
    definition("type").str shouldBe "function"
    definition("function").obj.keySet shouldBe Set("name", "description", "parameters", "strict")
    definition("function")("name").str shouldBe "everything"
    definition("function")("description").str shouldBe "Takes every kind of parameter"
    definition("function")("parameters") shouldBe everything.toJsonSchema(strict = true)
    shapeProblems(definition("function")("parameters")) shouldBe Seq.empty
  }

  it should "render its parameters in the mode its strict flag names" in {
    val tolerant = tool(everything).toOpenAITool(strict = false)

    tolerant("function")("strict").bool shouldBe false
    tolerant("function")("parameters") shouldBe everything.toJsonSchema(strict = false)
    required(tolerant("function")("parameters")) should not contain "nickname"
    required(tool(everything).toOpenAITool()("function")("parameters")) should contain("nickname")
  }

  it should "contain no null, NaN or infinite value anywhere in the document" in {
    def leaves(value: ujson.Value): Seq[ujson.Value] = value match {
      case o: ujson.Obj => o.value.values.toSeq.flatMap(leaves)
      case a: ujson.Arr => a.value.toSeq.flatMap(leaves)
      case other        => Seq(other)
    }

    val numbers = leaves(tool(everything).toOpenAITool()).collect { case ujson.Num(n) => n }

    numbers should not be empty
    numbers.foreach(n => n.isNaN || n.isInfinite shouldBe false)
    leaves(tool(everything).toOpenAITool()) should not contain ujson.Null
  }
}
