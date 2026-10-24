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

Each of these messages ends with a link to its entry here, such as `See https://llm4s.org/getting-started/troubleshooting#missing-api-key`, and a
configuration error also with the doctor's command. The anchors (`#missing-api-key`) are fixed: they do not change
when an entry is renumbered or its heading reworded.

Two tools help before you read on:

- **The doctor** checks your JDK, configuration, default provider, provider module, API key and (with `--local`) a
  local model server in one run, and says what to change. See [Doctor](/reference/doctor).
- **The rest of the reference** has [Troubleshooting / FAQ](/reference/troubleshooting) for later problems (agents,
  streaming, RAG, tracing) and [Configuration: Troubleshooting](configuration#troubleshooting).

## Contents

1. [The API key is missing](#missing-api-key)
2. [No default provider configured](#no-default-provider)
3. [Configured provider was not found](#provider-not-found)
4. [Provider is not registered](#provider-not-registered)
5. [application.conf is not picked up](#application-conf-not-loaded)
6. [LLM_MODEL has no effect](#llm-model-ignored)
7. [The code does not compile: no ModelRegistryService](#no-model-registry-service)
8. [Ollama is not running](#ollama-not-running)
9. [Ollama does not have the model](#ollama-model-missing)
10. [UnsupportedClassVersionError or a JDK that is too old](#jdk-too-old)
11. [Authentication failed (401 or 403)](#authentication-failed)
12. [Rate limited (429)](#rate-limited)
13. [Timeouts](#timeouts)
14. [Model not found (404)](#model-not-found)

## 1. The API key is missing {#missing-api-key}

**Symptom** (a `ConfigurationError`, from `Llm4sConfig.defaultProvider()` or `Llm4sConfig.provider(...)`):

```text
Provider 'openai-main' (provider = openai) is missing required fields:
  - apiKey: set OPENAI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf
See https://llm4s.org/getting-started/troubleshooting#missing-api-key
Run the doctor to check your whole setup: sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli --config /path/to/application.conf"
```

An embeddings block without a key fails the same way, with `Missing openai embeddings apiKey: set OPENAI_API_KEY, ...`.

**Cause.** Neither the vendor's variable nor an `apiKey` in the section is available to the JVM. The variable named
is the one the provider's module binds (`llm4s-openai` binds `OPENAI_API_KEY`); a section that sets its own `apiKey`
uses that instead. A variable exported in one terminal is not seen by an IDE run configuration started elsewhere.

**Fix.** Export the variable in the shell that starts the JVM (or set it in the IDE run configuration), or set
`apiKey` in the section. The error names the section it is about: if it is not the one you meant, check
`llm4s.providers.provider`. See [API keys](configuration#api-keys).

## 2. No default provider configured {#no-default-provider}

**Symptom** (a `ConfigurationError`):

```text
No default provider configured under llm4s.providers.provider
See https://llm4s.org/getting-started/troubleshooting#no-default-provider
Run the doctor to check your whole setup: sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli --config /path/to/application.conf"
```

**Cause.** `Llm4sConfig.defaultProvider()` loads the section that `llm4s.providers.provider` names, and the key is
not set, usually because no `application.conf` defines it.

**Fix.** Add the key to your `application.conf` (see [Named provider sections](configuration#named-provider-sections)),
or load a section by name with `Llm4sConfig.provider("<name>")`.

## 3. Configured provider was not found {#provider-not-found}

**Symptom** (a `ConfigurationError`):

```text
Configured provider 'openai-main' was not found
See https://llm4s.org/getting-started/troubleshooting#provider-not-found
Run the doctor to check your whole setup: sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli --config /path/to/application.conf"
```

**Cause.** `llm4s.providers.provider` (or the name you passed to `Llm4sConfig.provider(...)`) names a section that
does not exist, for example through a typo, or a `-Dllm4s.providers.provider=...` override.

**Fix.** Make the name match a section under `llm4s.providers` exactly.

## 4. Provider is not registered {#provider-not-registered}

**Symptom** (a `ConfigurationError`, from `LLMConnect.getClient`):

```text
Provider 'openai' is not registered. Registered providers: none. If you expected 'openai', add the dependency that supplies it, or register it explicitly with ProviderRegistry.of(...). Discovery scanned 0 modules; 0 failed.
See https://llm4s.org/getting-started/troubleshooting#provider-not-registered
Run the doctor to check your whole setup: sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli --config /path/to/application.conf"
```

When the id came from a config section, the message also says which key it came from. The first line ends with a
sentence about classpath discovery, such as `Discovery scanned 0 modules; 0 failed.`

**Cause.** Since 0.5.0 `llm4s-core` ships no provider: each one is its own module (`llm4s-openai`, `llm4s-anthropic`,
`llm4s-ollama`, ...). Either the module is not a dependency of your build, or you build a fat jar and the
`META-INF/services` entries of the modules were dropped or overwritten when the jars were merged (`Discovery scanned 0 modules` in the
message points to this).

**Fix.** Add the provider's module next to `llm4s-core` (the table in
[Named provider sections](configuration#named-provider-sections) lists them), or, for a fat jar, merge the
`META-INF/services` files in your assembly settings or register the provider explicitly with
`ProviderRegistry.of(...)` / `.withProvider(...)`.

## 5. application.conf is not picked up {#application-conf-not-loaded}

**Symptom.** `Llm4sConfig` fails as if your sections were not there (problem 2 or 3 above), although you wrote them,
or it cannot read the block at all (a `ConfigurationError`):

```text
Failed to load raw providers config via PureConfig: <what is wrong, and where>
See https://llm4s.org/getting-started/troubleshooting#application-conf-not-loaded
Run the doctor to check your whole setup: sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli --config /path/to/application.conf"
```

A section that cannot be read on its own fails only a load of that section, with
`Failed to read llm4s.providers.<name>: ...` and the same two lines.

**Cause.** The usual reasons are that the file is not on the runtime classpath (it must be in `src/main/resources/`,
not at the project root), that `-Dconfig.file` or `-Dconfig.resource` points at another file, that the HOCON has a
syntax error, or that a `-D` system property overrides the key. Precedence is system properties, then
`application.conf`, then each module's `reference.conf`.

**Fix.** Check the file's location and run the [doctor](/reference/doctor) with `--config <file>`, which reports an
unreadable configuration and where it is broken. See [How configuration is loaded](configuration#how-configuration-is-loaded).

## 6. LLM_MODEL has no effect {#llm-model-ignored}

**Symptom.** You set `LLM_MODEL=openai/gpt-4o` (as older guides and blog posts say) and the program still uses
another model, or fails with problem 2.

**Cause.** Nothing in the library reads `LLM_MODEL` any more. Providers are named sections in `application.conf`.

**Fix.** Define a section and select it, as in [Named provider sections](configuration#named-provider-sections). The
chat-tui sample is the exception: it reads `LLM_MODEL` itself.

## 7. The code does not compile: no ModelRegistryService {#no-model-registry-service}

**Symptom.** A Scala compile error at a `LLMConnect.getClient(...)` call: the compiler reports that no given instance
of `org.llm4s.model.ModelRegistryService` was found.

**Cause.** `LLMConnect.getClient` takes a `ModelRegistryService` (and a `ProviderRegistry`) as `using` parameters.
The `ProviderRegistry` has a default; the model registry service you provide.

**Fix.** Load it with `Llm4sConfig.modelRegistryService()` and bind it as a `given` before calling `getClient`,
as in [First Example](first-example).

## 8. Ollama is not running {#ollama-not-running}

**Symptom** (a `NetworkError` from a chat call, or the doctor's "Local server" step failing):

```text
POST http://localhost:11434/api/chat: connection failed: ConnectException. Start the server with `ollama serve`, and check the section's baseUrl. See https://llm4s.org/getting-started/troubleshooting#ollama-not-running
```

The doctor's step says `GET http://localhost:11434/api/tags: connection failed: ConnectException` instead.

**Cause.** Nothing is listening at the section's `baseUrl` (default `http://localhost:11434`). The text after
`connection failed:` is the Java exception's message, or its class name when it has none. `unknown host: ...` instead
means the host name in `baseUrl` does not resolve.

**Fix.** Start the server with `ollama serve` (on macOS and Windows the app may need to be started), and check the
section's `baseUrl` (the samples bind it to `OLLAMA_BASE_URL`). `curl http://localhost:11434` answers
`Ollama is running` when it is up. See [Ollama Quick Start](ollama-quickstart).

## 9. Ollama does not have the model {#ollama-model-missing}

**Symptom** (a `ServiceError` with HTTP 404 from a chat call):

```text
Service error from ollama: model "llama3" not found, try pulling it first (HTTP 404). Pull it with `ollama pull llama3`, or set the section's model to one `ollama list` shows. See https://llm4s.org/getting-started/troubleshooting#ollama-model-missing
```

or, from the doctor run with `--local`:

```text
http://localhost:11434 answered, but it does not have 'llama3' (it has: ...)
  fix: run `ollama pull llama3`, or set the model to one it has
```

**Cause.** The section's `model` is not among the models the server has downloaded. The text before `(HTTP 404)` is
the server's own.

**Fix.** Pull it (`ollama pull <model>`), or change `model` in the section to a model `ollama list` shows. The
samples' default is `llama3:latest`, which needs `ollama pull llama3` once, or `OLLAMA_MODEL` set to another.

## 10. UnsupportedClassVersionError or a JDK that is too old {#jdk-too-old}

**Symptom.** The JVM refuses to load LLM4S classes with `java.lang.UnsupportedClassVersionError` (that text is the
JVM's, not LLM4S's), or the doctor reports:

```text
Java <version> is too old: LLM4S needs 21 or newer
```

**Cause.** Every LLM4S artifact is compiled for JDK 21 and needs at least that. `java -version`, `JAVA_HOME`, and the
JDK your IDE and sbt use can each name a different one.

**Fix.** Run sbt, your application and your IDE with JDK 21 or newer (set `JAVA_HOME`, or choose the JDK in the IDE).
See the [v1 scope](/reference/v1-scope) for the support statement.

## 11. Authentication failed (401 or 403) {#authentication-failed}

**Symptom** (an `AuthenticationError`):

```text
Authentication failed for openai: <the provider's own message>. Check that the key is this provider's, and that its project or organisation has access to the model. See https://llm4s.org/getting-started/troubleshooting#authentication-failed
```

A provider that answers 403 through an SDK that does not type it (Anthropic) gives a `ServiceError`,
`Service error from anthropic: ... (HTTP 403)`, with the same sentence and link.

**Cause.** The provider answered with HTTP 401 or 403: the key is wrong, expired, revoked or has no access to the
model. The most common slip is a key from another vendor: a section whose own `apiKey` binds a variable holding a
different provider's key wins over the shared `OPENAI_API_KEY`.

**Fix.** Check which key the section uses: the INFO line `llm4s.providers.<name>: API key from <path>` says where it
came from (never the value). See [Provider mismatch](configuration#problem-provider-mismatch).

## 12. Rate limited (429) {#rate-limited}

**Symptom** (a `RateLimitError`):

```text
Rate limited by openai. Retry after 20s. Lower the request concurrency, or wrap the client in ReliableClient to retry and throttle. See https://llm4s.org/getting-started/troubleshooting#rate-limited
```

`Retry after 20s` appears only when the provider sent a `Retry-After` header.

**Cause.** The provider answered HTTP 429: too many requests or tokens for your plan.

**Fix.** Wait for the delay and retry, slow the caller down, or raise the plan's limit. A `RateLimitError` is a
recoverable error, and `ReliableClient` can retry and throttle for you (see the [fallback cookbook](/examples/cookbook/fallback) and
[Rate limiting / 429 errors](configuration#problem-rate-limiting--429-errors)).

## 13. Timeouts {#timeouts}

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

## 14. Model not found (404) {#model-not-found}

**Symptom** (a `ServiceError` with HTTP 404):

```text
Service error from openai: The model `gpt-5-mini-typo` does not exist or you do not have access to it. (HTTP 404). Check the section's model name and baseUrl; Llm4sConfig.listModels() lists the models the provider offers. See https://llm4s.org/getting-started/troubleshooting#model-not-found
```

**Cause.** The provider has no model of that name, or the section's `baseUrl` points at the wrong path, so the
endpoint itself is not found. A local Ollama that has not pulled the model answers this way too; see
[problem 9](#ollama-model-missing).

**Fix.** Check the section's `model` against the provider's model list: `Llm4sConfig.listModels()` lists the models
of the default section's provider, and `Llm4sConfig.listModels("<name>")` those of a named section. If the model is
right, check `baseUrl` (an OpenAI-compatible server's usually ends in `/v1`).

---

Still stuck? Run the [doctor](/reference/doctor), then ask on [Discord](https://discord.gg/4uvTPn6qww) with the full
message.
