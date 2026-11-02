package org.llm4s.schema

import org.llm4s.schema.SumFixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ write, ReadWriter }

/**
 * The schema derived for a sum type whose cases have fields (#2327): an `anyOf` of one alternative per case, pinned
 * as golden JSON, whose discriminator and tags are exactly what uPickle writes.
 */
class SumTypeSchemaSpec extends AnyFlatSpec with Matchers {

  private def golden(text: String): ujson.Value = ujson.read(text.stripMargin)

  "An enum whose cases have fields" should "derive an anyOf of tagged objects, with the cases' descriptions" in {
    SchemaOf[Action].toJsonSchema() shouldBe golden(
      """
      |{
      |  "description": "What the support bot decides to do",
      |  "anyOf": [
      |    {
      |      "type": "object",
      |      "description": "Refund the customer",
      |      "properties": {
      |        "$type": {
      |          "type": "string",
      |          "description": "Always \"Refund\"",
      |          "enum": [
      |            "Refund"
      |          ]
      |        },
      |        "amount": {
      |          "type": "string",
      |          "description": "amount"
      |        },
      |        "reason": {
      |          "type": "string",
      |          "description": "reason"
      |        }
      |      },
      |      "required": [
      |        "$type",
      |        "amount",
      |        "reason"
      |      ],
      |      "additionalProperties": false
      |    },
      |    {
      |      "type": "object",
      |      "description": "Hand over to a human team",
      |      "properties": {
      |        "$type": {
      |          "type": "string",
      |          "description": "Always \"Escalate\"",
      |          "enum": [
      |            "Escalate"
      |          ]
      |        },
      |        "team": {
      |          "type": "string",
      |          "description": "team",
      |          "enum": [
      |            "Billing",
      |            "Shipping"
      |          ]
      |        }
      |      },
      |      "required": [
      |        "$type",
      |        "team"
      |      ],
      |      "additionalProperties": false
      |    },
      |    {
      |      "type": "object",
      |      "description": "Reply",
      |      "properties": {
      |        "$type": {
      |          "type": "string",
      |          "description": "Always \"Reply\"",
      |          "enum": [
      |            "Reply"
      |          ]
      |        },
      |        "text": {
      |          "type": "string",
      |          "description": "text"
      |        }
      |      },
      |      "required": [
      |        "$type",
      |        "text"
      |      ],
      |      "additionalProperties": false
      |    }
      |  ]
      |}
      |"""
    )
  }

  "A sealed trait mixing case classes, a renamed case, a case object and a case class without fields" should
    "derive an object per case with fields and a string per singleton" in {
      SchemaOf[Shape].toJsonSchema() shouldBe golden(
        """
      |{
      |  "description": "Shape",
      |  "anyOf": [
      |    {
      |      "type": "object",
      |      "description": "Circle",
      |      "properties": {
      |        "$type": {
      |          "type": "string",
      |          "description": "Always \"Circle\"",
      |          "enum": [
      |            "Circle"
      |          ]
      |        },
      |        "radius": {
      |          "type": "number",
      |          "description": "radius"
      |        }
      |      },
      |      "required": [
      |        "$type",
      |        "radius"
      |      ],
      |      "additionalProperties": false
      |    },
      |    {
      |      "type": "object",
      |      "description": "Rectangle",
      |      "properties": {
      |        "$type": {
      |          "type": "string",
      |          "description": "Always \"rect\"",
      |          "enum": [
      |            "rect"
      |          ]
      |        },
      |        "width": {
      |          "type": "number",
      |          "description": "width"
      |        },
      |        "h": {
      |          "type": "number",
      |          "description": "height"
      |        }
      |      },
      |      "required": [
      |        "$type",
      |        "width",
      |        "h"
      |      ],
      |      "additionalProperties": false
      |    },
      |    {
      |      "type": "string",
      |      "description": "A single point",
      |      "enum": [
      |        "Dot"
      |      ]
      |    },
      |    {
      |      "type": "object",
      |      "description": "Blank",
      |      "properties": {
      |        "$type": {
      |          "type": "string",
      |          "description": "Always \"Blank\"",
      |          "enum": [
      |            "Blank"
      |          ]
      |        }
      |      },
      |      "required": [
      |        "$type"
      |      ],
      |      "additionalProperties": false
      |    }
      |  ]
      |}
      |"""
      )
    }

  "A sealed trait with a custom discriminator" should "use the trait's @key as the discriminator and the cases' tags" in {
    SchemaOf[Event].toJsonSchema() shouldBe golden(
      """
      |{
      |  "description": "Event",
      |  "anyOf": [
      |    {
      |      "type": "object",
      |      "description": "Click",
      |      "properties": {
      |        "kind": {
      |          "type": "string",
      |          "description": "Always \"click\"",
      |          "enum": [
      |            "click"
      |          ]
      |        },
      |        "x": {
      |          "type": "integer",
      |          "description": "x"
      |        },
      |        "y": {
      |          "type": "integer",
      |          "description": "y"
      |        }
      |      },
      |      "required": [
      |        "kind",
      |        "x",
      |        "y"
      |      ],
      |      "additionalProperties": false
      |    },
      |    {
      |      "type": "object",
      |      "description": "Typed",
      |      "properties": {
      |        "kind": {
      |          "type": "string",
      |          "description": "Always \"Typed\"",
      |          "enum": [
      |            "Typed"
      |          ]
      |        },
      |        "text": {
      |          "type": "string",
      |          "description": "text"
      |        },
      |        "at": {
      |          "type": [
      |            "integer",
      |            "null"
      |          ],
      |          "description": "at"
      |        },
      |        "tags": {
      |          "type": "array",
      |          "description": "tags",
      |          "items": {
      |            "type": "string",
      |            "description": "string"
      |          }
      |        }
      |      },
      |      "required": [
      |        "kind",
      |        "text",
      |        "at",
      |        "tags"
      |      ],
      |      "additionalProperties": false
      |    },
      |    {
      |      "type": "string",
      |      "description": "Idle",
      |      "enum": [
      |        "idle"
      |      ]
      |    }
      |  ]
      |}
      |"""
    )
  }

  "A case class holding sum types" should "describe them in place, directly, in a List and in an Option" in {
    val drawing = SchemaOf[Drawing].toJsonSchema()
    drawing("type") shouldBe ujson.Str("object")
    drawing("required") shouldBe ujson.Arr("title", "shapes", "highlight", "last")
    drawing("properties")("shapes")("items")("anyOf") shouldBe SchemaOf[Shape].toJsonSchema()("anyOf")
    drawing("properties")("highlight")("anyOf").arr.toList shouldBe
      SchemaOf[Shape].toJsonSchema()("anyOf").arr.toList :+ ujson.Obj("type" -> "null")
    drawing("properties")("last")("anyOf") shouldBe SchemaOf[Event].toJsonSchema()("anyOf")
    SchemaOf[Payment].toJsonSchema()("anyOf")(1)("properties")("next")("anyOf").arr.toList shouldBe
      SchemaOf[Action].toJsonSchema()("anyOf").arr.toList :+ ujson.Obj("type" -> "null")
  }

  it should "list an Option of a sum type as not required only in the relaxed schema" in {
    SchemaOf[Drawing].toJsonSchema(strict = false)("required") shouldBe ujson.Arr("title", "shapes", "last")
    SchemaOf[Option[Action]].isOptional shouldBe true
  }

  "Each case's discriminator" should "be what uPickle writes for it" in {

    /** The tag uPickle writes for `value`, and the alternative of the schema that admits only that tag. */
    def agrees[A: SchemaOf: ReadWriter](discriminator: String, values: List[A], expectedTags: List[String]): Unit = {
      val alternatives = SchemaOf[A].toJsonSchema()("anyOf").arr.toList
      val tags = values.map { value =>
        ujson.read(write(value)) match {
          case ujson.Str(tag) =>
            alternatives.filter(a => a("type") == ujson.Str("string")).map(_("enum")) should contain(ujson.Arr(tag))
            tag
          case obj =>
            val tag = obj(discriminator).str
            alternatives
              .filter(a => a("type") == ujson.Str("object"))
              .map(_("properties")(discriminator)("enum")) should
              contain(ujson.Arr(tag))
            tag
        }
      }
      tags shouldBe expectedTags
      alternatives should have size values.size
    }
    agrees[Action](
      "$type",
      List(Action.Refund(BigDecimal(1), "r"), Action.Escalate(Team.Billing), Action.Reply("t")),
      List("Refund", "Escalate", "Reply")
    )
    agrees[Shape]("$type", List(Circle(1), Rectangle(1, 2), Dot, Blank()), List("Circle", "rect", "Dot", "Blank"))
    agrees[Event]("kind", List(Click(1, 2), Typed("t", None, Nil), Idle), List("click", "Typed", "idle"))
    agrees[Payment](
      "$type",
      List(Payment.Card("1", Money(1, "EUR")), Payment.Voucher("v", None), Payment.Free),
      List("Card", "Voucher", "Free")
    )
  }

  it should "be required and come first in every case object" in {
    for (alternative <- SchemaOf[Event].toJsonSchema()("anyOf").arr if alternative("type") == ujson.Str("object")) {
      alternative("properties").obj.keys.head shouldBe "kind"
      alternative("required").arr.head shouldBe ujson.Str("kind")
    }
  }

  "A sum type's own schema" should "describe it by its @description, else its name" in {
    SchemaOf[Action].typeName shouldBe "Action"
    SchemaOf[Action].toJsonSchema()("description") shouldBe ujson.Str("What the support bot decides to do")
    SchemaOf[Shape].toJsonSchema()("description") shouldBe ujson.Str("Shape")
  }
}
