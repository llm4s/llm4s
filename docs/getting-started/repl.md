---
layout: page
title: Try llm4s in the REPL
description: "One import for the Scala CLI REPL, Metals worksheets and scripts: ask, extract, chat, history and lastUsage, with an offline session that needs no API key."
search_title: "Try LLM4S in the Scala REPL, a worksheet or a script"
parent: Getting Started
nav_order: 15
---

# Try llm4s in the REPL
{: .no_toc }

Ask a model a question in one line, from the Scala CLI REPL, a Metals worksheet or a script.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

`llm4s-repl` puts a handful of helpers behind one import: `ask` streams an answer to the console, `extract` reads a
typed value out of text, `chat()` is a small read-eval loop, and `history` and `lastUsage` show the conversation and
what the last call cost. A failed call prints the error and its troubleshooting hint, never a stack trace.

It is for trying things out, not for production code: the helpers print to the console and keep the conversation
in memory, and no other llm4s module depends on `llm4s-repl`, so it never reaches an application unless you add it.
It is experimental. For an application, use `LLMClient` and the [agent](../guide/agents/) directly.

## The dependency

`llm4s-repl` is new: it is published from the first release after {{ site.data.project.latest_release }}. It depends on
`llm4s-core`, `llm4s-schema-derivation` (for `extract`) and `llm4s-testkit` (for the offline session). Add the
provider module you want to call, as for any llm4s application ([Installation](installation)).

In a Scala CLI script or worksheet:

```
//> using dep org.llm4s::llm4s-repl:<version>
//> using dep org.llm4s::llm4s-openai:<version>
```

In sbt, for the `console` task:

```
libraryDependencies += "org.llm4s" %% "llm4s-repl" % llm4sVersion
```

## A session

Every helper takes a `Session` from a context parameter, so you set it once:

```scala
import org.llm4s.repl.*
import org.llm4s.testkit.Reply

// The section `llm4s.providers.provider` names in application.conf
val fromConfig = Session.fromConfig()
// A named section, llm4s.providers.openai-main
val named = Session.named("openai-main")
// No key and no network: the first call gets the first reply, and so on
given Session = Session.offline(Reply.text("Covariance lets a List[Cat] be used where a List[Animal] is expected."))
```

`Session.of(client)` wraps a client you built yourself. A session whose configuration does not load is still a
session: `session.failure` holds the error, and every helper prints it. `withSystemPrompt`, `withOptions` (any
`CompletionOptions`) and `withMaxToolSteps` return a new session with an empty history.

## Ask, extract, chat

```scala
import org.llm4s.repl.*
import org.llm4s.testkit.Reply

given Session = Session.offline(
  Reply.text("Covariance lets a List[Cat] be used where a List[Animal] is expected.").withUsage(
    org.llm4s.llmconnect.model.TokenUsage(12, 14, 26)
  )
)

val answer = ask("Explain variance in Scala 3 in one sentence") // streams the answer, then returns it
history.show                                                    // the conversation so far
lastUsage                                                       // Some(scripted-model: 12 prompt + 14 completion = 26 tokens, 3 ms, cost unknown)
```

`ask` returns an `Outcome[String]`: `Outcome.Done(text)`, which the REPL prints as the text, or
`Outcome.Failed(error)`, printed as `error: <kind>: <message>` with the hint llm4s attaches to configuration,
authentication, rate-limit and not-found errors. `answer.getOrElse("")`, `answer.toOption` and `answer.toResult` give
the value. Each question and answer is added to the history, so the next `ask` carries on the conversation.

`extract` asks for a typed value, with the JSON schema derived from the type (see
[Derived Schemas](../guide/derived-schemas)):

```scala
import org.llm4s.repl.*
import org.llm4s.schema.SchemaOf
import org.llm4s.testkit.Reply
import upickle.default.ReadWriter

case class Invoice(vendor: String, amount: Double, currency: String) derives SchemaOf, ReadWriter

given Session = Session.offline(Reply.text("""{"vendor":"ACME","amount":12.5,"currency":"EUR"}"""))

val invoice = extract[Invoice]("ACME, 12.50 EUR, two widgets") // Done(Invoice(ACME,12.5,EUR))
```

`chat()` reads lines from the console and asks each one; `:history` prints the conversation, `:reset` forgets it and
`:q` leaves.

```
scala> chat()
Chatting with default provider, gpt-4o-mini. :q leaves, :history shows the conversation, :reset forgets it.
you> What is a monad?
llm> A monad is ...
you> :q
```

## Tools

Give `ask` a `ToolRegistry` and it runs the tools the model calls, printing each call and its result:

```scala
import org.llm4s.repl.*
import org.llm4s.testkit.Reply
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }

val weather = ToolBuilder[Map[String, Any], ujson.Value](
  "get_weather",
  "The weather in a city",
  Schema.`object`[Map[String, Any]]("Arguments").withProperty(Schema.property("city", Schema.string("The city")))
).withHandler(args => args.getString("city").map(city => ujson.Obj("city" -> city, "temperature" -> 21)))
  .buildSafe()

given Session = Session.offline(
  Reply.toolCall("get_weather", """{"city":"Paris"}"""),
  Reply.text("It is 21 degrees in Paris.")
)

weather.foreach(tool => ask("Weather in Paris?", tools = new ToolRegistry(Seq(tool))))
// -> get_weather({"city":"Paris"})
// <- {"city":"Paris","temperature":21}
// It is 21 degrees in Paris.
```

## Scripts: strict mode

A script usually wants to stop at the first failure. The helpers of `strict` return a `Result` and print no error:

```scala
import org.llm4s.repl.*
import org.llm4s.testkit.Reply

given Session = Session.offline(Reply.text("Paris."), Reply.text("About two million."))

val answers = for {
  capital    <- strict.ask("Capital of France?")
  population <- strict.ask(s"Population of $capital?")
} yield population
```

## Where to run it

- **The Scala CLI REPL**: `scala-cli repl --dep org.llm4s::llm4s-repl:<version> --dep org.llm4s::llm4s-openai:<version>`,
  then `import org.llm4s.repl.*` and `given Session = Session.fromConfig()`. Scala CLI reads `application.conf` from
  a resource directory (`--resource-dir resources`).
- **A Metals worksheet**: a `*.worksheet.sc` file with the `//> using dep` lines above. Metals shows each value
  beside its line; `ask` also prints the answer.
- **A Scala CLI script**: a `*.sc` file, run with `scala-cli run hello.sc`.
- **Almond notebooks** are tracked in [#2296](https://github.com/llm4s/llm4s/issues/2296); this page will link them
  once they exist.

The repository's [`examples/repl`](https://github.com/llm4s/llm4s/tree/main/examples/repl) has a script and a
worksheet that run against `Session.offline`; CI runs both with Scala CLI against the library built from the
branch, with no API key.
