---
layout: page
title: "7. Serving and Observing It"
parent: Build a Support Assistant
grand_parent: Tutorials
nav_order: 7
description: "Support assistant tutorial, part 7: serve the LLM4S agent over HTTP, trace its runs, count its replies in Prometheus metrics, and deploy it as a service."
search_title: "Support assistant tutorial 7: serve and observe an agent"
---

# Part 7: serving and observing it
{: .no_toc }

The assistant works and has tests. Customers reach it through the shop's website, so it needs an HTTP endpoint; and
once it is live you need to see what it does: a trace of every run, and metrics that say how many replies it sent and
how many it handed to a person. This part adds both, then points at what a deployment adds on top.
{: .fs-6 .fw-300 }

The code is
[`Part7Serving.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Part7Serving.scala).

## From a message to a reply

The endpoint's work, without any HTTP: run the agent on the customer's message and turn the result into the JSON the
website shows. The status tells the website whether the assistant answered or handed the message over.

```scala
import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
import org.llm4s.types.Result
import upickle.default.ReadWriter

case class ChatRequest(message: String) derives ReadWriter

case class ChatResponse(answer: String, status: String) derives ReadWriter

def statusOf(result: AgentResult): String =
  result.status match {
    case AgentStatus.Completed(_)  => "answered"
    case AgentStatus.Blocked(_, _) => "handed-over"
    case _                         => "incomplete"
  }

def respond(agent: Agent)(message: String): Result[ChatResponse] =
  for {
    result <- agent.run(message)
    _      <- agent.forget(result.threadId)
  } yield ChatResponse(Part5SafetyMemory.reply(result), statusOf(result))
```

`agent.run` starts a new thread for every message, and the agent keeps each thread until it is forgotten. A service
that answers one message at a time forgets the thread once it has replied, so memory does not grow with every request.
To carry a conversation over several requests, return `result.threadId` to the website and continue with
`agent.run(threadId, message)`, as Part 5 did with `continueConversation`.

## An HTTP endpoint

Any Scala HTTP server can call `respond`. To keep the tutorial free of extra dependencies this one uses the server
built into the JDK: `POST /chat` with `{"message": "..."}` answers `ChatResponse` as JSON.

```scala
import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import org.llm4s.syntax.toResult
import scala.util.{ Try, Using }
import upickle.default.{ read, write }

case class ChatError(error: String) derives ReadWriter

def serve(port: Int)(respond: String => Result[ChatResponse]): Result[HttpServer] =
  Try(HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)).toResult.map { server =>
    server.createContext("/chat", exchange => answer(exchange, respond))
    server.start()
    server
  }

def answer(exchange: HttpExchange, respond: String => Result[ChatResponse]): Unit = {
  val (code, json) =
    if (exchange.getRequestMethod != "POST") (405, write(ChatError("Use POST")))
    else
      Try(read[ChatRequest](new String(exchange.getRequestBody.readAllBytes(), UTF_8))).toOption match {
        case None => (400, write(ChatError("""Send {"message": "..."}""")))
        case Some(request) =>
          respond(request.message) match {
            case Right(response) => (200, write(response))
            case Left(_)         => (503, write(ChatError("The assistant is unavailable. Please try again.")))
          }
      }
  val bytes = json.getBytes(UTF_8)
  exchange.getResponseHeaders.set("Content-Type", "application/json")
  exchange.sendResponseHeaders(code, bytes.length.toLong)
  Using.resource(exchange.getResponseBody)(_.write(bytes))
}
```

A refused message is a `200` with the status `handed-over`: the service worked, and the website shows the hand-over
text. Only an error, such as the provider being down, is a `503`, and its detail stays in your logs, never in the
response.

For a real service, use the stack you already run. The
[HTTP reference apps](../../guide/http-reference-apps) serve an llm4s agent with
[http4s](../../guide/cats-effect), [ZIO HTTP](../../guide/zio), Tapir (with OpenAPI docs) and Play, each with the
same `POST /chat` and a streamed `POST /chat/stream` that sends the answer as it is generated, with tests. The
[Tapir tools guide](../../guide/tapir-tools) goes the other way: it turns your existing Tapir endpoints into tools
the agent can call.

## Tracing every run

`withTracing` on the agent's builder sends each run's events to a `Tracing`: the steps of the run, the token usage of
each model call, and one `AgentRunEnded` when it finishes. Which tracer you get is configuration, not code:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.trace.Tracing

def configuredTracing(): Result[Tracing] =
  Llm4sConfig.tracing().flatMap(settings => Tracing.fromSettings(settings))

def observedAgent(client: LLMClient, tracing: Tracing): Result[Agent] =
  for {
    order    <- Part3Tools.orderTool
    policy   <- Part3Tools.policyTool
    registry <- ToolRegistry.of(Seq(order, policy))
    agent <- Agent
      .builder("support", client)
      .withSystemPrompt("You are the support assistant of Acme Shop. Use the tools; never guess an order's status.")
      .withTools(registry)
      .withInputGuardrails(Part5SafetyMemory.inputGuardrails*)
      .withOutputGuardrails(Part5SafetyMemory.outputGuardrails*)
      .withTracing(tracing)
      .build()
  } yield agent
```

`llm4s.tracing.mode`, bound to `TRACING_MODE`, picks the backend:

| `TRACING_MODE` | Traces go to | Module |
|---|---|---|
| `console` (the default) | standard output, for development | core |
| `none` | nowhere | core |
| `opentelemetry` | any OpenTelemetry collector (`OTEL_EXPORTER_OTLP_ENDPOINT`): Jaeger, Grafana Tempo, Honeycomb, ... | `llm4s-observability-otel` |
| `langfuse` | Langfuse (`LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`); the observability guide says which Langfuse versions accept it | `llm4s-observability` |

Traces hold the customer's messages only when the backend is told to capture content; the input guardrails have
already masked personal data either way. The [observability guide](../../guide/observability/) covers each backend
and what a span carries, and [instrumenting an agent service](../../guide/observability/agents) the trace of a run and
the agent runtime's own metrics.

## Counting replies

A trace tells you about one run; metrics tell you about all of them. A `MetricsCollector` records counters, timers and
gauges, and `PrometheusMetrics` exposes them to Prometheus. The service counts its replies by status, so a dashboard
can show the share of messages handed to a person, and an alert can fire when errors rise:

```scala
import org.llm4s.metrics.{ Metric, MetricsCollector }

val Replies: Metric.Counter =
  Metric.counter("support.replies", "Replies the assistant sent, by how its run ended", "status")

def counted(metrics: MetricsCollector)(respond: String => Result[ChatResponse]): String => Result[ChatResponse] =
  message => {
    val response = respond(message)
    metrics.increment(Replies, Seq(response.fold(_ => "error", _.status)))
    response
  }
```

Prometheus sees the counter as `support_replies_total{status="answered"}`. The same collector also measures the model:
build the client with `LLMConnect.getClient(providerConfig, LlmClientOptions(metrics = metrics))` and every request
adds to `llm4s_requests_total`, `llm4s_tokens_total`, `llm4s_cost_usd_total` and `llm4s_request_duration_seconds`,
by provider and model. To record into Micrometer instead, use
[`llm4s-observability-micrometer`](../../guide/observability/micrometer).

## Putting it together

`start` builds the traced agent, serves `/chat` with every reply counted, and serves the metrics on `/metrics` for
Prometheus to scrape:

```scala
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.llm4s.metrics.{ PrometheusEndpoint, PrometheusMetrics }

case class Service(server: HttpServer, metricsEndpoint: PrometheusEndpoint) {
  def chatUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}/chat"

  def stop(): Unit = {
    server.stop(0)
    metricsEndpoint.stop()
  }
}

def start(client: LLMClient, tracing: Tracing, port: Int, metricsPort: Int): Result[Service] = {
  val registry = new PrometheusRegistry()
  val metrics  = new PrometheusMetrics(registry)
  for {
    agent    <- observedAgent(client, tracing)
    endpoint <- PrometheusEndpoint.start(metricsPort, registry)
    server   <- serve(port)(counted(metrics)(respond(agent)))
  } yield Service(server, endpoint)
}
```

Run the part. It starts the service on free ports, posts a question and a prompt injection to `/chat` as a website
would, and prints the two responses and the counters Prometheus would scrape:

```bash
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part7Serving"
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part7Serving --live"
```

`Part7Spec` checks each piece: the replies and their statuses, the endpoint's `200`, `400`, `405` and `503`, a trace
that ends with one `AgentRunEnded`, the counters in Prometheus's format, and the whole service over real HTTP.

## Deploying it

A deployed service needs a few things the tutorial leaves out. [`modules/deploy-service`](https://github.com/llm4s/llm4s/tree/main/modules/deploy-service)
is a small llm4s service with all of them, to copy rather than depend on, and
[`deploy/README.md`](https://github.com/llm4s/llm4s/blob/main/deploy/README.md) walks through it:

- **Health checks.** `GET /health` for the liveness probe, and `GET /llm-check`, which checks that a provider is
  configured without calling it.
- **A container image**, built by the sbt Docker plugin (`sbt deployService/Docker/publishLocal`), and Kubernetes
  manifests with `dev`, `staging` and `prod` overlays and a rollout that rolls back when a health check fails.
- **Configuration from the environment.** The provider section and model come from `application.conf` or `-D`
  properties, the key from a secret through its variable (`OPENAI_API_KEY`, ...), and `TRACING_MODE` from the
  deployment, so the same image runs everywhere. See [configuration](../../getting-started/configuration) and the
  [production deployment guide](../../guide/production-deployment).
- **More than one instance.** Threads live in the agent's memory by default. To share them between instances, or keep
  them across a restart, give the agent a [durable checkpointer](../../guide/agents/durable-checkpointers); with a run
  queue, any instance can run, follow or cancel a run ([multiple machines](../../guide/agents/multiple-machines)).

The [operations guides](../../guide/operations) cover the rest of running it: secrets and key rotation, data
protection, an AI gateway in front of the providers, and monitoring. [Reliable LLM calling](../../guide/reliability)
wraps the client in retries, a circuit breaker and deadlines, and [cost controls](../../guide/cost-controls) cut what
each call costs.

## Exercise 7: time each reply

Counting replies does not show a slow provider. Write `Part7Exercise.timed` in
[`Exercises.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Exercises.scala):
like `counted`, but it measures how long `respond` took, with `System.nanoTime`, and records the time with
`metrics.observe` in the declared timer `Part7Exercise.ReplyDuration`, labelled with the response's status or `error`.
Then run

```bash
sbt "samples/testOnly *Part7Spec"
```

For a bonus, wrap the service's `respond` in both `counted` and `timed` in `start`, and find
`support_reply_duration_seconds` in the metrics the demo prints. The solution is `Part7Solution` in the
[`solutions`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/solutions/Solutions.scala)
package.

Previous: [Part 6, testing it](part-6-testing). This is the last part: the [series index](./) lists them all.
