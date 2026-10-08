---
layout: page
title: Structured Output
parent: User Guide
nav_order: 6
---

# Structured Output with completeStructured
{: .no_toc }

Ask a model for a typed value instead of free text, and know what you get back when it does not comply.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## 1. What it does

`LLMClient.completeStructured[A]` sends a conversation, asks the provider for JSON that matches a schema,
and returns the reply as a value of type `A`:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.{ macroRW, ReadWriter }

final case class Invoice(vendor: String, amount: Double, currency: String)
object Invoice { implicit val rw: ReadWriter[Invoice] = macroRW }

val invoiceSchema =
  Schema
    .`object`[Invoice]("An invoice extracted from text")
    .withRequiredField("vendor", Schema.string("Name of the vendor or supplier"))
    .withRequiredField("amount", Schema.number("Total invoice amount as a decimal number"))
    .withRequiredField("currency", Schema.string("ISO 4217 currency code, e.g. USD, EUR, GBP"))

def extractInvoice(client: LLMClient, text: String): Result[Invoice] =
  client.completeStructured[Invoice](
    Conversation(Seq(UserMessage(s"Extract the invoice from this text:\n$text"))),
    invoiceSchema
  )
```

The snippets below build on these definitions (`Invoice`, `invoiceSchema` and `extractInvoice`) and import
what else they use.

The result is a `Result[Invoice]`: `Right(Invoice("Acme Supplies Ltd", 1250.0, "GBP"))` for a reply of
`{"vendor":"Acme Supplies Ltd","amount":1250.0,"currency":"GBP"}`. Other replies that can be deserialised
as `Invoice` also return `Right`; replies that cannot be deserialised return `Left` (section 7).
A runnable version that builds the client from configuration is `StructuredOutputExample`:

```bash
sbt "samples/runMain org.llm4s.samples.basic.StructuredOutputExample"
```

Use it for extraction, classification, routing and any step whose output a program, not a person, reads.
Streaming is not part of it: `completeStructured` makes one non-streaming `complete` call.

## 2. Describing the type

Two things describe `A`, and they have to agree:

- **The schema**, an `ObjectSchema[A]` built with `Schema.\`object\``, `withRequiredField` and `withProperty`.
  It is what the provider is shown. The same builders define tool parameters; see
  [Agents and tools](agents/index.md).
- **A uPickle reader**, an implicit `upickle.default.Reader[A]` (a `ReadWriter` from `macroRW` is the usual way).
  It is what turns the reply into `A`.

Nothing checks that the two match, and some mismatches are never reported. A field the case class has but
the schema lacks fails to parse when the reply omits it (section 7), unless the field has a default, which
the reader fills in silently. A field the schema has but the case class lacks is worse: the provider returns
it, the reader ignores it as an extra key, and the result is `Right(A)` with that value dropped. Keep the
schema and the case class in step by hand, and test the pair on a sample reply.

The schema is a closed object: `additionalProperties` is `false` unless you pass `true` to `ObjectSchema`.

## 3. Fenced and prose-wrapped replies

Models often wrap JSON in a markdown fence or write a sentence around it. Before parsing, the reply is
normalised: a surrounding code fence is stripped, and if the rest is still not JSON, the first balanced
`{...}` or `[...]` that parses as JSON is taken from it. Both of these give the same `Invoice`:

````text
```json
{"vendor":"Acme Supplies Ltd","amount":1250.0,"currency":"GBP"}
```

Sure! Here is the invoice you asked for: {"vendor":"Acme Supplies Ltd","amount":1250.0,"currency":"GBP"}. Let me
know if you need anything else.
````

The recovery is bounded: it tries at most 16 places where a `{` or `[` starts, so a long reply full of braces
that are not JSON is not searched forever. If it finds nothing, the trimmed text goes on to the parser, which
reports the failure.

That function, `LLMClient.extractJson`, is internal to llm4s (`private[llmconnect]`). You cannot call it, and
code that calls `complete` and parses the reply itself gets no recovery (section 6).

## 4. What is sent, and why every property is required

`completeStructured` turns your schema into JSON Schema with `strict = true`, wraps it in
`ResponseFormat.JsonSchema(schema)` and sets that as the `responseFormat` of the call.

`strict = true` lists **every** property under `required`, including a property you declared optional:

```scala
import org.llm4s.toolapi.Schema

val note =
  Schema
    .`object`[Map[String, String]]("A note")
    .withRequiredField("vendor", Schema.string("Vendor"))
    .withProperty(Schema.property("note", Schema.string("Free-text note"), required = false))

note.toJsonSchema(strict = true)("required")  // ["vendor", "note"]
note.toJsonSchema(strict = false)("required") // ["vendor"]
```

So `required = false` does nothing through `completeStructured`: the provider is told `note` is required, and a
model that follows the schema always includes it. If you need a value that may be absent, make the field's
type allow it (`Schema.nullable(...)`, with an `Option` in the case class) instead of leaving the field out.

The `name` and `strict` fields of the format are the defaults, `"response"` and `true`. They are not parameters
of `completeStructured`; section 6 shows how to set them.

## 5. Options

`completeStructured` keeps every option you pass and replaces only `responseFormat`:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.types.Result

def deterministic(client: LLMClient, text: String): Result[Invoice] =
  client.completeStructured[Invoice](
    Conversation(Seq(UserMessage(text))),
    invoiceSchema,
    CompletionOptions().withTemperature(0.0).withMaxTokens(256)
  )
```

Temperature, token limit and the rest are passed to `complete` as given. The provider client may still
adjust them for the model, as it does for any `complete` call: OpenAI reasoning models, for example, omit
sampling parameters, and an option the model does not support can be dropped. A `responseFormat` you set
yourself is overwritten by the schema's, so `completeStructured` is not a way to request plain JSON mode; call `complete`
with `ResponseFormat.Json` for that.

## 6. Choosing the schema name or strictness

For a name other than `"response"`, or `strict = false`, call `complete` with your own format and read the
reply yourself:

```scala
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ResponseFormat, UserMessage }
import org.llm4s.types.Result

def withCustomFormat(client: LLMClient, text: String): Result[Invoice] = {
  val format = ResponseFormat.JsonSchema(
    invoiceSchema.toJsonSchema(strict = false),
    name = "invoice",
    strict = false
  )
  for {
    completion <- client.complete(Conversation(Seq(UserMessage(text))), CompletionOptions().withResponseFormat(format))
    // No fence or prose recovery on this path: the reply has to be bare JSON.
    invoice <- scala.util.Try(upickle.default.read[Invoice](completion.content)).toEither.left.map(e =>
      ValidationError("invoice", e.getMessage)
    )
  } yield invoice
}
```

`name` and `strict` only mean something to providers that have an equivalent (section 8). Where there is none,
as with Ollama, they are ignored.

## 7. When the reply is not usable

A reply that cannot become an `A` is a `ValidationError` whose `field` is `"structured_output"`:

| The reply | `error.message` contains |
|---|---|
| text with no JSON in it | `Response is not valid JSON` |
| the JSON value `null` | `Response does not match expected schema: got JSON null` |
| JSON of the wrong shape (an array for an object, a missing field, a wrong type) | `Response does not match expected schema` |

The reply is deserialised, not validated against the schema. A missing field or a wrong type fails because
the uPickle reader for `A` fails on it, but a constraint the reader does not check is not checked at all: an
enum, a numeric or string bound, or `additionalProperties = false` (extra keys are ignored). A reply that
breaks one of those still comes back as `Right(A)`, which matters most where the provider does not enforce
the schema (section 8). Check such constraints on the result yourself.

A failed provider call is a different error, returned unchanged (a `NetworkError`, `RateLimitError` and so on;
see [Error Handling](error-handling.md)). Telling the two apart is a pattern match:

```scala
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

def describe(result: Result[Invoice]): String = result match {
  case Right(invoice)                                                     => s"ok: ${invoice.vendor}"
  case Left(error: ValidationError) if error.field == "structured_output" => s"model reply rejected: ${error.message}"
  case Left(error)                                                        => s"call failed: ${error.message}"
}
```

A rejected reply is often worth one retry. A failed call is handled by whatever retry you already use
(a `ReliableClient` retries transient provider errors, but never a `ValidationError`):

```scala
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result

def retryOnce(client: LLMClient, text: String): Result[Invoice] =
  extractInvoice(client, text) match {
    case Left(error: ValidationError) if error.field == "structured_output" => extractInvoice(client, text)
    case other                                                                => other
  }
```

`ValidationError`s are not marked recoverable on their own, so decide per call site whether a retry is
reasonable. A model that refuses, or answers in prose again, will fail again.

## 8. What each provider does with the schema

The format is a request, not a guarantee. This is what each client does with `ResponseFormat.JsonSchema`,
taken from its code:

| Provider | What is sent | Enforced? |
|---|---|---|
| OpenAI, Azure OpenAI | `response_format` of type `json_schema`, with your `name` and `strict` | Yes: the provider constrains generation to the schema |
| Requesty | The same OpenAI `json_schema` shape, with your `name` and `strict` | Depends on the routed model: Requesty is a multi-provider router, so enforcement is up to the backend model or fallback it selects |
| openai-compatible: generic, DeepSeek, Z.ai, OpenRouter, Mistral | The same OpenAI `json_schema` shape, with your `name` and `strict` | The server decides: a server that ignores `response_format` returns unconstrained text |
| Cohere (through its OpenAI-compatibility API) | `response_format` of type `json_object` carrying the `schema`; `name` and `strict` are not sent | Cohere's own mechanism |
| Gemini, Vertex AI | `responseMimeType: application/json` and `responseSchema` set to the schema | Yes, by Gemini |
| Ollama | The schema as the top-level `format` field of `/api/chat` | Yes, on Ollama 0.5 or later; an older server rejects or ignores it. `name` and `strict` are ignored |
| Anthropic | **Nothing is enforced.** The schema is appended to the system prompt as an instruction to answer with JSON only | **No**: best effort. Expect section 7 errors more often |
| watsonx, AWS Bedrock | **Nothing.** These clients do not read `responseFormat` | **No**: the model sees only your conversation |

For the last row, `completeStructured` still parses whatever comes back, so a model that happens to answer
in JSON works and one that does not gives a `ValidationError`. Ask for JSON in the prompt yourself when you
use these providers.

## 9. Limits and gotchas

- **The model registry can switch the schema off.** The OpenAI, Anthropic, Gemini, Vertex AI and Bedrock
  clients check the options against the model registry first. When the registry records that a model does not
  support response schemas, the schema is dropped without an error (and with it Anthropic's prompt
  instruction), and `completeStructured` then parses an unconstrained reply.
- **Every property is required** (section 4), whatever you declared.
- **One call, no streaming, no repair.** There is no automatic retry inside `completeStructured`.
- **The content goes to the provider.** The conversation and the schema are sent to whichever provider the client
  talks to, like any other completion.
- **The schema is JSON Schema, not your case class.** A type uPickle can read but the schema cannot describe
  (a sealed hierarchy, a map with free keys) needs a schema that matches what the model should produce, and a
  reader that accepts it.

## Related

- [Error Handling](error-handling.md): every `LLMError`, and how to combine results.
- [Providers](providers.md): configuring each provider.
- `StructuredOutputExample` in `modules/samples`: the runnable version of section 1.
