---
layout: page
title: Your HTTP Client
description: "Send the provider clients' requests through the sttp backend your service already has, and test a provider call against sttp's stub backend."
search_title: "Use your own sttp backend as the LLM4S HTTP transport"
parent: User Guide
nav_order: 52
---

# Your HTTP client
{: .no_toc }

A Scala service that already calls HTTP APIs has a client it has tuned: a connection pool, proxy and TLS settings,
request logging, tracing, and a stub backend for its tests. `llm4s-sttp` hands that client to the LLM4S provider
clients, so their requests go through it too ([#2341](https://github.com/llm4s/llm4s/issues/2341)). Experimental.

1. TOC
{:toc}

---

## Add the module

```scala
libraryDependencies += "org.llm4s" %% "llm4s-sttp" % llm4sVersion // same version as llm4s-core
```

It depends on `llm4s-core` and sttp client 4's `core` only. `SttpLlm4sHttpClient(backend)` is an
`org.llm4s.http.Llm4sHttpClient` over any sttp 4 synchronous backend (`SyncBackend`); pass it with
`LlmClientOptions.withHttpClient`.

- **You own the backend.** Closing the LLM client, or the `SttpLlm4sHttpClient`, does not close it; close it when
  your application stops.
- **The section's `llm4s.http` proxy and TLS settings do not apply**: configure them on your backend.
- **Which providers use it**: the SDK-free ones, `ollama`, `gemini`, `vertexai`, `watsonx`, `openai-compatible` and
  its dialects (`deepseek`, `zai`, `openrouter`, `mistral`, `cohere`, `xai`). A descriptor's
  `features.customHttpClient` says whether it does. Providers built on a vendor SDK (`openai`, `azure`, `requesty`,
  `anthropic`, `bedrock`) keep the SDK's transport and ignore it. Embedding providers do not take one yet.
- **It behaves as the default client does**: a non-2xx status is a response, not an error; a timeout is a
  `TimeoutError`, a refused connection a `NetworkError`, an invalid URL a `ValidationError`; streamed replies are read
  as they arrive. Each request's timeout becomes its sttp `readTimeout`. A transport contract runs against both
  clients to keep it so.

## Reuse your backend and its middleware

Wrap the backend in whatever middleware your service already uses, then pass it on. Here sttp's own
`LoggingBackend` logs each provider request; it leaves request bodies out and redacts the `Authorization` header by
default.

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.sttp.SttpLlm4sHttpClient
import org.llm4s.types.Result
import sttp.client4.{ DefaultSyncBackend, SyncBackend }
import sttp.client4.logging.{ LogLevel, Logger, LoggingBackend }
import sttp.shared.Identity

// Your logger; sttp's slf4j-backend module has one over SLF4J.
val logger: Logger[Identity] = new Logger[Identity] {
  def apply(level: LogLevel, message: => String, exception: Option[Throwable], context: Map[String, Any]): Unit =
    println(s"[$level] $message")
}

// Created once, at application start, and closed when it stops.
val backend: SyncBackend = LoggingBackend(DefaultSyncBackend(), logger)

val client: Result[LLMClient] = for {
  providerConfig <- Llm4sConfig.defaultProvider()
  registry       <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  client <- LLMConnect.getClient(
    providerConfig,
    LlmClientOptions.default.withHttpClient(new SttpLlm4sHttpClient(backend))
  )
} yield client
```

OpenTelemetry works the same way: sttp's `opentelemetry-backend` module wraps a backend with
`OpenTelemetryTracingBackend(backend, openTelemetry)`, which starts a client span for every provider request and
propagates the trace context in its headers. Metrics, retries and redirects are sttp backend wrappers too.

## Test a provider call against a stub backend

sttp's `SyncBackendStub` answers requests without a network, and `RecordingBackend` keeps every request it sees, so a
test can assert on the exact HTTP exchange: here, the JSON body the `openai-compatible` provider sends for a
`complete` call.

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMConnect, LlmClientOptions }
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.sttp.SttpLlm4sHttpClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client4.testing.{ RecordingBackend, RichTestingRequest, SyncBackendStub }

class ChatRequestSpec extends AnyFlatSpec with Matchers {

  private val reply =
    """{"id":"c-1","object":"chat.completion","created":1700000000,"model":"my-model",
      |"choices":[{"index":0,"message":{"role":"assistant","content":"A JVM language."},"finish_reason":"stop"}],
      |"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""".stripMargin

  "the chat call" should "send the user's question in the request body" in {
    val backend = RecordingBackend(
      SyncBackendStub
        .whenRequestMatches(_.uri.path.endsWith(Seq("chat", "completions")))
        .thenRespondAdjust(reply)
    )
    given ModelRegistryService = Llm4sConfig.modelRegistryService().fold(e => fail(e.message), identity)

    val answer = for {
      client <- LLMConnect.getClient(
        OpenAICompatibleConfig("my-model", "http://llm.test/v1"),
        LlmClientOptions.default.withHttpClient(new SttpLlm4sHttpClient(backend))
      )
      completion <- client.complete(Conversation(Seq(UserMessage("What is Scala?"))), CompletionOptions())
    } yield completion.content

    answer shouldBe Right("A JVM language.")
    backend.allInteractions should have size 1
    val (request, _) = backend.allInteractions.head
    request.uri.toString shouldBe "http://llm.test/v1/chat/completions"
    val body = ujson.read(request.forceBodyAsString)
    body("model").str shouldBe "my-model"
    body("messages")(0)("content").str shouldBe "What is Scala?"
  }
}
```

The same stub answers a streamed call: give `thenRespondAdjust` the server-sent events as a string, and
`streamComplete` reads them as the stream. `SttpGuideSpec` in `modules/sttp` runs this example, and a streamed one,
in CI.
