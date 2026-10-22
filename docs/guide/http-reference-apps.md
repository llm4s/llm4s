---
layout: page
title: Serving an Agent over HTTP
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
| Tapir | endpoint definitions with streaming bodies | planned ([#1459](https://github.com/llm4s/llm4s/issues/1459)) |

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

`run` starts the http4s app; the ZIO HTTP app takes the same arguments through `runMain`:

```bash
sbt "examplesHttp/runMain org.llm4s.examples.http.ziohttp.ZioChatServerMain --fake"
LLM4S_PROVIDER=openai-main sbt "examplesHttp/runMain org.llm4s.examples.http.ziohttp.ZioChatServerMain"
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

The http4s app turns `AgentIO.stream` - the run's events, then its result - into `ServerSentEvent`s:

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

Both agents are built with `withStreaming()`, so its model calls stream and their text reaches the run's events as
`AgentEvents.TextDelta`.

## Cancellation

When the client disconnects, the run is cancelled and the provider call interrupted. The server learns of the
disconnect when a write fails; the heartbeat makes a write happen at least every 15 seconds, even while the model is
silent. The failed write interrupts the response stream, and interrupting `AgentIO.stream` cancels the run
(`AgentRun.cancel`), which interrupts its model call. `Http4sChatServerSpec` shows it: a provider call that parks after
its first chunk is interrupted once the test's socket closes.

In the ZIO HTTP app, the server interrupts the response stream when the connection closes, or when a write fails;
interrupting `AgentZ.stream` cancels the run in the same way. `ZioChatServerSpec` makes the same check.

`POST /chat` runs the turn with `AgentIO.run` or `AgentZ.run`, each of which cancels the turn when its fiber is
cancelled. Ember does not watch a connection while it computes a response, so with the http4s app the turn of a client
that leaves `/chat` runs to the end; use `/chat/stream` when that matters, or bound the run with a timeout
(`RunConfig().withBudgets(RunBudgets().withTimeout(...))`).

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
`ZioChatServerSpec` check the ZIO HTTP app the same way.

```bash
sbt examplesHttp/test
```

## What a real service adds

These apps leave out authentication, rate limiting, request size limits, conversation history (each request starts
a new thread in the agent's in-memory store) and deployment. See [Production Deployment](../PRODUCTION_DEPLOYMENT) for
closing the client and the rest of running llm4s in production.
