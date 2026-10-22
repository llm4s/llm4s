---
layout: page
title: First-Run Troubleshooting
parent: Getting Started
nav_order: 11
---

# First-Run Troubleshooting

The problems people most often meet when an LLM4S program does not work the first time, each with the symptom, the
cause and the fix. The message texts below are the ones the code produces (the class that builds each is named), so
you can search this page for the text you see.

Two tools help before you read on:

- **The doctor** checks your JDK, configuration, default provider, provider module, API key and (with `--local`) a
  local model server in one run, and says what to change. See [Doctor](/reference/doctor).
- **The rest of the reference** has [Troubleshooting / FAQ](/reference/troubleshooting) for later problems (agents,
  streaming, RAG, tracing) and [Configuration: Troubleshooting](configuration#troubleshooting).

## Contents

1. [The API key is missing](#1-the-api-key-is-missing)
2. [No default provider configured](#2-no-default-provider-configured)
3. [Configured provider was not found](#3-configured-provider-was-not-found)
4. [Provider is not registered](#4-provider-is-not-registered)
5. [application.conf is not picked up](#5-applicationconf-is-not-picked-up)
6. [LLM_MODEL has no effect](#6-llm_model-has-no-effect)
7. [The code does not compile: no ModelRegistryService](#7-the-code-does-not-compile-no-modelregistryservice)
8. [Ollama is not running](#8-ollama-is-not-running)
9. [Ollama does not have the model](#9-ollama-does-not-have-the-model)
10. [UnsupportedClassVersionError or a JDK that is too old](#10-unsupportedclassversionerror-or-a-jdk-that-is-too-old)
11. [Authentication failed (401 or 403)](#11-authentication-failed-401-or-403)
12. [Rate limited (429)](#12-rate-limited-429)
13. [Timeouts](#13-timeouts)

## 1. The API key is missing

**Symptom** (a `ConfigurationError`, from `Llm4sConfig.defaultProvider()` or `Llm4sConfig.provider(...)`):

```text
Provider 'openai-main' (provider = openai) is missing required fields:
  - apiKey: set OPENAI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf
```

**Cause.** Neither the vendor's variable nor an `apiKey` in the section is available to the JVM. The variable named
is the one the provider's module binds (`llm4s-openai` binds `OPENAI_API_KEY`); a section that sets its own `apiKey`
uses that instead. A variable exported in one terminal is not seen by an IDE run configuration started elsewhere.

**Fix.** Export the variable in the shell that starts the JVM (or set it in the IDE run configuration), or set
`apiKey` in the section. The error names the section it is about: if it is not the one you meant, check
`llm4s.providers.provider`. See [API keys](configuration#api-keys).

## 2. No default provider configured

**Symptom** (a `ConfigurationError`):

```text
No default provider configured under llm4s.providers.provider
```

**Cause.** `Llm4sConfig.defaultProvider()` loads the section that `llm4s.providers.provider` names, and the key is
not set, usually because no `application.conf` defines it.

**Fix.** Add the key to your `application.conf` (see [Named provider sections](configuration#named-provider-sections)),
or load a section by name with `Llm4sConfig.provider("<name>")`.

## 3. Configured provider was not found

**Symptom** (a `ConfigurationError`):

```text
Configured provider 'openai-main' was not found
```

**Cause.** `llm4s.providers.provider` (or the name you passed to `Llm4sConfig.provider(...)`) names a section that
does not exist, for example through a typo, or a `-Dllm4s.providers.provider=...` override.

**Fix.** Make the name match a section under `llm4s.providers` exactly.

## 4. Provider is not registered

**Symptom** (a `ConfigurationError`, from `LLMConnect.getClient`):

```text
Provider 'openai' is not registered. Registered providers: none. If you expected 'openai', add the dependency that supplies it, or register it explicitly with ProviderRegistry.of(...).
```

When the id came from a config section, the message also says which key it came from. It ends with a line about
classpath discovery, such as `Discovery scanned 0 modules; 0 failed.`

**Cause.** Since 0.5.0 `llm4s-core` ships no provider: each one is its own module (`llm4s-openai`, `llm4s-anthropic`,
`llm4s-ollama`, ...). Either the module is not a dependency of your build, or you build a fat jar and the
`META-INF/services` entries of the modules were dropped or overwritten when the jars were merged (`Discovery scanned 0 modules` in the
message points to this).

**Fix.** Add the provider's module next to `llm4s-core` (the table in
[Named provider sections](configuration#named-provider-sections) lists them), or, for a fat jar, merge the
`META-INF/services` files in your assembly settings or register the provider explicitly with
`ProviderRegistry.of(...)` / `.withProvider(...)`.

## 5. application.conf is not picked up

**Symptom.** `Llm4sConfig` fails as if your sections were not there (problem 2 or 3 above), although you wrote them.

**Cause.** The usual reasons are that the file is not on the runtime classpath (it must be in `src/main/resources/`,
not at the project root), that `-Dconfig.file` or `-Dconfig.resource` points at another file, that the HOCON has a
syntax error, or that a `-D` system property overrides the key. Precedence is system properties, then
`application.conf`, then each module's `reference.conf`.

**Fix.** Check the file's location and run the [doctor](/reference/doctor) with `--config <file>`, which reports an
unreadable configuration and where it is broken. See [How configuration is loaded](configuration#how-configuration-is-loaded).

## 6. LLM_MODEL has no effect

**Symptom.** You set `LLM_MODEL=openai/gpt-4o` (as older guides and blog posts say) and the program still uses
another model, or fails with problem 2.

**Cause.** Nothing in the library reads `LLM_MODEL` any more. Providers are named sections in `application.conf`.

**Fix.** Define a section and select it, as in [Named provider sections](configuration#named-provider-sections). The
chat-tui sample is the exception: it reads `LLM_MODEL` itself.

## 7. The code does not compile: no ModelRegistryService

**Symptom.** A Scala compile error at a `LLMConnect.getClient(...)` call: the compiler reports that no given instance
of `org.llm4s.model.ModelRegistryService` was found.

**Cause.** `LLMConnect.getClient` takes a `ModelRegistryService` (and a `ProviderRegistry`) as `using` parameters.
The `ProviderRegistry` has a default; the model registry service you provide.

**Fix.** Load it with `Llm4sConfig.modelRegistryService()` and bind it as a `given` before calling `getClient`,
as in [First Example](first-example).

## 8. Ollama is not running

**Symptom** (a `NetworkError`, or the doctor's "Local server" step failing):

```text
GET http://localhost:11434/api/tags: connection failed: ConnectException
```

**Cause.** Nothing is listening at the section's `baseUrl` (default `http://localhost:11434`). The text after
`connection failed:` is the Java exception's message, or its class name when it has none. `unknown host: ...` instead
means the host name in `baseUrl` does not resolve.

**Fix.** Start the server with `ollama serve` (on macOS and Windows the app may need to be started), and check the
section's `baseUrl` (the samples bind it to `OLLAMA_BASE_URL`). `curl http://localhost:11434` answers
`Ollama is running` when it is up. See [Ollama Quick Start](ollama-quickstart).

## 9. Ollama does not have the model

**Symptom** (the doctor run with `--local`):

```text
http://localhost:11434 answered, but it does not have 'llama3' (it has: ...)
  fix: run `ollama pull llama3`, or set the model to one it has
```

**Cause.** The section's `model` is not among the models the server has downloaded. A request to a model that is
missing fails with the server's own error.

**Fix.** Pull it (`ollama pull <model>`), or change `model` in the section to a model `ollama list` shows. The
samples' default is `llama3:latest`, which needs `ollama pull llama3` once, or `OLLAMA_MODEL` set to another.

## 10. UnsupportedClassVersionError or a JDK that is too old

**Symptom.** The JVM refuses to load LLM4S classes with `java.lang.UnsupportedClassVersionError` (that text is the
JVM's, not LLM4S's), or the doctor reports:

```text
Java <version> is too old: LLM4S needs 21 or newer
```

**Cause.** Every LLM4S artifact is compiled for JDK 21 and needs at least that. `java -version`, `JAVA_HOME`, and the
JDK your IDE and sbt use can each name a different one.

**Fix.** Run sbt, your application and your IDE with JDK 21 or newer (set `JAVA_HOME`, or choose the JDK in the IDE).
See the [v1 scope](/reference/v1-scope) for the support statement.

## 11. Authentication failed (401 or 403)

**Symptom** (an `AuthenticationError`):

```text
Authentication failed for openai: <the provider's own message>
```

**Cause.** The provider answered with HTTP 401 or 403: the key is wrong, expired, revoked or has no access to the
model. The most common slip is a key from another vendor: a section whose own `apiKey` binds a variable holding a
different provider's key wins over the shared `OPENAI_API_KEY`.

**Fix.** Check which key the section uses: the INFO line `llm4s.providers.<name>: API key from <path>` says where it
came from (never the value). See [Provider mismatch](configuration#problem-provider-mismatch).

## 12. Rate limited (429)

**Symptom** (a `RateLimitError`):

```text
Rate limited by openai. Retry after 20s
```

The second sentence appears only when the provider sent a `Retry-After` header; otherwise the message is
`Rate limited by openai`.

**Cause.** The provider answered HTTP 429: too many requests or tokens for your plan.

**Fix.** Wait for the delay and retry, slow the caller down, or raise the plan's limit. A `RateLimitError` is a
recoverable error, and `ReliableClient` can retry and throttle for you (see the [fallback cookbook](/examples/cookbook/fallback) and
[Rate limiting / 429 errors](configuration#problem-rate-limiting--429-errors)).

## 13. Timeouts

**Symptom** (a `TimeoutError`; these are the messages of the JDK-based HTTP client):

```text
POST https://example.com/v1/chat/completions: request timed out after 2 minutes
POST https://example.com/v1/chat/completions: connection timed out after 10 seconds
```

**Cause.** The response did not begin in time (`request timed out`), or the TCP connection could not be made
(`connection timed out`), often a wrong `baseUrl`, a proxy or a firewall. A slow local model on a CPU can also exceed
the default.

**Fix.** Check the endpoint is reachable, and if the model is just slow raise the timeout in the section's
`timeouts` block (`timeouts { request = 5m, stream = 20m }`). The defaults differ by provider, and the OpenAI,
Azure, Requesty and Anthropic clients use their SDK's own timeouts, which retry; see [Timeouts](configuration#timeouts).

---

Still stuck? Run the [doctor](/reference/doctor), then ask on [Discord](https://discord.gg/4uvTPn6qww) with the full
message.
