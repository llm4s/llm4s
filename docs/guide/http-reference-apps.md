---
layout: page
title: Serving an Agent over HTTP
description: "Reference apps that serve an LLM4S agent over HTTP with http4s, ZIO HTTP, Tapir and Play: a chat endpoint, a streamed endpoint, cancellation and tests."
search_title: "Serve an LLM agent over HTTP in Scala"
parent: User Guide
nav_order: 36
---

# Serving an Agent over HTTP

`modules/examples-http` holds small, tested reference apps that expose an llm4s agent from a Scala HTTP stack. Each
serves the same two endpoints, so they can be compared:

| Endpoint | Request | Response |
|---|---|---|
| `POST /chat` | `{"message": "..."}` | `{"threadId", "status", "answer"}` as JSON, or an error status |
| `POST /chat/stream` | `{"message": "..."}` | server-sent events of the answer as it is generated |

| App | Built on | Status |
|---|---|---|
| http4s (Ember) | [`llm4s-effect`](cats-effect): `AgentIO[IO]`, an fs2 `Stream` | available |
| ZIO HTTP (Netty) | [`llm4s-zio`](zio): `AgentZ`, `ZStream` | available |
| Tapir, served by http4s (Ember) | endpoint definitions interpreted by `tapir-http4s-server`, over `AgentIO[IO]`; OpenAPI docs at `/docs` | available |
| Play 3 (Pekko HTTP) | [`llm4s-pekko`](pekko): `AgentPekko`, a Pekko `Source`; Guice wiring | available |

The module is not published: it is code to read and copy. Its HTTP dependencies stay in it. The apps share what does
not depend on the stack: the request and event JSON (`ChatProtocol`), the error mapping (`HttpErrors`), the command
line (`ServerSettings`) and the agent's configuration (`ChatAgent`).

## Run it

With the fake client, which needs no model and no API key (it answers "You said: ..."):

```bash
sbt "examplesHttp/run --fake"
```

With a model: the app loads the provider at startup through `Llm4sConfig`, from the named section
`llm4s.providers.provider` names. The module's `application.conf` uses the samples' default, a local Ollama
(`ollama pull llama3` first, or set `OLLAMA_MODEL`), and has `openai-main` and `anthropic-main` sections that use
`OPENAI_API_KEY` and `ANTHROPIC_API_KEY`:

```bash
sbt examplesHttp/run
LLM4S_PROVIDER=openai-main sbt examplesHttp/run    # with OPENAI_API_KEY set
```

`run` starts the http4s app; the ZIO HTTP and Tapir apps take the same arguments through `runMain`:

```bash
sbt "examplesHttp/runMain org.llm4s.examples.http.ziohttp.ZioChatServerMain --fake"
LLM4S_PROVIDER=openai-main sbt "examplesHttp/runMain org.llm4s.examples.http.ziohttp.ZioChatServerMain"
sbt "examplesHttp/runMain org.llm4s.examples.http.tapir.TapirChatServerMain --fake"
sbt "examplesHttp/runMain org.llm4s.examples.http.playframework.PlayChatServerMain --fake"
```

`--port N` and `--host H` change the address (default `127.0.0.1:8080`). Then:

```bash
curl -s localhost:8080/chat -d '{"message": "What is Scala?"}'
curl -N localhost:8080/chat/stream -d '{"message": "What is Scala?"}'
```

## The stream

`POST /chat/stream` answers `200` with `Content-Type: text/event-stream` once the request is valid, then sends:

| Event | Data |
|---|---|
| `delta` | `{"attempt", "text"}`: the next piece of the answer. A higher `attempt` means the model call was retried; discard the earlier attempt's text. |
| `tool_call` | `{"id", "tool"}`: the agent is calling a tool. |
| `done` | the `/chat` response body; always the last event of a run that ends. |
| `error` | the error body below, plus `status` (and `retryAfterSeconds`): the run failed. |

A `: keep-alive` comment is sent every 15 seconds while the run is quiet.

The http4s app (`ChatEvents`) turns `AgentIO.stream` - the run's events, then its result - into `ServerSentEvent`s:

```scala
agent
  .stream(ThreadId(UUID.randomUUID().toString), message)
  .collect {
    case AgentStreamItem.Event(AgentEvents.TextDelta(d)) => event("delta", ChatProtocol.delta(d.attempt, d.text))
    case AgentStreamItem.Event(AgentEvents.ToolCallStarted(t)) =>
      event("tool_call", ChatProtocol.toolCall(t.toolCallId, t.tool))
    case AgentStreamItem.Done(result) => event("done", ChatProtocol.result(result))
  }
  .handleErrorWith(error => Stream.emit(event("error", ChatProtocol.streamError(HttpErrors.of(llmError(error))))))
  .mergeHaltL(Stream.awakeEvery[IO](heartbeat).as(ServerSentEvent(comment = Some("keep-alive"))))
```

The ZIO HTTP app does the same with `AgentZ.stream`, writing each event in the SSE format itself, so the keep-alive can
be a comment:

```scala
agent
  .stream(ThreadId(UUID.randomUUID().toString), message)
  .collect {
    case AgentStreamItem.Event(AgentEvents.TextDelta(d)) => event("delta", ChatProtocol.delta(d.attempt, d.text))
    case AgentStreamItem.Event(AgentEvents.ToolCallStarted(t)) =>
      event("tool_call", ChatProtocol.toolCall(t.toolCallId, t.tool))
    case AgentStreamItem.Done(result) => event("done", ChatProtocol.result(result))
  }
  .catchAllCause(cause => ZStream.succeed(event("error", ChatProtocol.streamError(HttpErrors.of(llmError(cause))))))
  .mergeHaltLeft(ZStream.fromSchedule(Schedule.spaced(heartbeat)).as(": keep-alive\n\n"))
```

The response is that stream as a chunked body (`Body.fromCharSequenceStreamChunked`) with
`Content-Type: text/event-stream`.

The Tapir app serves the http4s app's stream (`ChatEvents`), encoded with http4s's `ServerSentEvent.encoder`, as a
Tapir streaming body. It does not use `tapir-http4s-server`'s `serverSentEventsBody`, because sttp's `ServerSentEvent`
has no comment field for the keep-alive. See [The Tapir app](#the-tapir-app).

Both agents are built with `withStreaming()`, so its model calls stream and their text reaches the run's events as
`AgentEvents.TextDelta`.

## Cancellation

When the client disconnects, the run is cancelled and the provider call interrupted. The server learns of the
disconnect when a write fails; the heartbeat makes a write happen at least every 15 seconds, even while the model is
silent. The failed write interrupts the response stream, and interrupting `AgentIO.stream` cancels the run
(`AgentRun.cancel`), which interrupts its model call. `Http4sChatServerSpec` shows it: a provider call that parks after
its first chunk is interrupted once the test's socket closes.

The Tapir app hands the same stream to Ember through `tapir-http4s-server`, so a disconnect cancels the run in the same
way; `TapirChatServerSpec` makes the same check.

In the Play app, Play's Pekko HTTP server cancels the response's `Source` when the connection closes, and cancelling
`AgentPekko.stream` cancels the run in the same way; `PlayChatServerSpec` makes the same check.

In the ZIO HTTP app, the server interrupts the response stream when the connection closes, or when a write fails;
interrupting `AgentZ.stream` cancels the run in the same way. `ZioChatServerSpec` makes the same check.

`POST /chat` runs the turn with `AgentIO.run` or `AgentZ.run`, each of which cancels the turn when its fiber is
cancelled. Ember does not watch a connection while it computes a response, so with the http4s app the turn of a client
that leaves `/chat` runs to the end, and so does the Play app's, whose `AgentPekko.run` is a `Future`, which cannot be
cancelled; use `/chat/stream` when that matters, or bound the run with a timeout
(`RunConfig().withBudgets(RunBudgets().withTimeout(...))`).

## The Tapir app

`ChatEndpoints` defines the two endpoints as values, independent of any server, with the errors as a typed output:
`ErrorResponse` (the status, `Retry-After` in seconds, the error body), one `oneOf` variant per status `HttpErrors.of`
can choose, so the documentation lists each one.

```scala
val chat: PublicEndpoint[String, ErrorResponse, ujson.Value, Any] =
  endpoint.post
    .in("chat")
    .in(requestBody)
    .out(jsonBody(summon[Schema[ChatResponse]]).description("The turn's result."))
    .errorOut(oneOf[ErrorResponse](variant(StatusCode.BadRequest), ErrorStatuses.tail.map(variant)*))

val chatStream: PublicEndpoint[String, ErrorResponse, Stream[IO, Byte], Fs2Streams[IO]] =
  endpoint.post
    .in("chat" / "stream")
    .in(requestBody)
    .out(header(HeaderNames.CacheControl, "no-cache"))
    .out(streamTextBody(Fs2Streams[IO])(CodecFormat.TextEventStream(), Some(StandardCharsets.UTF_8)))
    .errorOut(oneOf[ErrorResponse](variant(StatusCode.BadRequest)))
```

The `429` variant adds the `Retry-After` header. The request body is read as text and checked by
`ChatProtocol.message`, so an invalid body gets the shared `400` error body instead of Tapir's own decode-failure
response. The bodies are the shared protocol's `ujson` values; the case classes in `ChatSchemas` (`derives Schema`)
only describe them for the documentation.

`TapirChatRoutes` gives the endpoints their logic, the same as the http4s app's, and interprets them with
`Http4sServerInterpreter`:

```scala
val chat = ChatEndpoints.chat.serverLogic[IO] { body =>
  ChatProtocol.message(body) match {
    case Left(invalid) => IO.pure(Left(ErrorResponse.of(HttpErrors.of(invalid))))
    case Right(message) =>
      agent.run(message).attempt.map {
        case Right(result) => Right(ChatProtocol.result(result))
        case Left(error)   => Left(ErrorResponse.of(HttpErrors.of(ChatEvents.llmError(error))))
      }
  }
}

val chatStream = ChatEndpoints.chatStream.serverLogicPure[IO] { body =>
  ChatProtocol.message(body).left.map(invalid => ErrorResponse.of(HttpErrors.of(invalid)))
    .map(message => ChatEvents(agent, message, heartbeat).through(ServerSentEvent.encoder[IO]))
}

val routes = Http4sServerInterpreter[IO]().toRoutes(List(chat, chatStream) ++ docs)
```

It is served on the http4s app's Ember server. Tapir 1.11.20 is the last release built on the cats-effect (3.5.7),
fs2 (3.11.0) and http4s (0.23.30) versions the module uses; a later Tapir needs cats-effect 3.6.

### The OpenAPI documentation

The same endpoint values are interpreted as an OpenAPI document (`OpenAPIDocsInterpreter`), so the documentation
cannot drift from what is served. With the Tapir app running:

- `http://localhost:8080/docs` - the Swagger UI, where both endpoints can be tried;
- `http://localhost:8080/docs/openapi.yaml` - the OpenAPI 3.1 document.

```bash
sbt "examplesHttp/runMain org.llm4s.examples.http.tapir.TapirChatServerMain --fake"
curl -s localhost:8080/docs/openapi.yaml
```

The document has both endpoints, the request, answer and error body schemas, and every error status with its
description; the `429` response documents `Retry-After`. To watch the stream's events as they arrive, use `curl -N`
rather than the Swagger UI.

## The Play app

`modules/examples-http/src/main/scala/org/llm4s/examples/http/playframework` is a Play 3 app on Scala 3, over
[`llm4s-pekko`](pekko). It uses Play as a library - `play-guice` and `play-pekko-http-server` - without the Play sbt
plugin: the routes are a SIRD router (`ChatRouter`) instead of a compiled `conf/routes` file, and `PlayChatServerMain`
starts the server itself (`PekkoHttpServer.fromApplication`). In an application built with the plugin, the module, the
controller and the configuration below are the same; only the router and the start differ.

```bash
sbt "examplesHttp/runMain org.llm4s.examples.http.playframework.PlayChatServerMain --fake"
LLM4S_PROVIDER=openai-main sbt "examplesHttp/runMain org.llm4s.examples.http.playframework.PlayChatServerMain"
```

### Versions

Play 3.0.12 is built on Pekko 1.0.3 and Pekko HTTP 1.0.1; `llm4s-pekko` is built on Pekko 1.7.1. Pekko refuses to
start with mixed versions of its modules, so the module pins every Pekko artifact Play brings (`pekko-actor`,
`pekko-actor-typed`, `pekko-slf4j`, `pekko-serialization-jackson`, `pekko-protobuf-v3`, `pekko-stream`) to 1.7.1 with
`dependencyOverrides`, which Pekko's binary compatibility within 1.x allows. An application on Play 3.0 does the same;
Pekko HTTP keeps its own version. Play is Apache-2.0 licensed, and only this unpublished module depends on it.

### Wiring with Guice

`ChatModule`, a Play `Module` enabled in `application.conf`, binds the `LLMClient` and the agent as eager
singletons, so a provider section that cannot be loaded stops the application from starting instead of failing its
first request:

```scala
bind[LLMClient].toProvider[LLMClientProvider].eagerly(),
bind[AgentPekko].toProvider[AgentPekkoProvider].eagerly()
```

`LLMClientProvider` loads the client and registers an `ApplicationLifecycle` stop hook that closes it when the
application stops:

```scala
lifecycle.addStopHook(() => Future.fromTry(Try(client.close())))
```

### Configuration

The llm4s settings live in Play's own `application.conf`, beside Play's keys. The client is loaded from the
`Configuration` Play injects, with `Llm4sConfig.from`, not from a second read of the classpath:

```scala
settings <- Llm4sConfig.from(configuration.underlying)
registry <- settings.modelRegistryService()
provider <- settings.defaultProvider()
client   <- LLMConnect.getClient(provider)(using registry)
```

The precedence is the library's: JVM system properties (`-Dllm4s.providers.provider=openai-main`) override Play's
configuration, which overrides each llm4s module's `reference.conf` - so `llm4s.credentials.<provider>.apiKey`, bound
to the vendor's variable such as `OPENAI_API_KEY`, applies without a line in `application.conf`. A key a test sets with
`GuiceApplicationBuilder().configure(...)` is part of Play's configuration, so it reaches llm4s too. The module's
`application.conf` adds Play's keys to the shared `llm4s` block:

```hocon
play {
  modules.enabled += "org.llm4s.examples.http.playframework.ChatModule"
  http.router = "org.llm4s.examples.http.playframework.ChatRouter"
}

examples.chat {
  heartbeat = 15 seconds
  blocking-dispatcher {
    executor = "thread-pool-executor"
    throughput = 1
    thread-pool-executor {
      fixed-pool-size = 16
    }
  }
}
```

(Production mode also needs `play.http.secret.key`, at least 32 bytes; the app signs nothing, so it sets a fixed one
that `APPLICATION_SECRET` overrides.)

### Not blocking Play's dispatcher

A provider call blocks its thread. `POST /chat` waits for its run with `AgentPekko.run` on `ChatExecutionContext`, a
`CustomExecutionContext` over `examples.chat.blocking-dispatcher`, so Play's default dispatcher, which serves every
request, is never held:

```scala
agent
  .run(message)(using blocking)
  .map(result => Ok(ChatProtocol.result(result).render()).as(ContentTypes.JSON))
  .recover { case error => failed(HttpErrors.of(PlayChatEvents.llmError(error))) }
```

`POST /chat/stream` needs no dispatcher of its own: `AgentPekko.stream` runs the turn on a thread of its own. The
controller answers the stream with `Ok.chunked`:

```scala
Ok.chunked(PlayChatEvents(agent, message, heartbeat))
  .as(ContentTypes.EVENT_STREAM)
  .withHeaders(HeaderNames.CACHE_CONTROL -> "no-cache")
```

`PlayChatEvents` writes each event in the SSE format itself, so the keep-alive can be a comment (Play's
`EventSource.Event` has no comment), and merges in the heartbeat until the run ends:

```scala
val run = agent
  .stream(ThreadId(UUID.randomUUID().toString), message)
  .collect {
    case AgentStreamItem.Event(AgentEvents.TextDelta(d)) => event("delta", ChatProtocol.delta(d.attempt, d.text))
    case AgentStreamItem.Event(AgentEvents.ToolCallStarted(t)) =>
      event("tool_call", ChatProtocol.toolCall(t.toolCallId, t.tool))
    case AgentStreamItem.Done(result) => event("done", ChatProtocol.result(result))
  }
  .recover { case error => event("error", ChatProtocol.streamError(HttpErrors.of(llmError(error)))) }
run
  .merge(Source.tick(heartbeat, heartbeat, KeepAlive), eagerComplete = true)
  .mapMaterializedValue(_ => NotUsed)
```

Errors map to the same statuses as the other apps (`HttpErrors.of`, below); a failed `Future` or stream carries its
`LLMError` in `llm4s-pekko`'s `LLMException`.

## Errors

An error is answered as `{"error": {"type", "message", "recoverable"}}`. `HttpErrors.of` chooses the status from the
error that failed the run, unwrapping the agent runtime's `GraphError.NodeFailed`:

| Error | Status |
|---|---|
| a body that is not `{"message": "<non-blank>"}`, `ValidationError`, `InvalidInputError` | `400` |
| `RateLimitError` | `429`, with `Retry-After`: the provider's hint, else `RateLimitError.DefaultRetryDelay` |
| `TimeoutError` | `504` |
| `AuthenticationError` | `502`: the provider refused the server's credential, which the caller cannot fix |
| `ConfigurationError` | `500` |
| any other `RecoverableError` (network, provider 5xx) | `503` |
| anything else | `502` |

The message is the library's, which can name the provider; a public service may want to log it and send less.

## Testing

The tests need no API key and no network beyond the loopback. `ChatRoutesSpec` calls the routes in memory against a
`ScriptedLLMClient` from [`llm4s-testkit`](testing-with-the-testkit): the JSON answer, the order of the streamed
events, each error's status, and the stream's `error` event. `Http4sChatServerSpec` starts the server on an ephemeral
port and calls it over real sockets, including the disconnect above. `ZioChatRoutesSpec` (with `Routes.runZIO`) and
`ZioChatServerSpec` check the ZIO HTTP app the same way, and `TapirChatRoutesSpec` and `TapirChatServerSpec` the Tapir
app. `ChatEndpointsSpec` checks the generated OpenAPI document: both endpoints, their schemas, every error status, and
`Retry-After` on the `429` response.

The Play app is tested with Play's test helpers. `ChatControllerSpec` sends `FakeRequest`s through `route` to an
application built by `GuiceApplicationBuilder` with the agent overridden: the same checks as `ChatRoutesSpec`.
`PlayChatServerSpec` starts the app on Play's server on an ephemeral port, including the disconnect above, and
`ChatModuleSpec` checks the wiring: the client from Play's configuration, the start that fails on a missing provider
section, the blocking dispatcher, and the stop hook that closes the client. `PlayGuideSpec` checks that every line of
this page's Play snippets is in the app's sources.

```bash
sbt examplesHttp/test
```

## What a real service adds

These apps leave out authentication, rate limiting, request size limits, conversation history (each request starts
a new thread in the agent's in-memory store) and deployment. See [Production Deployment](../PRODUCTION_DEPLOYMENT) for
closing the client and the rest of running llm4s in production.
