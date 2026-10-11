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

{: .note }
> **Not published yet.** `llm4s-java-api` is not on Maven Central: the latest release, `0.4.1`, is a single
> `llm4s-core` artifact, and the Java API is planned to ship with `0.5.0`. Until then, build it from a checkout of the
> repository; the
> [`gradle-java` sample](https://github.com/llm4s/llm4s/tree/main/modules/samples/gradle-java) has the steps (its "Run
> it" section) and is a complete Java project you can copy. Which JDK is the minimum is not settled either (CI runs
> JDK 21 only today): follow [#1493](https://github.com/llm4s/llm4s/issues/1493).

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

`Llm4s.createClient(ProviderConfig)` builds a client from a config object instead of a file. For OpenAI, Anthropic and
Ollama the config classes have a Java entry point that takes the essentials and fills in the rest (the default base
URL, and a context window and completion reserve guessed from the model name):

```java
LlmResult<JLlmClient> openai = Llm4s.createClient(OpenAIConfig.apply(apiKey, "gpt-4o-mini"));
LlmResult<JLlmClient> anthropic = Llm4s.createClient(AnthropicConfig.apply(apiKey, "claude-sonnet-4-20250514"));
LlmResult<JLlmClient> local = Llm4s.createClient(OllamaConfig.apply("llama3.2", "http://localhost:11434"));
```

(The imports are listed under [The snippets' imports](#the-snippets-imports).) Reading the key is up to you; creating the client sends no
request. The `with` methods of each config (`withContextWindow`, `withReserveCompletion`, and for OpenAI
`withBaseUrl` and `withOrganization`) change one field at a time. The other providers (Gemini, Azure, the
OpenAI-compatible family and so on) have no such entry point yet, so configure them in `application.conf`.

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

import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.JCompletion;
import org.llm4s.javaapi.JCompletionOptions;
import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JEmbeddingPurpose;
import org.llm4s.javaapi.JEmbeddings;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.JReasoningEffort;
import org.llm4s.javaapi.JToolCall;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;
import org.llm4s.llmconnect.config.AnthropicConfig;
import org.llm4s.llmconnect.config.OllamaConfig;
import org.llm4s.llmconnect.config.OpenAIConfig;
import org.llm4s.llmconnect.model.Conversation;
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

No model configured, a provider that is not on the classpath, a missing key, or a model whose provider module does
not declare its dimensions is a failed result of kind `CONFIGURATION`, whose message says what to set.
`embedder.model()` and `embedder.dimensions()` are the model and its vector length as configured.

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
`VALIDATION`, any other as `SERVICE`, with `getStatusCode()` - and one with no status, such as a provider that could not
be reached, is `OTHER`. A vector store to keep the vectors in and search them is
[#1491](https://github.com/llm4s/llm4s/issues/1491).

## What is not here yet

`llm4s-java-api` covers a client, a conversation, the whole reply, the kind of a failure and embeddings. These are not
available from Java yet, each with the issue that tracks it:

| You may expect | State today |
|---|---|
| Streaming tokens | `JLlmClient` has blocking `complete` calls only: [#1485](https://github.com/llm4s/llm4s/issues/1485) |
| An asynchronous call | none; wrap `complete` yourself, or use the [Spring Boot starter](spring-boot)'s `completeAsync`. Threading and cancellation are being documented in [#1500](https://github.com/llm4s/llm4s/issues/1500) |
| Structured output into a Java record | [#1486](https://github.com/llm4s/llm4s/issues/1486) |
| Defining tools | an agent takes a Scala `ToolRegistry`: [#1484](https://github.com/llm4s/llm4s/issues/1484) |
| Agents beyond a turn | [An agent turn](#an-agent-turn) reads a result; the agent guide covers [streaming a turn](agents/streaming#java-and-kotlin) and [suspended turns](agents/#suspended-turns-from-java-and-kotlin) from Java. Tools still need a Scala `ToolRegistry` (row above) |
| A vector store and RAG | [Embeddings](#embeddings) are here; storing and searching them is [#1491](https://github.com/llm4s/llm4s/issues/1491) |
| A fake client for your own tests | [#1497](https://github.com/llm4s/llm4s/issues/1497) |

## Where next

- [Spring Boot](spring-boot): auto-configuration, a template bean and a health indicator on top of this module.
- [Basic usage](basic-usage) and [providers](providers): the same concepts in Scala, and every provider's configuration.
- [Gradle integration](../getting-started/gradle): dependency recipes for Gradle builds.
- The [`gradle-java` sample](https://github.com/llm4s/llm4s/tree/main/modules/samples/gradle-java): a Java project you
  can run.
