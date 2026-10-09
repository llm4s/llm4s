package org.llm4s.schema

import org.llm4s.schema.Fixtures.*
import org.llm4s.toolapi.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The derived schema of every supported shape, against the JSON it has to be, key order included. */
class SchemaOfSpec extends AnyFlatSpec with Matchers {

  private def json(text: String): String           = ujson.write(ujson.read(text))
  private def written(schema: ujson.Value): String = ujson.write(schema)

  "A derived schema" should "be the hand-built schema of the sample it replaces" in {
    val handBuilt = Schema
      .`object`[Invoice]("An invoice extracted from text")
      .withRequiredField("vendor", Schema.string("Name of the vendor or supplier"))
      .withRequiredField("amount", Schema.number("Total invoice amount as a decimal number"))
      .withRequiredField("currency", Schema.string("ISO 4217 currency code, e.g. USD, EUR, GBP"))
      .withRequiredField("description", Schema.string("Brief description of goods or services"))

    written(SchemaOf[Invoice].toJsonSchema()) shouldBe written(handBuilt.toJsonSchema(strict = true))
    written(SchemaOf[Invoice].toJsonSchema(strict = false)) shouldBe written(handBuilt.toJsonSchema(strict = false))
  }

  it should "describe the scalar types, with a BigDecimal and a BigInt as strings as uPickle writes them" in {
    written(SchemaOf[Scalars].toJsonSchema()) shouldBe json(
      """{"type":"object","description":"Scalars","properties":{
        |"s":{"type":"string","description":"s"},
        |"i":{"type":"integer","description":"i"},
        |"l":{"type":"integer","description":"l"},
        |"d":{"type":"number","description":"d"},
        |"f":{"type":"number","description":"f"},
        |"b":{"type":"boolean","description":"b"},
        |"bd":{"type":"string","description":"bd"},
        |"bi":{"type":"string","description":"bi"}},
        |"required":["s","i","l","d","f","b","bd","bi"],"additionalProperties":false}""".stripMargin
    )
  }

  it should "describe a field by its @description, else its type's @description, else its name" in {
    written(SchemaOf[Described].toJsonSchema()) shouldBe json(
      """{"type":"object","description":"A described inner type","properties":{
        |"label":{"type":"string","description":"the label"}},
        |"required":["label"],"additionalProperties":false}""".stripMargin
    )
    written(SchemaOf[Inner].toJsonSchema()) shouldBe json(
      """{"type":"object","description":"Inner","properties":{"n":{"type":"integer","description":"n"}},
        |"required":["n"],"additionalProperties":false}""".stripMargin
    )
  }

  it should "nest case classes, describing a list's items by the item type" in {
    val inner =
      """"properties":{"n":{"type":"integer","description":"n"}},"required":["n"],"additionalProperties":false"""
    written(SchemaOf[Nested].toJsonSchema()) shouldBe json(
      s"""{"type":"object","description":"Nested","properties":{
         |"inner":{"type":"object","description":"inner",$inner},
         |"items":{"type":"array","description":"items","items":{"type":"object","description":"Inner",$inner}},
         |"described":{"type":"object","description":"A described inner type","properties":{
         |"label":{"type":"string","description":"the label"}},"required":["label"],"additionalProperties":false}},
         |"required":["inner","items","described"],"additionalProperties":false}""".stripMargin
    )
  }

  it should "describe an enum and a sealed hierarchy of singletons as a string with the case names" in {
    written(SchemaOf[Color].toJsonSchema()) shouldBe json(
      """{"type":"string","description":"Color","enum":["Red","Green","Blue"]}"""
    )
    written(SchemaOf[Planet].toJsonSchema()) shouldBe json(
      """{"type":"string","description":"Planet","enum":["Earth","Mars"]}"""
    )
    written(SchemaOf[Letter].toJsonSchema()) shouldBe json(
      """{"type":"string","description":"Letter","enum":["A","B"]}"""
    )
  }

  it should "make an Option nullable, and allow null among the values of an optional enum" in {
    written(SchemaOf[Optionals].toJsonSchema()) shouldBe json(
      """{"type":"object","description":"Optionals","properties":{
        |"a":{"type":["integer","null"],"description":"a"},
        |"b":{"type":["object","null"],"description":"b",
        |"properties":{"n":{"type":"integer","description":"n"}},"required":["n"],"additionalProperties":false},
        |"c":{"type":["string","null"],"description":"c","enum":["Red","Green","Blue",null]}},
        |"required":["a","b","c"],"additionalProperties":false}""".stripMargin
    )
  }

  it should "leave an Option out of the required properties unless strict" in {
    val strict  = SchemaOf[Defaults].toJsonSchema().obj("required").arr.map(_.str)
    val relaxed = SchemaOf[Defaults].toJsonSchema(strict = false).obj("required").arr.map(_.str)
    strict.toList shouldBe List("a", "b")
    relaxed.toList shouldBe List("a")
  }

  it should "describe List, Vector, Seq and Set as arrays of the item schema" in {
    written(SchemaOf[Collections].toJsonSchema()) shouldBe json(
      """{"type":"object","description":"Collections","properties":{
        |"list":{"type":"array","description":"list","items":{"type":"integer","description":"integer"}},
        |"vector":{"type":"array","description":"vector","items":{"type":"string","description":"string"}},
        |"seq":{"type":"array","description":"seq","items":{"type":"boolean","description":"boolean"}},
        |"set":{"type":"array","description":"set","items":{"type":"integer","description":"integer"}},
        |"options":{"type":"array","description":"options","items":{"type":["integer","null"],"description":"integer"}}},
        |"required":["list","vector","seq","set","options"],"additionalProperties":false}""".stripMargin
    )
  }

  it should "use enums inside case classes and collections" in {
    written(SchemaOf[WithEnums].toJsonSchema()) shouldBe json(
      """{"type":"object","description":"WithEnums","properties":{
        |"color":{"type":"string","description":"color","enum":["Red","Green","Blue"]},
        |"planet":{"type":"string","description":"planet","enum":["Earth","Mars"]},
        |"letters":{"type":"array","description":"letters",
        |"items":{"type":"string","description":"Letter","enum":["A","B"]}},
        |"maybe":{"type":["string","null"],"description":"maybe","enum":["Red","Green","Blue",null]}},
        |"required":["color","planet","letters","maybe"],"additionalProperties":false}""".stripMargin
    )
  }

  it should "name a property as uPickle writes it, honouring @upickle.implicits.key" in {
    written(SchemaOf[Renamed].toJsonSchema()) shouldBe json(
      """{"type":"object","description":"Renamed","properties":{
        |"user_name":{"type":"string","description":"userName"},
        |"age":{"type":"integer","description":"age"}},
        |"required":["user_name","age"],"additionalProperties":false}""".stripMargin
    )
  }

  it should "derive a generic case class for the type it is used at" in {
    written(SchemaOf[Box[Int]].toJsonSchema()) shouldBe json(
      """{"type":"object","description":"Box","properties":{"value":{"type":"integer","description":"value"}},
        |"required":["value"],"additionalProperties":false}""".stripMargin
    )
    written(SchemaOf[Box[List[String]]].toJsonSchema()) shouldBe json(
      """{"type":"object","description":"Box","properties":{
        |"value":{"type":"array","description":"value","items":{"type":"string","description":"string"}}},
        |"required":["value"],"additionalProperties":false}""".stripMargin
    )
  }

  it should "be built from the types the user defines with SchemaOf.string and SchemaOf.stringEnum" in {
    given SchemaOf[java.time.Instant]  = SchemaOf.string("ISO-8601 instant")
    given SchemaOf[Boolean => Boolean] = SchemaOf.stringEnum("switch", Seq("on", "off"))
    written(SchemaOf[java.time.Instant].toJsonSchema()) shouldBe json(
      """{"type":"string","description":"ISO-8601 instant"}"""
    )
    written(SchemaOf[Boolean => Boolean].toJsonSchema()) shouldBe json(
      """{"type":"string","description":"switch","enum":["on","off"]}"""
    )
  }

  it should "describe a field of a user-defined type by that type's description, unless the field has its own" in {
    written(SchemaOf[Event].toJsonSchema()) shouldBe json(
      """{"type":"object","description":"Event","properties":{
        |"at":{"type":"string","description":"ISO-8601 instant, for example 2026-10-08T09:30:00Z"},
        |"until":{"type":"string","description":"when it ends"}},
        |"required":["at","until"],"additionalProperties":false}""".stripMargin
    )
  }
}
