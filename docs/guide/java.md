---
layout: page
title: Java
parent: User Guide
nav_order: 6
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

It does not hide every Scala type. `Conversation`, `CompletionOptions`, `ProviderConfig` and `LLMError` are Scala
classes that still appear in its signatures. [What is not here yet](#what-is-not-here-yet) says which of them gets in
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.llm4s.error.LLMError;
import org.llm4s.error.RecoverableError;
import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.JLlmClient;
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

## Reading a result

Every call returns an `LlmResult<String>`. There are several ways to take the value out, depending on how you want to
treat a failure:

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
unchecked exception that carries the llm4s error:

```java
try {
    String text = client.complete("What is 2+2?").get();
    System.out.println(text);
} catch (LlmException e) {
    LLMError error = e.error();                // the llm4s error: a Scala type
    System.err.println(error.message());       // the same text as e.getMessage()
    System.err.println(error.formatted());     // the message plus its code and context
    if (error instanceof RecoverableError) {
        System.err.println("a retry may succeed");
    }
}
```

`LLMError` is a Scala trait, but `message()` and `formatted()` are plain methods you can call from Java. If the error
carries a `Throwable`, it is the exception's `getCause()`.

Errors are classes you can test with `instanceof`. The ones that may succeed if tried again, perhaps after you do
something first, implement `RecoverableError`: `RateLimitError`, `TimeoutError`, `NetworkError`, `APIError` and
`ServiceError` among them. `AuthenticationError`, `ConfigurationError` and `ValidationError` implement
`NonRecoverableError`: retrying the same request will not help. The [error handling guide](error-handling) has the full
list and the recovery tools, written for Scala.

If the call is interrupted, `InterruptedException` is not turned into a failed result: it propagates out of `complete`. The method does not declare it, so Java will not let you catch it by name; catch `Exception` if you need to.

## What is not here yet

`llm4s-java-api` covers a client and a conversation. These are not available from Java yet, each with the issue that
tracks it:

| You may expect | State today |
|---|---|
| Completion options (temperature, max tokens, reasoning) | `complete(Conversation, CompletionOptions)` exists, but a `CompletionOptions` takes `scala.Option` and `Seq` arguments to construct: [#1488](https://github.com/llm4s/llm4s/issues/1488) |
| Streaming tokens | `JLlmClient` has blocking `complete` calls only: [#1485](https://github.com/llm4s/llm4s/issues/1485) |
| An asynchronous call | none; wrap `complete` yourself, or use the [Spring Boot starter](spring-boot)'s `completeAsync`. Threading and cancellation are being documented in [#1500](https://github.com/llm4s/llm4s/issues/1500) |
| Structured output into a Java record | [#1486](https://github.com/llm4s/llm4s/issues/1486) |
| Defining tools | an agent takes a Scala `ToolRegistry`: [#1484](https://github.com/llm4s/llm4s/issues/1484) |
| Agents | `Llm4s.createAgent` and `JAgent` exist, but the Java agent API is being reworked ([#1386](https://github.com/llm4s/llm4s/pull/1386), [#1392](https://github.com/llm4s/llm4s/issues/1392), [#1393](https://github.com/llm4s/llm4s/issues/1393)), so this guide does not cover it yet |
| Embeddings and RAG | [#1490](https://github.com/llm4s/llm4s/issues/1490), [#1491](https://github.com/llm4s/llm4s/issues/1491) |
| A fake client for your own tests | [#1497](https://github.com/llm4s/llm4s/issues/1497) |

## Where next

- [Spring Boot](spring-boot): auto-configuration, a template bean and a health indicator on top of this module.
- [Basic usage](basic-usage) and [providers](providers): the same concepts in Scala, and every provider's configuration.
- [Gradle integration](../getting-started/gradle): dependency recipes for Gradle builds.
- The [`gradle-java` sample](https://github.com/llm4s/llm4s/tree/main/modules/samples/gradle-java): a Java project you
  can run.
