# Calling llm4s from Quarkus

A standalone Quarkus application that answers `POST /chat` through
[`llm4s-java-api`](../../java-api): a CDI producer for the `JLlmClient`, configured with `@ConfigMapping`, and a REST
resource that keeps the blocking call off the event loop. The walk-through is
[docs/guide/quarkus.md](../../../docs/guide/quarkus.md).

It needs JDK 21 or newer and Maven 3.9 or newer.

| File | What it shows |
|---|---|
| `Llm4sSettings.java` | the `llm4s.*` keys of `application.properties` as a `@ConfigMapping` |
| `LlmClientProducer.java` | `@Produces @ApplicationScoped JLlmClient`, built with the Java API's builders, closed by a `@Disposes` method |
| `ChatResource.java` | `POST /chat`, `@Blocking`, a failed call mapped to 429, 504 or 502 |
| `ChatResourceTest.java`, `OllamaStub.java` | `@QuarkusTest` against a stub Ollama server on a loopback port: no model, no key |

## Run it

The default provider is a local [Ollama](https://ollama.com) server at `http://localhost:11434`, which needs no key.
Against a release:

```bash
ollama pull llama3.2
cd modules/samples/quarkus-java
mvn quarkus:dev -Dllm4s.version=0.5.1
curl -X POST -H 'Content-Type: text/plain' -d 'What is Quarkus, in one sentence?' http://localhost:8080/chat
```

Another provider is configuration only:

```bash
LLM4S_PROVIDER=openai LLM4S_MODEL=gpt-4o-mini LLM4S_API_KEY=sk-... mvn quarkus:dev
```

Without `-Dllm4s.version` the build uses `0.1.0-SNAPSHOT`, the code in this checkout, which you publish to your
local Maven repository first. From the root of this repository:

```bash
sbt 'set ThisBuild / version := "0.1.0-SNAPSHOT"' publishM2
cd modules/samples/quarkus-java
mvn verify
```

`mvn verify` runs the tests, which start a stub Ollama server and need nothing else. CI runs it against that
snapshot on every pull request, and `scripts/check-quarkus-guide-snippets.sh` checks that the guide's snippets are
excerpts of this sample.
