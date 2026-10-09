---
layout: page
title: Derived Schemas
parent: User Guide
nav_order: 24
---

# Derived Schemas
{: .no_toc }

Write the type once and let `llm4s-schema-derivation` build the JSON schema for structured output from it.
{: .fs-6 .fw-300 }

> **Experimental.** The module is new and not part of the 1.0 frozen surface. It is not published yet: until a release carries it, build it from a checkout (`sbt schemaDerivation/publishLocal`).

1. TOC
{:toc}

---

## Why

`completeStructured` takes an `ObjectSchema` that you build by hand, so every field is written twice, once in the case class and once in the schema, and a mismatch only shows up when a model answers. `llm4s-schema-derivation` derives the schema from the case class with Scala 3's `derives`, so the two cannot drift.

It layers on the public API: it builds the same `ObjectSchema` and calls the same `completeStructured`. Nothing in `llm4s-core` changes, and the module adds no dependency.

## Add the module

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-schema-derivation" % "{{ site.data.project.latest_release }}"
```

## A first call

```scala
import org.llm4s.schema.*
import upickle.default.ReadWriter

@description("An invoice extracted from text")
case class Invoice(
  @description("Name of the vendor or supplier") vendor: String,
  @description("Total invoice amount as a decimal number") amount: Double,
  @description("ISO 4217 currency code, e.g. USD, EUR, GBP") currency: String
) derives SchemaOf, ReadWriter
```

`derives SchemaOf` gives the type its schema, and `derives ReadWriter` (uPickle) is what reads the model's answer, as with `completeStructured`. With an `LLMClient` and a `Conversation` in scope, one call replaces the hand-built schema:

```scala
val invoice: Result[Invoice] = client.completeStructuredOf[Invoice](conversation)
```

`Result` is `org.llm4s.types.Result`. `completeStructuredOf` is an extension method on `LLMClient`, in `org.llm4s.schema`. It sets the same response format as `completeStructured`, so the provider enforces the schema where it can (OpenAI, Gemini) and falls back to an instruction in the prompt where it cannot (Anthropic).

To see the schema that is sent:

```scala
val json = SchemaOf[Invoice].toJsonSchema()
```

which is

```json
{
  "type": "object",
  "description": "An invoice extracted from text",
  "properties": {
    "vendor": { "type": "string", "description": "Name of the vendor or supplier" },
    "amount": { "type": "number", "description": "Total invoice amount as a decimal number" },
    "currency": { "type": "string", "description": "ISO 4217 currency code, e.g. USD, EUR, GBP" }
  },
  "required": ["vendor", "amount", "currency"],
  "additionalProperties": false
}
```

This is exactly the schema that `Schema.object[Invoice](...)` with three `withRequiredField` calls builds by hand; the module's tests compare the two.

## What is derived

| Scala type | JSON schema |
|---|---|
| `String` | `string` |
| `Int`, `Long` | `integer` |
| `Double`, `Float` | `number` |
| `Boolean` | `boolean` |
| `BigDecimal`, `BigInt` | `string` (uPickle writes and reads them as JSON strings) |
| `Option[A]` | the schema of `A`, also allowing `null`; the property is not `required` unless strict (below) |
| `List[A]`, `Vector[A]`, `Seq[A]`, `Set[A]` | `array` of the schema of `A` |
| a case class | `object` with one property per field, in declaration order, `additionalProperties: false` |
| an `enum` or sealed hierarchy of singletons | `string` limited to the case names |

Case classes nest, and they can be generic (`Box[Int]` is described for `Int`).

```scala
case class Contact(
  name: String,
  email: Option[String],
  tags: List[String] = Nil
) derives SchemaOf, ReadWriter

enum Priority derives SchemaOf, ReadWriter {
  case Low, Medium, High
}
```

## Descriptions

The model sees a description for every property and for the object. `@description("...")` sets it: on a case class parameter it describes the field, on a case class, enum or sealed trait it describes the type. The text must be a string literal, because the annotation is read at compile time. Without it a field is described by its type's `@description` if it has one, and otherwise by its name, and a type by its name.

## How it agrees with uPickle

The schema describes what uPickle's derived `ReadWriter` reads and writes, so a reply that matches the schema reads back into your type. The module's tests check this in both directions for every supported shape: what uPickle writes satisfies the schema, and a document that satisfies the schema is read into the right value. The points where it matters:

- **Field names.** A property is named as uPickle writes the field, so `@upickle.implicits.key("user_name")` is honoured.
- **`Option`.** uPickle writes `None` as `null` and `Some(x)` as `x`, and the schema allows exactly that.
- **`BigDecimal`.** uPickle writes it as a string and cannot read a JSON number, so the schema says `string`.
- **Enums.** uPickle writes a case as its name, which is the `enum` list in the schema.
- **Defaults.** uPickle leaves out a field that equals its default when it writes, and reads a missing field as the default. The schema still lists the field: `completeStructured` sends the strict schema, in which every property is required (a model must answer every field), so a field with a default is asked for like any other.

## Strict mode

`completeStructured` asks for strict mode, in which every property is `required` and an `Option` is a property that may be `null` instead of being absent. That is what providers with native structured output expect. `SchemaOf[A].toJsonSchema(strict = false)` returns the relaxed form, in which an `Option` property is not listed in `required`.

## Types the module does not know

Another type needs a `given SchemaOf` of its own, written next to the `ReadWriter` that reads it. `SchemaOf.string` describes a type that is written as a JSON string, and `SchemaOf.stringEnum` one that is written as one of a fixed set of strings. The text you pass is what the model sees for a field of that type, unless the field has its own `@description`.

```scala
import java.time.Instant

given ReadWriter[Instant] = upickle.default.readwriter[String].bimap[Instant](_.toString, Instant.parse)
given SchemaOf[Instant]   = SchemaOf.string("ISO-8601 instant, for example 2026-10-08T09:30:00Z")

case class Meeting(title: String, starts: Instant) derives SchemaOf, ReadWriter
```

## What cannot be derived

These are refused at compile time, with a message that names the type:

- **`Map`.** `llm4s-core`'s `ObjectSchema` has a fixed set of properties and no way to describe the values of a map.
- **Recursive types** (a `Tree` with `children: List[Tree]`). They need a JSON Schema `$ref`, which the schema model cannot express.
- **Sealed hierarchies with fields** (`Circle(radius)` and `Rectangle(w, h)` under `Shape`). They need `oneOf`, which the schema model cannot express. Enums and sealed hierarchies of singletons are fine.
- **A class that is not a case class**, and a field of a type nothing describes (a function, for instance).

`completeStructuredOf` also needs the type to be a case class, since the model answers with a JSON object: for an enum or another type it returns a `Left` before calling the model.

A derived schema has no length or range constraints. When you need `withRange` or `withLengthConstraints`, build that schema by hand as before; `completeStructured` takes both.

## Compile time

The derivation is Scala 3 `inline` code plus a small macro that reads annotations and rejects the shapes above. For a case class with 30 fields, one measurement of an incremental compile of the single file took about 8 seconds with `derives SchemaOf, ReadWriter` against about 5 seconds with `derives ReadWriter` alone, so roughly 3 seconds more, on a busy machine. Measure your own project if compile time matters to you.
