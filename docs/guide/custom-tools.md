---
layout: page
title: Custom Tools
description: "Write a tool for an LLM4S agent with ToolBuilder: declare parameters, read arguments safely, return a result or error, and test it without a model."
search_title: "Build custom LLM tools in Scala with ToolBuilder"
parent: Agents
nav_order: 213
grand_parent: User Guide
---

# Build Your Own Tool
{: .no_toc }

Write a tool with {% include api.html t="org.llm4s.toolapi.ToolBuilder" %}: declare its parameters, read them safely, return a result or an error, and test it without a model.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

The [built-in tools](builtin-tools) are written with `ToolBuilder`, the same way yours is, so this page is the how-to
for the thing most users do first: write a tool. Everything here is in `llm4s-core` (`org.llm4s.toolapi`); running
the tool in an agent needs `llm4s-agent`.

## 1. Anatomy of a tool

A tool is a {% include api.html t="org.llm4s.toolapi.ToolFunction" %} with four parts, and `ToolBuilder` assembles them:

- a **name**, which is how the model calls it (`convert_temperature`);
- a **description**, which is the model's only guide to when to call it;
- a **parameter schema**, built with the `Schema` DSL, which tells the model the arguments it may send;
- a **handler**, a function from a `SafeParameterExtractor` to `Either[ToolHandlerError, R]`: `Right` is the
  result, `Left` is an error: the one a getter returned for a missing or mistyped argument, or a message of your
  own.

```scala
import org.llm4s.toolapi.{ SafeParameterExtractor, Schema, ToolBuilder, ToolFunction, ToolHandlerError }
import org.llm4s.types.Result

def temperatureTool: Result[ToolFunction[Map[String, Any], String]] = {
  val schema = Schema
    .`object`[Map[String, Any]]("Temperature to convert")
    .withProperty(Schema.property("value", Schema.number("The temperature to convert, in the unit given by `unit`")))
    .withProperty(
      Schema.property(
        "unit",
        Schema.string("The unit `value` is in").withEnum(Seq("celsius", "fahrenheit"))
      )
    )
    .withProperty(
      Schema.property(
        "precision",
        Schema.integer("Digits after the decimal point, 0 to 4 (default 1)").withMinimum(0).withMaximum(4),
        required = false
      )
    )

  def handler(params: SafeParameterExtractor): Either[ToolHandlerError, String] =
    for {
      value     <- params.getDouble("value")
      unit      <- params.getString("unit")
      precision <- params.getOptionalInt("precision")
      converted <- unit match {
        case "celsius"    => Right(value * 9 / 5 + 32)
        case "fahrenheit" => Right((value - 32) * 5 / 9)
        case other        => Left(s"unit must be 'celsius' or 'fahrenheit', not '$other'")
      }
    } yield BigDecimal(converted).setScale(precision.getOrElse(1), BigDecimal.RoundingMode.HALF_UP).toString

  ToolBuilder[Map[String, Any], String](
    "convert_temperature",
    "Converts a temperature between Celsius and Fahrenheit. Use it whenever the user asks for a temperature " +
      "in the other unit; it does not convert any other quantity.",
    schema
  ).withHandler(handler).buildSafe()
}
```

`ToolBuilder[T, R]` takes the schema's type `T` (`Map[String, Any]` for an object whose fields you read one by one)
and the result type `R`. `R` needs a upickle `ReadWriter`, because the result is written to JSON for the model:
`String`, numbers and `Seq`s have one, and for your own case class you add `given ReadWriter[Result] = macroRW`.
`buildSafe()` returns a `Result`, a `Left(ValidationError)` if you forgot `withHandler`, so building a tool never
throws.

## 2. Declaring parameters with `Schema`

`Schema` builds each parameter's type; `Schema.property(name, schema, required = true)` names it:

| You need | Use |
|---|---|
| text | `Schema.string("...")`, with `.withEnum(Seq("a", "b"))` for a fixed set of values |
| a whole number | `Schema.integer("...")`, with `.withMinimum(min).withMaximum(max)` |
| a decimal number | `Schema.number("...")` |
| true or false | `Schema.boolean("...")` |
| a list | `Schema.array("...", itemSchema)` |
| a value that may be `null` | `Schema.nullable(schema)` |
| one of several shapes | `Schema.anyOf("...", first, second)` |
| a JSON Schema keyword `Schema` has no setter for | `Schema.raw(ujson.Obj(...))`, sent as written |
| a nested object | ``Schema.`object` `` with a type parameter and a description, plus its own properties |

An object schema adds fields with `withProperty`, or with `withRequiredField(name, schema)` and
`withOptionalField(name, schema)`, which are shorthand for it. A nested object is read with a dotted path:

```scala
import org.llm4s.toolapi.{ ObjectSchema, SafeParameterExtractor, Schema, ToolParameterError }

def lookupSchema: ObjectSchema[Map[String, Any]] =
  Schema
    .`object`[Map[String, Any]]("A place to look up")
    .withRequiredField(
      "location",
      Schema
        .`object`[Map[String, Any]]("Where to look")
        .withRequiredField("city", Schema.string("City name"))
        .withOptionalField("country", Schema.string("Two-letter country code"))
    )
    .withOptionalField("tags", Schema.array("Labels to match", Schema.string("One label")))

def city(params: SafeParameterExtractor): Either[ToolParameterError, String] = params.getString("location.city")

def country(params: SafeParameterExtractor): Either[ToolParameterError, Option[String]] =
  params.getOptionalString("location.country")
```

An optional field reaches the model in one of two ways. `toolDefinition(strict = false)` leaves it out of
`required`, and the model omits it. `toolDefinition(strict = true)`, which the OpenAI-compatible clients send, must
list every property as required, so it marks the optional ones as nullable and the model sends `null` instead. Read
an optional parameter with `getOptional*`: it returns `None` for both.

## 3. Reading arguments

`SafeParameterExtractor` reads one argument by name: `getString`, `getInt`, `getDouble`, `getBoolean`, `getArray`
and `getObject`, each returning `Either[ToolParameterError, A]`, so they chain in a for-comprehension that stops at
the first problem. `getOptionalString`, `getOptionalInt`, `getOptionalDouble` and `getOptionalBoolean` return
`Either[ToolParameterError, Option[A]]`: an absent or `null` argument is `Right(None)`, and only a wrong type fails.
`validateRequired("value" -> ToolParameterType.Number, "unit" -> ToolParameterType.String)` checks several at once
and reports every problem rather than the first.

A handler's `Left` is a `ToolHandlerError`, which is `String | ToolParameterError`: return a getter's error as it
is, and write a message for a failure of your own, as the handler above does for an unknown unit. Give a handler
written as a `def` its return type, `Either[ToolHandlerError, R]`.

A missing or mistyped argument does not throw; it is a `Left` whose `message` is written for the model to act on:

| Arguments | Message |
|---|---|
| `value` absent | `required parameter 'value' (type: number) is missing (available: unit)` |
| `value` is `"hot"` | `parameter 'value' has wrong type - expected number but got string` |
| `value` is `null` | `parameter 'value' (type: number) is required but value was null` |

The message names what was expected and what arrived, and lists the argument names that were sent, so the model
can correct itself and call again. The error is a structured `ToolParameterError` (`MissingParameter`,
`TypeMismatch`, `NullParameter`, `InvalidNesting`), and `ToolCallErrorJson` renders it as JSON.

## 4. Returning a result or an error

The handler's `Right` value is written to JSON and handed to the model; a `Left(message)` becomes a
`ToolCallError.HandlerError`, and a getter's `Left` a `ToolCallError.InvalidArguments`. Return a `Left` for anything
the model can fix or should know: a bad argument, a missing record, a refused action. The model never sees a stack
trace:

- `tool.execute(args)` returns `Either[ToolCallError, ujson.Value]`. A `Left(message)` from the handler is a
  `Left(HandlerError(toolName, message))`, and a `Left` of a `ToolParameterError` is a
  `Left(InvalidArguments(toolName, parameterErrors))` that lists each argument at fault.
- Run through an agent, the model is shown `error.formatted`, which here reads
  `Tool call 'convert_temperature' failed with error: unit must be 'celsius' or 'fahrenheit', not 'kelvin'`.
- An exception your handler throws is not a `Left` you chose. A {% include api.html t="org.llm4s.toolapi.ToolRegistry" %} catches it and reports a
  `ToolCallError.ExecutionError` with the exception's message; calling `tool.execute` directly lets it escape.
  Keep expected failures in the `Left`.

## 5. Registering the tool and giving it to an agent

A `ToolRegistry` holds tools by name and runs them from a `ToolCallRequest`. The agent takes the registry when
you build it. This is what
[`MultiToolExample`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/MultiToolExample.scala)
does with two tools:

```scala
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ ToolFunction, ToolRegistry }
import org.llm4s.types.Result

def askWithTool(tool: ToolFunction[?, ?], question: String): Result[Option[String]] =
  for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client <- LLMConnect.getClient(providerConfig)
    toolRegistry <- ToolRegistry.of(Seq(tool))
    agent  <- Agent.builder("assistant", client).withTools(toolRegistry).build()
    result <- agent.run(question)
  } yield result.answer
```

The registry can also run a call itself, which is how the agent loop does it, and print the definitions a provider
receives:

```scala
import org.llm4s.toolapi.{ ToolCallError, ToolCallRequest, ToolFunction, ToolRegistry }
import org.llm4s.types.Result

def callByName(tool: ToolFunction[?, ?]): Result[Either[ToolCallError, ujson.Value]] = {
  println(tool.toolDefinition(strict = true).render(indent = 2)) // what the model is told about the tool
  ToolRegistry
    .of(Seq(tool)) // a Left when two tools share a name
    .map(_.execute(ToolCallRequest(tool.name, ujson.Obj("value" -> 100, "unit" -> "celsius"))))
}
```

A name that is not in the registry is a `ToolCallError.UnknownFunction` that lists the tools that are. To add your
tool next to the built-in ones, see [Built-in Tools](builtin-tools#8-your-own-tools); for how agents use tools,
see [Agents](agents/#agent-with-tools).

## 6. Testing a tool on its own

A tool is a function from JSON to JSON, so test it without a model or an agent: call `execute` with a `ujson.Obj`
and assert on the `Either`. `MCPEmbeddedServerRoundTripSpec` builds its compact tools this way.

```scala
import org.llm4s.toolapi.{ ToolCallError, ToolFunction }

def checkTemperatureTool(tool: ToolFunction[Map[String, Any], String]): Unit = {
  // the result is the handler's `Right` value, written as JSON
  assert(tool.execute(ujson.Obj("value" -> 100, "unit" -> "celsius")) == Right(ujson.Str("212.0")))
  assert(tool.execute(ujson.Obj("value" -> 100, "unit" -> "celsius", "precision" -> 3)) == Right(ujson.Str("212.000")))

  // a missing argument is InvalidArguments: the parameter error names it
  tool.execute(ujson.Obj("unit" -> "celsius")) match {
    case Left(error @ ToolCallError.InvalidArguments(name, List(parameter))) =>
      assert(name == "convert_temperature")
      assert(parameter.parameterName == "value")
      assert(error.message.contains("'value'") && error.message.contains("missing"))
    case other => assert(false, s"expected InvalidArguments, got $other")
  }

  // a wrong type is one too
  tool.execute(ujson.Obj("value" -> "hot", "unit" -> "celsius")) match {
    case Left(error: ToolCallError.InvalidArguments) => assert(error.message.contains("wrong type"))
    case other                                       => assert(false, s"expected InvalidArguments, got $other")
  }

  // the handler's own refusal is a HandlerError with its message
  tool.execute(ujson.Obj("value" -> 1, "unit" -> "kelvin")) match {
    case Left(ToolCallError.HandlerError(_, message)) => assert(message.contains("kelvin"))
    case other                                        => assert(false, s"expected a HandlerError, got $other")
  }
}
```

Build the tool with `temperatureTool` from section 1 and pass the `Right` value in. The same calls work in
ScalaTest with `shouldBe`; nothing here needs a provider or a network.

## 7. Three common mistakes

**A vague description.** The description and the parameter descriptions are all the model has. `"Does stuff"` and
`"the input"` give it nothing to choose the tool or fill the argument with. Say what the tool does, when to use it
and when not to, and what each parameter means, including its unit:

```scala
import org.llm4s.toolapi.{ Schema, ToolBuilder }

def vagueTool =
  ToolBuilder[Map[String, Any], String](
    "tool1",
    "Does stuff",
    Schema.`object`[Map[String, Any]]("Params").withRequiredField("input", Schema.string("the input"))
  ).withHandler(params => params.getString("input")).buildSafe()
```

**An undeclared parameter.** The model only sends arguments the schema declares. A handler that reads
`params.getString("unit")` while the schema lists only `value` passes every test that supplies `unit` by hand, then
fails in production with `required parameter 'unit' (type: string) is missing`. Check what the model is told:

```scala
import org.llm4s.toolapi.{ Schema, ToolBuilder }

def declaredParameters: Either[org.llm4s.error.LLMError, List[String]] =
  ToolBuilder[Map[String, Any], String](
    "convert_temperature",
    "Converts a temperature",
    Schema.`object`[Map[String, Any]]("Temperature").withRequiredField("value", Schema.number("The temperature"))
  ).withHandler(params => params.getString("unit")) // reads `unit`, which the schema never declares
    .buildSafe()
    .map(tool => tool.toolDefinition(strict = true)("function")("parameters")("properties").obj.keys.toList)
```

The list is `List("value")`: add every parameter the handler reads to the schema.

**A blocking call with no limit.** A handler that waits on a slow service holds the thread the agent loop is
running on, and one hung call stalls the whole turn. Run the registry with a timeout so a slow tool comes back as
an error the model can see:

```scala
import org.llm4s.toolapi.{ ToolCallError, ToolCallRequest, ToolExecutionConfig, ToolExecutor, ToolRegistry }

import scala.concurrent.duration.*

def callWithLimit(registry: ToolRegistry, request: ToolCallRequest): Either[ToolCallError, ujson.Value] =
  ToolExecutor.execute(registry, request, ToolExecutionConfig(timeout = Some(5.seconds)))
```

A call that runs past the limit returns `Left(ToolCallError.Timeout(name, 5.seconds))`. Give the underlying client
its own timeout as well, so the work behind the handler stops too.

## Related

- [Built-in Tools](builtin-tools): the tools that ship with `llm4s-agent-tools`
- [Agents](agents/): running tools in an agent loop, with guardrails and handoffs
- [Error Handling](error-handling): `Result[A]` and `LLMError`
