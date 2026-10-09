---
layout: page
title: JSON Libraries
parent: User Guide
nav_order: 17
---

# Using LLM4S with circe, play-json and zio-json
{: .no_toc }

LLM4S reads and writes JSON with uPickle and its `ujson` AST. This page shows how an application that already uses circe, play-json or zio-json passes its own types in and gets them back, without writing a second set of codecs.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Where ujson shows in the API

Three places are where your JSON library meets LLM4S:

| Where | What LLM4S takes or returns |
|---|---|
| `LLMClient.completeStructured[A]` | an implicit `upickle.default.Reader[A]` for the type you want back |
| Tool handlers and results | `SafeParameterExtractor.params` is a `ujson.Value`; `ToolFunction.execute` takes and returns a `ujson.Value`; a tool's result type needs a uPickle `ReadWriter` (use `ujson.Value` itself to return a document you built) |
| Agent interrupts and events | `Agent.streamResume` takes `Map[InterruptId, ujson.Value]`; `AgentResult.edit` takes the edited `ujson.Value`; the `ToolCallStarted` event carries its `arguments` as a `ujson.Value` |

So there are two jobs: turn a `ujson.Value` into your library's document (and back), and turn your library's decoder into the `Reader` that `completeStructured` asks for.

The examples use these types: a nested object, an `Option`, a collection and the three numeric kinds.

```scala
final case class Address(city: String, zip: Option[String])
final case class Order(id: Int, total: Double, address: Address, tags: List[String], note: Option[String])

val addressSchema: ObjectSchema[Address] = Schema
  .`object`[Address]("Where to deliver")
  .withRequiredField("city", Schema.string("The city"))
  .withProperty(PropertyDefinition("zip", Schema.string("The postal code"), required = false))

val orderSchema: ObjectSchema[Order] = Schema
  .`object`[Order]("An order")
  .withRequiredField("id", Schema.integer("The order number"))
  .withRequiredField("total", Schema.number("The total"))
  .withRequiredField("address", addressSchema)
  .withRequiredField("tags", Schema.array("Labels", Schema.string("A label")))
  .withProperty(PropertyDefinition("note", Schema.string("A note"), required = false))
```

## What to add

Nothing here goes into LLM4S itself; add the library you use to your own build. uPickle's `ujson-circe` is the bridge for circe. **There is no Scala 3 build of `ujson-play`** (on Maven Central it exists for Scala 2.11 to 2.13 only, checked on 2026-10-08), and zio-json has no bridge at all, so those two cross as text. The versions below are the ones this page was run with.

```sbt
// circe: ujson-circe is uPickle's own bridge, built for Scala 3. Keep its version equal to the ujson that LLM4S uses.
libraryDependencies ++= Seq(
  "io.circe"    %% "circe-core"  % "0.14.17",
  "com.lihaoyi" %% "ujson-circe" % "4.4.3"
)

// play-json (no bridge needed: it crosses as text)
libraryDependencies += "org.playframework" %% "play-json" % "3.0.6"

// zio-json (no bridge needed: it crosses as text)
libraryDependencies += "dev.zio" %% "zio-json" % "1.0.0"
```

**zio-json 1.1.0 needs a Scala 3.9 compiler.** It depends on `scala3-library` 3.9.0, and a project compiled with Scala 3.7.1 (the version LLM4S is built with) cannot read that library: the compiler stops with "Forward incompatible TASTy file ... produced by Scala 3.9.0". Use 1.0.0 (built against Scala 3.3) unless your project is on Scala 3.9 or later. zio-json 1.0.0 also needs zio 2.1.26 or newer, which sbt resolves for you. <!-- doc-support: ignore -->

## circe

### Converting a document

`ujson.transform` visits a `ujson.Value` with circe's visitor and builds a `Json`, and `CirceJson.transform` goes the other way. Nothing is rendered to text on the way.

```scala
import io.circe.Json
import ujson.circe.CirceJson

def toCirce(value: ujson.Value): Json  = ujson.transform(value, CirceJson)
def fromCirce(json: Json): ujson.Value = CirceJson.transform(json, ujson.Value)
```

### Using your Decoder with completeStructured

`completeStructured` wants a uPickle `Reader[A]`. A `Reader` can be built from the `ujson.Value` reader, so any circe `Decoder` becomes one. Here the codecs come from circe's own derivation:

```scala
import io.circe.{ Decoder, Encoder }
import io.circe.syntax._

given Decoder[Address] = Decoder.derived
given Decoder[Order]   = Decoder.derived
given Encoder[Address] = Encoder.AsObject.derived
given Encoder[Order]   = Encoder.AsObject.derived
```

```scala
import upickle.default.{ reader, Reader }

def circeReader[A](using decoder: Decoder[A]): Reader[A] =
  reader[ujson.Value].map { value =>
    decoder.decodeJson(toCirce(value)).fold(error => throw upickle.core.Abort(error.getMessage), identity)
  }
```

Then pass it where the `Reader` goes:

```scala
def orderViaCirce(client: LLMClient, conversation: Conversation): Result[Order] =
  client.completeStructured[Order](conversation, orderSchema)(using circeReader[Order])
```

`Option` fields can be `null` or absent, and nested objects decode as circe decodes them. The schema you pass to `completeStructured` is still an LLM4S `ObjectSchema` (circe does not derive it; deriving one from a case class is tracked in [#1472](https://github.com/llm4s/llm4s/issues/1472)), and it reaches the provider exactly as with a uPickle `Reader`.

**When decoding fails.** The `Reader` throws `upickle.core.Abort` with the decoder's message. `completeStructured` catches anything thrown while reading and returns `Left(ValidationError("structured_output", "Response does not match expected schema: ..."))`, the same error the uPickle path gives, so your error handling does not change (see [Error Handling](error-handling)). A reply that is JSON `null` is refused and never comes back as `Right(null)`.

### Tools that use circe

A tool handler receives its arguments as `extractor.params`, a `ujson.Value`. Decode it with circe, and return a `ujson.Value` built from your result's encoder:

```scala
final case class AddArgs(a: Int, b: Int)
final case class AddResult(sum: Long)

given Decoder[AddArgs]   = Decoder.derived
given Encoder[AddResult] = Encoder.AsObject.derived

val addSchema = Schema
  .`object`[Map[String, Any]]("Two integers")
  .withRequiredField("a", Schema.integer("The first"))
  .withRequiredField("b", Schema.integer("The second"))

val addTool = ToolBuilder[Map[String, Any], ujson.Value]("add", "Adds two integers", addSchema)
  .withHandler { extractor =>
    toCirce(extractor.params).as[AddArgs] match {
      case Right(args) => Right(fromCirce(AddResult(args.a.toLong + args.b).asJson))
      case Left(error) => Left(error.getMessage)
    }
  }
  .buildSafe()
```

A value that does not fit the case class (`"a": "two"`, or a missing field) is returned to the model as a tool error, not thrown. See [Built-in Tools](builtin-tools) for how tool errors reach the model.

### Answers and edits to an interrupted run

Wherever LLM4S takes a `ujson.Value` (the `answers` of `Agent.streamResume`, the `arguments` of `AgentResult.edit`), build it from a circe `Json` with the same conversion:

```scala
val answer: ujson.Value = fromCirce(Json.obj("approved" -> Json.fromBoolean(true)))
```

## play-json

play-json reads and writes text, so the document crosses as a string: `value.render()` is compact JSON, and `PlayJson.parse` reads it. This costs one copy of the document each way; it is fine for tool arguments and structured answers, which are small.

```scala
import play.api.libs.json.{ JsError, JsSuccess, Json as PlayJson, JsValue, Reads }

def toPlay(value: ujson.Value): JsValue  = PlayJson.parse(value.render())
def fromPlay(json: JsValue): ujson.Value = ujson.read(PlayJson.stringify(json))

def playReader[A](using reads: Reads[A]): Reader[A] =
  reader[ujson.Value].map { value =>
    reads.reads(toPlay(value)) match {
      case JsSuccess(decoded, _) => decoded
      case JsError(errors)       => throw upickle.core.Abort(errors.toString)
    }
  }

given Reads[Address] = PlayJson.reads[Address]
given Reads[Order]   = PlayJson.reads[Order]
```

```scala
def orderViaPlay(client: LLMClient, conversation: Conversation): Result[Order] =
  client.completeStructured[Order](conversation, orderSchema)(using playReader[Order])
```

A document that does not fit the `Reads` gives the same `ValidationError("structured_output", ...)` as above.

## zio-json

zio-json decodes straight from text, so the `Reader` renders the document once and decodes it. To hand a zio-json value to LLM4S, encode it to text and read it back as a `ujson.Value`. Note that zio-json leaves a `None` field out of the document it writes, where circe writes `null`; every one of the three libraries reads both forms back as `None`, so a model may answer either way.

```scala
import zio.json.{ DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder }
import zio.json.{ DecoderOps, EncoderOps }

given JsonDecoder[Address] = DeriveJsonDecoder.gen[Address]
given JsonDecoder[Order]   = DeriveJsonDecoder.gen[Order]
given JsonEncoder[Address] = DeriveJsonEncoder.gen[Address]
given JsonEncoder[Order]   = DeriveJsonEncoder.gen[Order]

def zioReader[A](using decoder: JsonDecoder[A]): Reader[A] =
  reader[ujson.Value].map { value =>
    value.render().fromJson[A].fold(message => throw upickle.core.Abort(message), identity)
  }

def fromZio[A](value: A)(using encoder: JsonEncoder[A]): ujson.Value = ujson.read(value.toJson)
```

```scala
def orderViaZio(client: LLMClient, conversation: Conversation): Result[Order] =
  client.completeStructured[Order](conversation, orderSchema)(using zioReader[Order])
```

## Numbers

LLM4S parses a model's reply into the `ujson` AST first, and a `ujson` number is a double. Doubles and ordinary integers (up to 2^53) round trip unchanged. **A whole number above 2^53 is rounded to the nearest double before any JSON library sees it.** That is `ujson`'s own parse, so it is not a property of circe, play-json or zio-json:

```scala
val big = 9007199254740993L // 2^53 + 1: the first integer a double cannot hold

val parsed: Long = ujson.read(s"""{"id":$big}""")("id").num.toLong
```

The first integer a double cannot hold, 2^53 + 1 (9007199254740993), comes back as 9007199254740992, and a circe `Decoder[Long]` then reads the rounded value. If a model has to return or receive large identifiers (snowflake ids, database keys above 9 quadrillion), make them strings in the schema and the case class, and convert in your own code. This rounding is a known limitation, tracked in [#1597](https://github.com/llm4s/llm4s/issues/1597).

## What this page does not cover

- **A bridge module.** No `llm4s-circe` module is published. The conversion is two lines and the `Reader` is five, as shown above, so a module would add a dependency and a maintenance promise for little. Its main value would be one place for the error mapping, and only circe has a Scala 3 bridge, so a module could not treat the three libraries alike. If you would use one, say so on [#1458](https://github.com/llm4s/llm4s/issues/1458).
- **Deriving tool or response schemas** from your case classes. The schemas are still built with LLM4S's `Schema` (issue [#1472](https://github.com/llm4s/llm4s/issues/1472)).
- **Streaming events.** The event fields that are `ujson.Value`s, such as the `arguments` of `ToolCallStarted`, convert the same way.

## How this page is checked

Every Scala block on this page is a snippet of `JsonLibrariesGuideSpec` in the `llm4s-samples` tests, which compiles and runs them against the real libraries at the versions in the `sbt` block above, using a scripted client in place of a model. The same spec fails if a block here differs from its snippet or a version here differs from the build's.
