---
layout: page
title: Derived Schemas
parent: User Guide
nav_order: 28
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
| an `enum` or sealed hierarchy of singletons | `string` limited to the case names (a case's `@upickle.implicits.key`, where it has one) |

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

## Tool parameters

A tool's parameters are a schema too. `SchemaOf[A].definition` is the derived schema as `llm4s-core`'s schema model, which `ToolBuilder` takes as it is, and the handler reads the arguments with the same `ReadWriter`:

```scala
import org.llm4s.schema.*
import org.llm4s.toolapi.{ ToolBuilder, ToolFunction }
import org.llm4s.types.Result
import upickle.default.{ read, ReadWriter }

import scala.util.Try

@description("Where to look up the weather")
case class WeatherQuery(
  @description("City name, for example Paris") city: String,
  @description("Temperature unit; the default is Celsius") unit: Option[TemperatureUnit]
) derives SchemaOf, ReadWriter

enum TemperatureUnit derives SchemaOf, ReadWriter {
  case Celsius, Fahrenheit
}

val weatherTool: Result[ToolFunction[WeatherQuery, String]] =
  ToolBuilder[WeatherQuery, String]("get_weather", "Current weather in a city", SchemaOf[WeatherQuery].definition)
    .withHandler { args =>
      Try(read[WeatherQuery](args.params)).toEither.left.map(_.getMessage).map(query => s"Sunny in ${query.city}")
    }
    .buildSafe()
```

The tool is sent in strict mode, so every parameter is required and `unit` is a property that may be `null`. The module's tests check that the parameters the tool sends are the derived schema, and that the handler reads a call.

## Descriptions

The model sees a description for every property and for the object. `@description("...")` sets it: on a case class parameter it describes the field, on a case class, enum or sealed trait it describes the type. The text must be a string literal, because the annotation is read at compile time. Without it a field is described by its type's `@description` if it has one, and otherwise by its name, and a type by its name.

## How it agrees with uPickle

The schema describes what uPickle's derived `ReadWriter` reads and writes, so a reply that matches the schema reads back into your type. The module's tests check this in both directions for every supported shape: what uPickle writes satisfies the schema, and a document that satisfies the schema is read into the right value. The points where it matters:

- **Field names.** A property is named as uPickle writes the field, so `@upickle.implicits.key("user_name")` is honoured.
- **`Option`.** uPickle writes `None` as `null` and `Some(x)` as `x`, and the schema allows exactly that.
- **`BigDecimal`.** uPickle writes it as a string and cannot read a JSON number, so the schema says `string`.
- **Enums.** uPickle writes a case as its name, or as its `@upickle.implicits.key("...")` when the case (an enum case or a case object) has one, and the `enum` list in the schema holds exactly those strings.
- **`Long`.** uPickle writes a `Long` beyond 2^53 (9007199254740992) in either direction as a JSON *string*, because a JSON number that large loses precision in many readers; it reads both forms back. The schema says `integer`, so a value written that large does not match it. A model answering the schema writes a number, which reads back; if your values can be that large and you validate what uPickle writes, describe the field as a string yourself.
- **`BigDecimal` and `BigInt` text.** The schema says `string` and cannot say that only numeric text reads back (llm4s-core's schema model has no `pattern`), so give such a field a `@description` that asks for a number.
- **Defaults.** uPickle leaves out a field that equals its default when it writes, and reads a missing field as the default. The schema still lists the field: `completeStructured` sends the strict schema, in which every property is required (a model must answer every field), so a field with a default is asked for like any other.

## Strict mode

`completeStructured` asks for strict mode, in which every property is `required` and an `Option` is a property that may be `null` instead of being absent. That is what providers with native structured output expect. `SchemaOf[A].toJsonSchema(strict = false)` returns the relaxed form, in which an `Option` property is not listed in `required`.

## Types the module does not know

Another type needs a `given SchemaOf` of its own, written next to the `ReadWriter` that reads it. The derivation takes a hand-written given as it is and does not look inside the type, so this also works for a type it would otherwise refuse, such as a sealed hierarchy with fields or a recursive type, as long as the given describes what its `ReadWriter` writes and does not itself derive a type that leads back to the one being derived (see [Recursion](#recursion)). `SchemaOf.string` describes a type that is written as a JSON string, and `SchemaOf.stringEnum` one that is written as one of a fixed set of strings. The text you pass is what the model sees for a field of that type, unless the field has its own `@description`.

```scala
import java.time.Instant

given ReadWriter[Instant] = upickle.default.readwriter[String].bimap[Instant](_.toString, Instant.parse)
given SchemaOf[Instant]   = SchemaOf.string("ISO-8601 instant, for example 2026-10-08T09:30:00Z")

case class Meeting(title: String, starts: Instant) derives SchemaOf, ReadWriter
```

## What cannot be derived

These are refused at compile time, with a message that names the type:

- **`Map`.** `llm4s-core`'s `ObjectSchema` has a fixed set of properties and no way to describe the values of a map.
- **Recursive types** (a `Tree` with `children: List[Tree]`). They need a JSON Schema `$ref`, which the schema model cannot express. One case is caught only at run time: see [Recursion](#recursion).
- **Sealed hierarchies with fields** (`Circle(radius)` and `Rectangle(w, h)` under `Shape`). They need `oneOf`, which the schema model cannot express. Enums and sealed hierarchies of singletons are fine.
- **`Option` directly inside `Option`** (`Option[Option[Int]]`). uPickle writes it as a JSON array (`[]`, `[null]`, `[1]`), not as the value or `null`. An `Option` inside a `List` inside an `Option` is fine.
- **Tuples** (`(Int, String)`). uPickle writes them as JSON arrays of mixed types. Use a case class with named fields.
- **Named tuples** (`(a: Int, b: String)`). uPickle has no `ReadWriter` for them, so nothing could read the reply back. Use a case class.
- **A class that is not a case class**, and a field of a type nothing describes (a function, for instance).

`completeStructuredOf` also needs the type to be a case class, since the model answers with a JSON object: for an enum or another type it returns a `Left` before calling the model.

### Recursion

A recursive type is refused at compile time when the recursion runs through derived instances: a type that refers to itself, directly or through an `Option`, a collection or a generic case class; types that refer to each other and all `derives SchemaOf`; and a type whose own given is written as `SchemaOf.derived` of itself.

The compile-time check does not look inside any other hand-written given, so it cannot see a recursion that passes through one which itself calls `SchemaOf.derived`:

```scala
case class P(q: Option[Q])
case class Q(p: List[P])
object Schemas {
  given ps: SchemaOf[P] = SchemaOf.derived[P] // compiles: Q's given is hand-written
  given qs: SchemaOf[Q] = SchemaOf.derived[Q] // compiles: P's given is hand-written
}
```

This compiles, and making the instances still works: a derived instance does not look up its fields' instances until its schema is built, so two such givens never wait on each other's initialisation. Building the schema (`SchemaOf[P].toJsonSchema()` or `.definition`) throws an `IllegalStateException` on first use, and on every use after it, naming the type: `recursive type P: llm4s-schema-derivation cannot describe recursive types; core's schema model has no $ref. ...`. `completeStructuredOf[P]` returns the same message as a `Left(ValidationError)` without calling the model. It never hangs and never overflows the stack. The same shape is fine when the given that closes the loop describes the type by hand rather than deriving it, for example `given SchemaOf[Q] = SchemaOf.string("...")`; that is why the check cannot refuse every hand-written given that reaches back.

A derived schema has no length or range constraints. When you need `withRange` or `withLengthConstraints`, build that schema by hand as before; `completeStructured` takes both.

## Compile time

The derivation is Scala 3 `inline` code plus a small macro that reads annotations and rejects the shapes above. The macro walks each type it reaches once, so a type reached along many paths does not multiply the work. For a case class with 30 fields, one measurement of an incremental compile of the single file took about 8 seconds with `derives SchemaOf, ReadWriter` against about 5 seconds with `derives ReadWriter` alone, so roughly 3 seconds more, on a busy machine. Measure your own project if compile time matters to you.
