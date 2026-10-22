---
layout: page
title: Java
parent: User Guide
nav_order: 19
---

# Using LLM4S from Java
{: .no_toc }

Call a model from Java with `llm4s-java-api`: a client, a prompt, a conversation, and failures you can read without Scala.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What `llm4s-java-api` is

LLM4S is written in Scala. Its core API returns Scala types (`Either`, `Option`, `Seq`) that are awkward to use from Java.
`llm4s-java-api` is a small layer in front of it for Java callers: `Llm4s` creates a client, `JLlmClient` calls the
model, `ConversationBuilder` builds a conversation, and every call returns an `LlmResult`, a result type modelled on
`java.util.Optional` and `CompletableFuture`. A failed call is a value you check, not an exception you have to catch.

It does not hide every Scala type. `Conversation` and `ProviderConfig` are Scala classes that still appear in its
signatures, as does `LLMError` behind `LlmException.error()`, though [its kind](#handling-a-failure) reads as a Java
enum, and so does core's `CompletionOptions`, in an overload that [`JCompletionOptions`](#completion-options)
makes unnecessary. [What is not here yet](#what-is-not-here-yet) says which of them gets in
your way.

The values it hands you to read - `JCompletion`, `JAgentResult`, `JMessage`, `PendingInterrupt`, `JEmbeddings` and the
rest - are Java classes whose constructors are package-private: you receive them, you do not build them, so a later
release can add a field without breaking code compiled against this one. The classes that do the work come from a
factory: `Llm4s` makes clients and agents, `JCompletionOptions.builder()` and `ConversationBuilder.create()` start a
builder, `LlmResult.success` and `failure` make a result, and the `Answer` factories an answer. Their constructors are
not for you either: each takes a first parameter of a type only the package can name, and throws
`UnsupportedOperationException` when called from anywhere else. To call a Scala `LLMClient` you built yourself - a
`ReliableClient` around a provider's client, your own implementation, a test double - wrap it with
`Llm4s.wrapClient(client)`; a Scala `EmbeddingClient`, with `Llm4s.wrapEmbeddingClient(client, model)`.

{: .note }
> `llm4s-java-api` is on Maven Central from `0.5.0` on; the current release is {{ site.data.project.latest_release }}.
> The [`gradle-java` sample](https://github.com/llm4s/llm4s/tree/main/modules/samples/gradle-java) is a complete Java
> project you can copy. It needs **JDK 21**: it depends on `llm4s-agent`, which is compiled for JDK 21 and starts
> virtual threads (see [Scala and JDK support](/reference/v1-scope#scala-and-jdk-support)). Whether some artifacts get a
> lower floor before 1.0 is tracked in [#1493](https://github.com/llm4s/llm4s/issues/1493).

## Add the dependency

The artifact is `org.llm4s:llm4s-java-api_3`. The `_3` is the Scala binary version, and LLM4S is Scala 3 only. sbt adds
it for you (`%%`); Maven and Gradle do not, so write it yourself.

**Maven**

```xml
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>llm4s-java-api_3</artifactId>
    <version>{{ site.data.project.latest_release }}</version>
</dependency>
```

**Gradle (Kotlin DSL)**

```kotlin
dependencies {
    implementation("org.llm4s:llm4s-java-api_3:{{ site.data.project.latest_release }}")
}
```

**sbt**

```scala
libraryDependencies += "org.llm4s" %% "llm4s-java-api" % "{{ site.data.project.latest_release }}"
```

What arrives with it:

- `llm4s-core`, `llm4s-agent` and the OpenAI, Anthropic, Ollama, Gemini and OpenAI-compatible provider modules, so you do
  not add a provider artifact. Making providers opt-in is tracked in
  [#1496](https://github.com/llm4s/llm4s/issues/1496).
- Scala's standard library (`scala3-library_3` and `scala-library`). If your build pins or excludes it, see the
  [Gradle guide](../getting-started/gradle) for the recipes that keep the two Scala libraries aligned.
- No logging backend. LLM4S logs through SLF4J; add one (for example Logback), or SLF4J prints a warning and drops the
  logs.

## Configure a provider

Java code does not build a provider configuration. It comes from an `application.conf` on your classpath, the same file
Scala users write: a named section per provider, and one section named as the default.

```hocon
# src/main/resources/application.conf
llm4s {
  providers {
    provider = "openai-main"          # the default: the name of a section below

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
    }
  }
}
```

With `OPENAI_API_KEY` set in the environment, that is the whole configuration: the OpenAI module binds the variable to
the shared credential for `openai`, so the section needs only `provider` and `model`. Other providers work the same way;
`anthropic` reads `ANTHROPIC_API_KEY`, and `ollama` needs no key but does need a `baseUrl`:

```hocon
llm4s {
  providers {
    provider = "claude"

    claude {                              # key from ANTHROPIC_API_KEY
      provider = "anthropic"
      model    = "claude-sonnet-4-20250514"
    }

    ollama-local {
      provider = "ollama"
      model    = "llama3.2"
      baseUrl  = "http://localhost:11434"
    }
  }
}
```

The [configuration guide](../getting-started/configuration) lists every key and every provider. Nothing in the library
reads a `LLM_MODEL` variable; the model is the `model` key of the section.

### Configure in code

To build a client in code instead of from a file, `Llm4s` has a builder for each provider this module brings:
`openAi()`, `anthropic()`, `gemini()`, `ollama()`, `openAiCompatible()` (vLLM, LM Studio, LiteLLM, a gateway: any
server that speaks OpenAI's chat-completions API) and `azureOpenAi()`. Each takes plain Java values and builds an
`LlmResult<JLlmClient>`:

```java
LlmResult<JLlmClient> openai = Llm4s.openAi()
    .apiKey(apiKey)
    .model("gpt-4o-mini")
    .build();
LlmResult<JLlmClient> local = Llm4s.ollama()
    .model("llama3.2")
    .build();
LlmResult<JLlmClient> gateway = Llm4s.openAiCompatible()
    .baseUrl("http://localhost:8000/v1")
    .model("qwen2.5-coder")
    .header("X-Team-Id", "platform")
    .build();
```

What each provider needs is required - an API key and a model, for Ollama the model alone, for an OpenAI-compatible
server its base URL and model, for Azure the endpoint, key and deployment - and everything else has the default
`application.conf` would give it: a builder makes the same config as a section with the same values. That means the
provider's own base URL (`http://localhost:11434` for Ollama), and the context window and reply reserve the model
catalogue lists for the model; for an OpenAI-compatible server, the catalogue's window for the provider its base URL's
host names (Groq, Together, Fireworks, xAI, Perplexity), else 8192 tokens. `baseUrl`, `contextWindow`,
`reserveCompletion`, `requestTimeout` and `streamTimeout` (each a `java.time.Duration`, at most about 292 years)
override them, as do `organization` for OpenAI, `apiVersion` for Azure and `header` for an OpenAI-compatible server. A
context window set without a reserve keeps a quarter of itself for the reply, up to the reserve the provider would
otherwise use, as a section's `contextWindow` does. `metrics(MetricsCollector)` makes the client report every call, as
[`createClient(config, metrics)`](#recording-metrics) does.

`build()` sends no request and does not throw. A required field left out - or set to `null` or a blank string, as
`System.getenv` returns for a variable that is not set - is a failed result of kind `VALIDATION` whose message names
it (`Field 'apiKey' is required`), as is a value out of range, such as a context window of zero. A builder is
immutable, so one can be kept and forked: `base.model("gpt-4o")` returns a new builder and leaves `base` as it was.
Its `toString` prints the API key as `***`, an OpenAI-compatible server's headers by name only, and a base URL or
endpoint without its userinfo, query or fragment, so a builder can be logged.

From Kotlin, build the client the same way and hand it to `llm4s-kotlin-api`'s `Llm4s.wrapClient`, which returns
an `LLMClientKt` with suspend functions, `Flow` streaming and `LLMException`
([#1850](https://github.com/llm4s/llm4s/issues/1850)). `wrapClient` takes any `JLlmClient`, so a client from
`createClient(ProviderConfig)` works the same way:

```kotlin
import org.llm4s.kotlin.Llm4s
import org.llm4s.javaapi.Llm4s as JLlm4s

val client = Llm4s.wrapClient(
    JLlm4s.ollama()
        .model("llama3.2")
        .build()
        .get(),   // throws the facade's LlmException on a VALIDATION failure; check isSuccess to handle it
)
client.use { println(it.complete("What is 2 + 2?")) }   // complete is a suspend function: call it in a coroutine
```

For any other provider - one from its own module, such as `llm4s-bedrock` - `Llm4s.createClient(ProviderConfig)` takes
the provider's config object. OpenAI, Anthropic and Ollama have one with a Java entry point too, which takes the
essentials and fills in the rest (the default base URL, and a context window and completion reserve guessed from the
model name):

```java
LlmResult<JLlmClient> openai = Llm4s.createClient(OpenAIConfig.apply(apiKey, "gpt-4o-mini"));
LlmResult<JLlmClient> anthropic = Llm4s.createClient(AnthropicConfig.apply(apiKey, "claude-sonnet-4-20250514"));
LlmResult<JLlmClient> local = Llm4s.createClient(OllamaConfig.apply("llama3.2", "http://localhost:11434"));
```

(The imports are listed under [The snippets' imports](#the-snippets-imports).) Reading the key is up to you; creating the client sends no
request. The `with` methods of each config (`withContextWindow`, `withReserveCompletion`, and for OpenAI
`withBaseUrl` and `withOrganization`) change one field at a time; some take a Scala `Option`, which the builders above
avoid.

### Recording metrics

`Llm4s.createClient(ProviderConfig, MetricsCollector)` builds a client that reports every call (its outcome, duration,
tokens and cost) to a `MetricsCollector`:

```java
LlmResult<JLlmClient> measured = Llm4s.createClient(OllamaConfig.apply("llama3.2", "http://localhost:11434"), metrics);
```

For `metrics`, use `new MicrometerMetrics(meterRegistry)` from `llm4s-observability-micrometer` to record into the
application's Micrometer `MeterRegistry` (see [Micrometer metrics](observability/micrometer)), or `PrometheusMetrics`
from `llm4s-observability-prometheus`. A null collector records nothing. The Spring Boot starter does this for you when
the application has a `MeterRegistry`.

A [client builder](#configure-in-code) takes the collector too: `Llm4s.ollama().model("llama3.2").metrics(metrics).build()`.

## Your first call

```java
import java.io.PrintStream;

import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;

public final class HelloLlm4s {

    private HelloLlm4s() {}

    public static void main(String[] args) {
        System.exit(run(System.out, System.err));
    }

    static int run(PrintStream out, PrintStream err) {
        LlmResult<JLlmClient> created = Llm4s.createDefaultClient();
        if (created.isFailure()) {
            err.println("Could not create a client: " + created.getError().getMessage());
            return 1;
        }

        // JLlmClient is AutoCloseable: it holds an HTTP client, so close it.
        try (JLlmClient client = created.get()) {
            LlmResult<String> answer = client.complete("What is a monad? Answer in one sentence.");
            answer
                .ifSuccess(text -> out.println(text))
                .ifFailure(error -> err.println("The call failed: " + error.getMessage()));
            return answer.isSuccess() ? 0 : 1;
        }
    }
}
```

`Llm4s.createDefaultClient()` loads the default provider section and builds a client from it. It does not throw: a
missing section, a missing API key or an unknown provider is a failed `LlmResult`, and its message says what to fix (for
a missing key it names the environment variable and the section). `JLlmClient` is `AutoCloseable` and closing it closes
the underlying client, so use try-with-resources.

`ifSuccess` and `ifFailure` each take a lambda and return the result, so they chain. The `run` method is separate from
`main` so that a test can pass its own streams.

The [`gradle-java` sample](https://github.com/llm4s/llm4s/tree/main/modules/samples/gradle-java) is this program in a
complete Gradle project.

### The snippets' imports

The snippets below leave out their imports. Together they use these:

```java
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import org.llm4s.javaapi.CompletionStream;
import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.JCompletion;
import org.llm4s.javaapi.JCompletionOptions;
import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JEmbeddingPurpose;
import org.llm4s.javaapi.JEmbeddings;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.JReasoningEffort;
import org.llm4s.javaapi.JTool;
import org.llm4s.javaapi.JToolCall;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;
import org.llm4s.javaapi.Param;
import org.llm4s.javaapi.Tool;
import org.llm4s.llmconnect.config.AnthropicConfig;
import org.llm4s.llmconnect.config.OllamaConfig;
import org.llm4s.llmconnect.config.OpenAIConfig;
import org.llm4s.llmconnect.model.Conversation;
import org.llm4s.metrics.MetricsCollector;
```

## A conversation

A conversation is a list of messages. `ConversationBuilder` builds one without Scala syntax:

```java
Conversation conversation = ConversationBuilder.create()
    .system("You answer in one short sentence.")
    .user("What is a monad?")
    .build();

LlmResult<String> answer = client.complete(conversation);
System.out.println(answer.get());
```

The builder has `system`, `user` and `assistant`, and messages are sent in the order you add them. It is immutable: each
method returns a new builder, so one can be shared and extended. A `null` message throws `NullPointerException` at once.

## Completion options

`JCompletionOptions` sets how the model answers one request, built with a builder and passed with the conversation:

```java
JCompletionOptions options = JCompletionOptions.builder()
    .temperature(0.2)
    .maxTokens(512)
    .reasoning(JReasoningEffort.MEDIUM)
    .build();

LlmResult<String> answer = client.complete(conversation, options);
System.out.println(answer.get());
```

The builder has `temperature`, `topP`, `maxTokens`, `presencePenalty`, `frequencyPenalty`, `reasoning` (the Java enum
`JReasoningEffort`: `NONE`, `LOW`, `MEDIUM` or `HIGH`) and `budgetTokens`, an explicit thinking budget that overrides
the one the reasoning level implies. The providers that take a budget are Anthropic, and OpenRouter for Claude models:
both raise a budget below `1024` to `1024` and keep it below the token limit, and a budget set with no reasoning level
still turns thinking on. A setting you leave out keeps the library's default:
temperature `0.7`, top-p `1.0`, no penalties, and no token limit, reasoning level or thinking budget. The built options
have an accessor of the same name for each setting. `maxTokens()` and `budgetTokens()` return an `OptionalInt` and
`reasoning()` an `Optional<JReasoningEffort>`, empty when unset. Their setters also take an `OptionalInt` or `Optional`,
so an empty one clears the value.

The builder is immutable like `ConversationBuilder`: each setter returns a new builder, and `options.toBuilder()` starts
one from existing options. A value no provider accepts throws `IllegalArgumentException` at once: a negative or
non-finite temperature, a top-p outside `0` to `1`, a non-finite penalty, or a token count below `1`. A `null` throws
`NullPointerException`. A value only some models reject, such as a temperature above `1` for some models, is left to
the provider. A model that does not reason ignores `reasoning`. Tools and response formats are not options here yet.

## The whole reply

`complete` returns the reply's text. `completion` takes the same arguments - a query, a conversation, or a conversation
and `JCompletionOptions` - and returns the whole reply as a `JCompletion`: the model that answered, the tokens it used,
its estimated cost and the tool calls it asked for, alongside the text:

```java
JCompletion reply = client.completion(conversation).get();

System.out.println(reply.model() + ": " + reply.content());
reply.usage().ifPresent(usage ->
    System.out.println(usage.promptTokens() + " tokens in, " + usage.completionTokens() + " out"));
reply.estimatedCost().ifPresent(cost -> System.out.println("about $" + cost.toPlainString()));
for (JToolCall call : reply.toolCalls()) {
    System.out.println("wants " + call.name() + " " + call.argumentsJson());
}
```

`model()` is the model as the provider names it, which can be more specific than the one you configured. `usage()` is a
`JTokenUsage` with `promptTokens()`, `completionTokens()`, `totalTokens()` and `thinkingTokens()`, and the prompt-cache
counts `cachedTokens()` (input read from the provider's prompt cache, billed at the cheaper cache-read rate) and
`cacheCreationTokens()` (input written into it, typically billed above the normal input rate) - each an `int`, zero when
the model reported none - or empty when the provider reported no usage. `estimatedCost()` is a `java.math.BigDecimal` in USD, empty
when the cost is not known; it is the figure an agent adds to its `usage().totalCost()`. `toolCalls()` lists `JToolCall`s,
the same type an agent's messages use, with the arguments as JSON text; `thinking()` is the model's reasoning text when
it reported one. A `JCompletion` is a value with `equals`, `hashCode` and a `toString` that prints the full text.

The method is `completion`, a noun, because it returns the reply rather than performs a request for its text; `complete`
keeps returning a `String`. There is no `completion` overload for core's Scala `CompletionOptions`, so
`client.completion(conversation, null)` compiles, and returns a failed result like any other `null` argument.

## Streaming the reply

`stream` sends the same arguments as `complete` - a query, a conversation, or a conversation and `JCompletionOptions` -
with a `CompletionStreamListener`, and returns at once. The listener receives each piece of the reply's text as it
arrives (`onPartial`), then the whole reply (`onComplete`, a `JCompletion`) or the error (`onError`); only `onPartial` is
abstract, so a lambda or a method reference is a listener:

```java
JCompletion reply;
try (CompletionStream stream = client.stream(conversation, System.out::print).get()) {
    reply = stream.await().get();
}
System.out.println();

Flow.Publisher<String> pieces = client.streamAsPublisher(conversation);
```

The returned `CompletionStream` awaits the reply (`await()`, the same outcome the listener heard) and cancels the call
(`cancel()`, or `close()`, so try-with-resources stops a call its block leaves). Cancelling interrupts the provider's
thread, which is how llm4s providers stop, and the listener then hears `onError` with a `CANCELLED` error. Listener calls
are made one at a time, never on your thread; one that throws stops the call, and `onError` receives what it threw.

`streamAsPublisher` returns the text as a `java.util.concurrent.Flow.Publisher<String>` instead: it is in the JDK, so
Reactor (`JdkFlowAdapter`), RxJava and Mutiny adapt it without another llm4s dependency. Each subscription makes its own
call. A subscriber slower than the model holds the provider's thread rather than losing text, and cancelling the
subscription cancels the call. Streaming yields text only; tool-call deltas are not streamed.

## Structured output

Pass a record class after the query, or after the conversation (and options), and `complete` returns an instance of
it instead of text. The record's components become a JSON Schema that is sent as the structured-output format, and
the answer is read back into the record:

```java
record City(String name, String country, int population) {}

City city = client.complete("The largest city in Japan, with its population", City.class).get();
System.out.println(city.name() + ", " + city.country() + ": " + city.population());
```

A component can be a `String`, an `int`, `long`, `short` or `byte` (boxed or not), a `double` or `float`, a
`boolean`, an enum (the model answers with a constant's name), another record, a `java.util.List` of any of these, or an
`Optional` of any of these. `Optional` is the only way to let the model answer `null` or leave a field out; every other
field is required. A `Map`, an array, a raw `List`, an interface or a record that contains itself is refused before
any call, and an answer that does not fit - a missing field, the wrong JSON type, a fraction for an `int`, an unknown
enum name - is a failed result whose message names the field, such as `field 'previous[0].city': expected a string,
got a number`. Numbers pass through a `double`, so ask for an identifier above 2^53 as a `String`.

How firmly the schema binds the model is the provider's: OpenAI-style providers send it as a native JSON Schema,
Anthropic adds it to the system prompt, and watsonx and Bedrock ignore it, so the answer is still checked when it comes
back. See [Structured output](structured-output) for each provider.

## Reading a result

Every `complete` call returns an `LlmResult<String>`, and every `completion` call an `LlmResult<JCompletion>`. There
are several ways to take the value out, depending on how you want to treat a failure:

```java
LlmResult<String> result = client.complete("What is 2+2?");

String text = result.get();                       // the value, or throws LlmException
String orNull = result.getOrNull();               // the value, or null when the call failed
Optional<String> optional = result.toOptional();  // Optional.empty() when the call failed
LlmResult<Integer> length = result.map(String::length);
CompletableFuture<String> future = result.toCompletableFuture();
```

`isSuccess()` and `isFailure()` test it, `getError()` returns the `LlmException` of a failure (or `null`), and `map`
transforms a success and passes a failure through. `toCompletableFuture()` returns a future that is already complete: it
makes a result fit an API that expects a future, and does not make the call asynchronous (see
[What is not here yet](#what-is-not-here-yet)).

## Handling a failure

A call that fails does not throw. `JLlmClient` turns a failure of the provider, the network or the library into a failed
`LlmResult`, and a `null` argument into a failed result too. Only `get()` throws, and it throws `LlmException`, an
unchecked exception that says what went wrong in Java types:

```java
try {
    String text = client.complete("What is 2+2?").get();
    System.out.println(text);
} catch (LlmException e) {
    System.err.println(e.getMessage());
    switch (e.getKind()) {
        case AUTHENTICATION, CONFIGURATION -> System.err.println("check the API key and the provider section");
        case RATE_LIMIT -> System.err.println("rate limited; wait "
            + e.getRetryAfter().map(d -> d.toSeconds() + " s").orElse("a while"));
        case SERVICE -> System.err.println("the provider answered HTTP "
            + (e.getStatusCode().isPresent() ? e.getStatusCode().getAsInt() : "?"));
        default -> { }
    }
    if (e.isRecoverable()) {
        System.err.println("a retry may succeed");
    }
}
```

`getKind()` is the Java enum `LlmErrorKind`:

| Kind | The failure | Recoverable |
|---|---|---|
| `AUTHENTICATION` | the provider rejected the API key | no, unless it is a provider's `401` or `403` reported as a service error |
| `RATE_LIMIT` | the provider, or a local limiter, refused the call for now | yes, unless it is an embedding provider's error |
| `TIMEOUT` | the call did not finish in time | yes |
| `NETWORK` | the provider could not be reached | yes |
| `SERVICE` | the provider answered with an error | yes, unless it is an embedding provider's error |
| `VALIDATION` | the request is wrong: a missing or invalid argument | no, unless it is a provider's `400` reported as a service error |
| `CONFIGURATION` | a setting is missing or invalid | no |
| `CANCELLED` | the call was cancelled, for example by an interrupt | no |
| `OTHER` | anything else, including an error from another llm4s module or your own code | depends on the error |

A provider's error response with status `400`, `401`/`403` or `429` has the kind that status means (`VALIDATION`,
`AUTHENTICATION`, `RATE_LIMIT`) even when a provider client reported it as a generic service error. Such an error keeps
the recoverability of its class: a `ServiceError` or an `APIError` is recoverable whatever its status, so a `403`
reported as one reads as `AUTHENTICATION` and `isRecoverable()` is `true`. The table's column is what the kind's own
error classes say; to decide whether to try again, always call `isRecoverable()` rather than infer it from the kind.
Every error class in `org.llm4s.error` has a kind, and a test fails when a new one is added without one.
An [embedding](#embeddings) provider's error response has the kind of its HTTP status in the same way, but llm4s does
not call any embedding error recoverable, so `isRecoverable()` is `false` for it whatever its kind.

`isRecoverable()` says whether the call may succeed if tried again, perhaps after you do something first: wait out a rate
limit, or correct a request the provider rejected. `getRetryAfter()` is the `Optional<Duration>` the provider asked you
to wait (its `Retry-After` header), empty when it did not say, and `getStatusCode()` the `OptionalInt` HTTP status of a
provider's error response. If the error carries a `Throwable`, it is the exception's `getCause()`.

`e.error()` is still the llm4s error itself, a Scala `LLMError` whose `message()` and `formatted()` (the message plus its
code and context) are plain methods, and whose classes - `RateLimitError`, `ServiceError` and the rest - you can test
with `instanceof` for a detail the kind does not carry. The [error handling guide](error-handling) has the full list
and the recovery tools, written for Scala.

If the thread blocked in `complete` is interrupted, the call returns a failed result whose error is a `CancelledError`,
with the thread's interrupt flag still set. `InterruptedException` is never thrown, so the method does not declare it
and Java will not let you write `catch (InterruptedException e)` around the call; test the result for a
`CancelledError` instead. The [threading and cancellation guide](java-threading-and-cancellation) has the details.

## An agent turn

`Llm4s.createAgent(client)` wraps a client in a `JAgent`. `run` and `continueConversation` block, like `complete`, and
return an `LlmResult<JAgentResult>`. A `JAgentResult` is read with JDK types and this module's own: `answer()` is an
`Optional<String>`, `messages()` a `java.util.List<JMessage>`, and `status().kind()` the Java enum `AgentStatusKind`, so
a `switch` covers every way a turn ends:

```java
JAgent agent = Llm4s.createAgent(client);
JAgentResult result = agent.run("What is 2+2?").get();

switch (result.status().kind()) {
    case COMPLETED -> System.out.println(result.answer().orElseThrow());
    case BLOCKED -> System.out.println("Blocked by " + result.status().guardrail().orElseThrow());
    case STEP_LIMIT_REACHED -> System.out.println("Hit the step limit");
    case TOKEN_LIMIT_REACHED -> System.out.println("Hit the token budget");
    case SUSPENDED -> System.out.println("Waiting for " + result.status().pending().size() + " answers");
}
for (JMessage message : result.messages()) {
    System.out.println(message.role() + ": " + message.content());
}
JUsageSummary usage = result.usage();
System.out.println(usage.inputTokens() + " tokens in, " + usage.outputTokens() + " out");

JAgentResult next = agent.continueConversation(result, "And 3+3?").get();
```

Each status's data has its own accessor, empty for the other kinds: `answer()` for `COMPLETED`, `guardrail()` and
`reason()` for `BLOCKED`, and `pending()` for `SUSPENDED` - the approvals and questions the turn waits for, which the
agent guide's [suspended turns](agents/#suspended-turns-from-java-and-kotlin) section answers. A `JMessage` has a
`role()` (the Java enum `JMessageRole`), its `content()`, the `toolCalls()` an assistant message asked for, with their
arguments as JSON text (an object, as a model sends them; a call built with a `ujson.Str` renders as a JSON string
literal), and the `toolCallId()` a tool message answers. `usage()` counts tokens as `long`s, the cost as a
`java.math.BigDecimal` (two usages are equal when their costs are numerically equal, whatever the scale), and
`byModel()` breaks both down per model. No accessor returns a Scala or `ujson` type.

These types are values, but not `Serializable`. Their `toString` prints the full text - a message's content, a tool
call's arguments, an answer or a guardrail's reason - as the Scala types do, so mind what you log.

## Tools

An agent can call tools you write in Java, in either of two styles. Annotate the public methods of a class with `@Tool`,
and each parameter with `@Param` (its `name` is what the model passes; without one, the compiled name is used, which
needs `javac -parameters`):

```java
class WeatherTools {
    @Tool(description = "The current weather in a city")
    public String weather(@Param(name = "city", description = "City name") String city) {
        return "Sunny in " + city;
    }
}
```

or build one from a lambda with `JTool.builder`, then give the tools to `Llm4s.createAgentWithTools`:

```java
List<JTool> weather = JTool.fromAnnotated(new WeatherTools()).get();
LlmResult<JTool> add = JTool.builder("add", "Adds two numbers")
    .param("a", long.class, "First")
    .param("b", long.class, "Second")
    .handler(args -> args.getLong("a") + args.getLong("b"))
    .build();

JAgent agent = Llm4s.createAgentWithTools(client, List.of(weather.get(0), add.get())).get();
JAgentResult result = agent.run("What is the weather in Paris?").get();
```

A parameter is a `String`, an `int`, `long`, `short` or `byte` (boxed or not), a `double` or `float`, a `boolean`, or an
enum (the model passes a constant's name); its JSON Schema is derived from the type, and `optionalParam` (or
`@Param(required = false)`, with a boxed type) lets the model leave it out. The model's arguments are read into those Java
values before your code runs, with no JSON library: a missing or mistyped argument is a tool error the model sees, and so
is an exception your method or lambda throws - the agent keeps running and the model can try again. A result is passed to
the model as text if it is a `String`; a number, boolean, enum, `Map` or `Collection` becomes JSON. A tool that cannot be
built (no handler, an unsupported parameter type, a parameter declared twice) and an agent given two tools with one name
are failed results, not exceptions.

## Embeddings

An embedding model turns a text into a vector, a `float[]`, and texts that mean similar things get vectors that point
in similar directions: the basis of semantic search. `Llm4s.createDefaultEmbeddingClient()` creates a
`JEmbeddingClient` for the model `application.conf` names, as `createDefaultClient()` does for chat. The model is
`provider/model` under `llm4s.embeddings`, or the `EMBEDDING_MODEL` environment variable:

```hocon
llm4s {
  embeddings {
    model = "openai/text-embedding-3-small"   # or set EMBEDDING_MODEL
  }
}
```

The key comes from the vendor's variable, `OPENAI_API_KEY` here, as a chat section's does. The embedding providers
`llm4s-java-api` brings are `openai` and `ollama`; Voyage, Jina and Cohere come with their own modules
(`llm4s-voyage`, `llm4s-jina`, `llm4s-cohere`). Creating the client sends no request, and does not throw:

```java
LlmResult<JEmbeddingClient> created = Llm4s.createDefaultEmbeddingClient();
created.ifFailure(error -> System.err.println("Could not create an embedding client: " + error.getMessage()));
JEmbeddingClient embedder = created.getOrNull();   // null when it failed
```

No model configured, a provider that is not on the classpath or a missing key is a failed result of kind
`CONFIGURATION`, whose message says what to set. `embedder.model()` is the model as configured, and
`embedder.dimensions()` an `OptionalInt`: its vector length as the provider's module declares it, or empty for a model
the module does not list, such as an Ollama model you pulled yourself - that model still works, and its vectors are as
long as the provider makes them.

To embed with a model other than the one `application.conf` names - two models side by side, say -
`Llm4s.createEmbeddingClient` takes it as `provider/model`. The provider's key, base URL and timeouts still come from
configuration, as for the default client, so `llm4s.embeddings.model` need not be set at all:

```java
JEmbeddingClient large = Llm4s.createEmbeddingClient("openai/text-embedding-3-large").get();
JEmbeddingClient local = Llm4s.createEmbeddingClient("ollama/bge-m3").get();   // dimensions() is empty
```

A `null` model is a failed result of kind `VALIDATION`; one not in the form `provider/model`, a provider that is not on
the classpath or a missing key, one of kind `CONFIGURATION`.

`embed` takes a `java.util.List<String>` and returns a `JEmbeddings`: one vector per text, in the order of the texts,
from a single request. `JEmbeddings.cosineSimilarity` compares two of them:

```java
JEmbeddings embeddings = embedder.embed(List.of(
    "The cat sat on the mat.",
    "A kitten was sitting on the rug.")).get();

List<float[]> vectors = embeddings.vectors();
double similarity = JEmbeddings.cosineSimilarity(vectors.get(0), vectors.get(1));
System.out.println(embeddings.model() + ", " + embeddings.dimensions() + " dimensions: similarity " + similarity);
```

`model()` is the model as the provider named it in its reply, and `dimensions()` the length of every vector.
`vectors()` returns a new unmodifiable list of new arrays on each call, so changing one changes nothing; call it once and
keep the list. A `JEmbeddings` is a value, with `equals` and `hashCode` over its vectors' components. Core's vectors
are `double`s; these are their nearest `float`s, the precision vector stores keep.

`cosineSimilarity` goes from `-1` (opposite) through `0` (unrelated) to `1` (the same direction). A zero vector has no
direction, so its similarity to anything is `0`. Two vectors of different lengths, which come from different models,
throw `IllegalArgumentException`, and a `null` throws `NullPointerException`.

Several embedding models embed a search query differently from the documents it searches, and comparing a query
embedded as a document quietly finds worse matches. Say which side a text is on with `JEmbeddingPurpose`:

```java
List<float[]> documents = embedder.embed(texts, JEmbeddingPurpose.DOCUMENT).get().vectors();
float[] query = embedder.embed(List.of("Where did the cat sit?"), JEmbeddingPurpose.QUERY).get().vectors().get(0);

int best = 0;
for (int i = 1; i < documents.size(); i++) {
    if (JEmbeddings.cosineSimilarity(query, documents.get(i)) > JEmbeddings.cosineSimilarity(query, documents.get(best))) {
        best = i;
    }
}
System.out.println("closest: " + texts.get(best));
```

`embed(texts)` without a purpose embeds documents. Voyage and Cohere send the purpose as `input_type` and Jina as
`task`; OpenAI and Ollama embed both alike and ignore it, so code that says which side it is on works with any of them.

Like `complete`, `embed` blocks and never throws: an empty list returns no vectors without a request, a `null` list,
text or purpose is a failed result of kind `VALIDATION`, and an interrupt one of kind `CANCELLED`. A provider's error
response has the kind of its HTTP status - `401` reads as `AUTHENTICATION`, `429` as `RATE_LIMIT`, `400` as
`VALIDATION`, any other as `SERVICE`, with `getStatusCode()`. A provider that cannot be reached is `NETWORK`, and one
that does not answer in time `TIMEOUT`; any other failure with no status, such as a reply that cannot be read, is
`OTHER`. To keep the vectors in a store, search them and answer questions from what they find, see
[Java RAG](java-rag) (`llm4s-java-rag`).

## What is not here yet

`llm4s-java-api` covers a client, a conversation, the whole reply, the kind of a failure and embeddings. These are not
available from Java yet, each with the issue that tracks it:

| You may expect | State today |
|---|---|
| An asynchronous call | none; wrap `complete` yourself, or use the [Spring Boot starter](spring-boot)'s `completeAsync`. Threading and cancellation are being documented in [#1500](https://github.com/llm4s/llm4s/issues/1500) |
| Agents beyond a turn | [An agent turn](#an-agent-turn) reads a result; the agent guide covers [streaming a turn](agents/streaming#java-and-kotlin) and [suspended turns](agents/#suspended-turns-from-java-and-kotlin) from Java. Tools are defined from Java in [Tools](#tools) |
| A fake client for your own tests | [#1497](https://github.com/llm4s/llm4s/issues/1497) |

## Where next

- [Java RAG](java-rag): ingest documents and ask questions about them, with `llm4s-java-rag`.
- [Spring Boot](spring-boot): auto-configuration, a template bean and a health indicator on top of this module.
- [Basic usage](basic-usage) and [providers](providers): the same concepts in Scala, and every provider's configuration.
- [Gradle integration](../getting-started/gradle): dependency recipes for Gradle builds.
- The [`gradle-java` sample](https://github.com/llm4s/llm4s/tree/main/modules/samples/gradle-java): a Java project you
  can run.
