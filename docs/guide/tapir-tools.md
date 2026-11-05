---
layout: page
title: Tapir Endpoints as Tools
description: "Turn the tapir endpoints a Scala service already has into LLM4S tools, with each tool's schema derived from its endpoint."
search_title: "Expose tapir endpoints as LLM agent tools"
parent: User Guide
nav_order: 43
---

# Tapir endpoints as tools
{: .no_toc }

`llm4s-tapir` turns [tapir](https://tapir.softwaremill.com) endpoints into tools a model can call. The tool's
parameter schema is derived from the endpoint's own inputs, and a call goes through the endpoint's own codecs, so
the tool cannot drift from the API it calls. The module is Experimental.

1. TOC
{:toc}

---

## Installation

```scala
libraryDependencies += "org.llm4s" %% "llm4s-tapir" % llm4sVersion // same version as llm4s-core
```

It depends on `llm4s-core`, `tapir-core` 1.11 and sttp client 3, not on the agent runtime: the tools are plain
`ToolFunction`s in a `ToolRegistry`, for an agent or for your own tool calling.

## From endpoints to a registry

`EndpointTools.tool` names an endpoint; `EndpointTools.registry` builds the tools, each calling its endpoint at a
base URI through an sttp backend:

```scala
import org.llm4s.tapir.EndpointTools
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import sttp.client3.{ HttpClientSyncBackend, UriContext }
import sttp.tapir._

val getOrder =
  endpoint.get
    .in("orders" / path[String]("id").description("The order's id"))
    .out(stringBody)
    .errorOut(stringBody)
    .description("Look up an order by id")

val cancelOrder =
  endpoint.post
    .in("orders" / path[String]("id") / "cancel")
    .out(stringBody)
    .description("Cancel an order")

val tools: Result[ToolRegistry] = EndpointTools.registry(
  uri"https://orders.internal",
  HttpClientSyncBackend(),
  EndpointTools.tool("get_order", getOrder),
  EndpointTools.tool("cancel_order", cancelOrder)
)
```

Your endpoints most likely use a JSON integration such as `tapir-json-circe`'s `jsonBody[Order]`; that works the
same, since the tool reads the body's tapir `Schema` and calls its `Codec`. The examples here use `stringBody` so
that they compile with LLM4S alone.

`registry` is a `Left(ValidationError)` listing every endpoint that cannot be a tool and why, so a misfit fails
when the application starts, not when the model first calls it.

## The derived schema

Each input of the endpoint becomes a property of the tool's JSON schema:

| Input | Property |
|---|---|
| `path[T]("id")` | `id`, required; an unnamed `path[T]` is `param1`, `param2`, ... as tapir shows it |
| `query[T]("page")` | `page`, required unless `T` is an `Option`; a `List` is an array |
| `header[T]("X-Request-Id")` | `X-Request-Id`, only when the model may set it (see [Headers](#headers)) |
| a text or JSON body | `body`, with the fields of its tapir `Schema` |
| fixed paths, fixed headers, the method | nothing: the tool sends them itself |

From each tapir `Schema` the property takes its type, its description (the input's, else the schema's), whether
it is optional, and the validators JSON Schema can state: an enumeration's values, `min`/`max` (exclusive or
not), `minLength`/`maxLength` and `minSize`/`maxSize`. A `pattern` is added to the description. An optional
property is also nullable, so the schema holds in OpenAI's strict mode, where every property is required.

The tool's description is the endpoint's `description`, else its `summary`, else its method and path template;
`withDescription` replaces it.

What a schema cannot state fails the build: a map, a recursive type, a sealed hierarchy that is not an
enumeration, binary data. So does an input the model cannot supply (a cookie, `paths`, `queryParams`, `headers`, a
multipart or binary body) and an output it cannot read (a binary, streaming or web-socket body).

## A call

A call decodes each argument with its input's own `Codec`, so the endpoint's types and validators check it before
anything is sent, then calls the endpoint through sttp. The tool's result is the endpoint's output, checked
against the output's codec: JSON for a JSON body, a string for a text body, and `{"status": 204}` for a response
with no body. Everything that goes wrong is a tool error the model reads, never an exception:

| What happens | The model reads |
|---|---|
| an argument the codec or a validator rejects | `the arguments are invalid: 'limit' is invalid: expected <=100, got 500`, every argument at once; nothing is sent |
| a path argument that is empty, `.` or `..`, or holds `/` or `\`, written or percent-encoded (`%2e%2e`) | `'id' must be a single path segment, not '..'`; nothing is sent, so a model cannot reach another endpoint of the same host with your headers |
| an error response | `the service rejected the call with HTTP 404: {"message":"no order 9"}` |
| a server error | `the service failed with HTTP 503: ...` |
| the request cannot be sent | `the request to GET https://... could not be sent: ...` |
| a success whose body does not match the output | `... a body that does not match the endpoint's output: ...` |

A quoted body is cut at 2000 characters.

## Headers

A header input is the application's unless you let the model set it. Authentication headers are always the
application's: `withModelHeaders` does not reach an `auth.*` input.

```scala
import org.llm4s.tapir.EndpointTools
import sttp.tapir._

val listOrders =
  endpoint.get
    .securityIn(auth.bearer[String]())
    .in("orders")
    .in(header[Option[String]]("X-Request-Id"))
    .out(stringBody)

val tool = EndpointTools
  .tool("list_orders", listOrders)
  .withHeaders(Map("Authorization" -> "Bearer service-token")) // sent on every call
  .withModelHeaders("X-Request-Id")                            // the only header the model may set
```

`withHeaders` are sent on every call, whether or not the endpoint declares them. A required header that is
neither the application's nor the model's fails the build; so does one that is both. An argument the schema does
not list, such as a header the model was not allowed, is ignored. Only header authentication is supported: an
API key in a query parameter or a cookie fails the build.

## Approval

{: .warning }
> **Approval is not enforced by the tool.** `requiresApproval` only marks the tool; a `ToolRegistry` carries no
> approval flag, so an agent runs a `cancel_order` call at once unless you wire
> `EndpointTools.requiringApproval` into an `ApprovalMiddleware` on that agent, as below.

`EndpointTools.tool(name, endpoint)` requires approval for any endpoint whose method is not `GET`, `HEAD` or
`OPTIONS`; pass `requiresApproval` to decide yourself. A `ToolRegistry` carries no approval flag, so
`EndpointTools.requiringApproval` gives the names to an agent's `ApprovalMiddleware`:

```scala
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.tapir.EndpointTools
import sttp.tapir._

val read  = EndpointTools.tool("get_order", endpoint.get.in("orders" / path[String]("id")))
val write = EndpointTools.tool("cancel_order", endpoint.post.in("orders" / path[String]("id") / "cancel"))
val ping  = EndpointTools.tool("ping", endpoint.post.in("ping"), requiresApproval = false)

val gated    = EndpointTools.requiringApproval(read, write, ping) // Set("cancel_order")
val approval = new ApprovalMiddleware(call => Option.when(gated(call.spec.name))("this changes an order"))
```

Give `approval` to the agent with `withMiddleware`: a call to `cancel_order` suspends the run until it is
approved ([Human review](agents/human-review)).

## Testing without a network

tapir's stub interpreter serves your endpoints in-process, so a test or a demo runs an agent against the real
endpoint definitions with a scripted model and no server:

```scala
import org.llm4s.agent.Agent
import org.llm4s.tapir.EndpointTools
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import sttp.client3.{ Identity, UriContext }
import sttp.client3.testing.SttpBackendStub
import sttp.tapir._
import sttp.tapir.server.stub.TapirStubInterpreter

val getOrder = endpoint.get.in("orders" / path[String]("id")).out(stringBody).errorOut(stringBody)

val backend = TapirStubInterpreter(SttpBackendStub.synchronous)
  .whenServerEndpointRunLogic(getOrder.serverLogic[Identity](id => Right(s"order $id: 9.50 EUR")))
  .backend()

val model = ScriptedLLMClient.sequence(
  Reply.toolCall("get_order", """{"id": "42"}"""),
  Reply.text("Order 42 is 9.50 EUR.")
)

val answer = for {
  tools  <- EndpointTools.registry(uri"http://orders.test", backend, EndpointTools.tool("get_order", getOrder))
  agent  <- Agent.builder("orders", model).withTools(tools).build()
  result <- agent.run("What does order 42 cost?")
} yield result.answer
```

The stub needs `tapir-sttp-stub-server` (the same tapir version) in your test scope. The runnable version is
`org.llm4s.samples.tapir.TapirEndpointToolsExample`:

```bash
sbt "samples/runMain org.llm4s.samples.tapir.TapirEndpointToolsExample"
```

## Limits

- Endpoints are called synchronously, through an `SttpBackend[Identity, Any]` (sttp client 3), one call per
  tool call. Use the backend you already configure for timeouts, retries, proxies or logging.
- Arguments are checked input by input; a validator or mapping across several inputs (`mapTo`, `validate` on a
  pair) is left to the server.
- Importing an OpenAPI document that is not a tapir endpoint is not supported.
