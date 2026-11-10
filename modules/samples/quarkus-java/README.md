# Calling llm4s from Quarkus

A standalone Quarkus application that answers `POST /chat` through the [`quarkus-llm4s`](../../quarkus) extension: the
`JLlmClient` bean the extension builds from `application.properties`, a REST resource that keeps the blocking call off
the event loop, and the extension's readiness check. The walk-through is
[docs/guide/quarkus.md](../../../docs/guide/quarkus.md).

It needs JDK 21 or newer and Maven 3.9 or newer.

| File | What it shows |
|---|---|
| `application.properties` | the client as a section of `llm4s.providers`, in the Spring Boot starter's spelling |
| `ChatResource.java` | `POST /chat` on the injected `JLlmClient`, `@Blocking`, a failed call mapped to 429, 504 or 502 |
| `ChatResourceTest.java`, `OllamaStub.java` | `@QuarkusTest` against a stub Ollama server on a loopback port: no model, no key |

## Run it

`quarkus-llm4s` is not published yet, so the sample builds against the code of this checkout, installed in your local
Maven repository. From the root of this repository:

```bash
sbt 'set ThisBuild / version := "0.1.0-SNAPSHOT"' publishM2
(cd modules/quarkus && mvn install)
```

The default provider is a local [Ollama](https://ollama.com) server at `http://localhost:11434`, which needs no key:

```bash
ollama pull llama3.2
cd modules/samples/quarkus-java
mvn quarkus:dev
curl -X POST -H 'Content-Type: text/plain' -d 'What is Quarkus, in one sentence?' http://localhost:8080/chat
curl http://localhost:8080/q/health/ready
```

Another provider is configuration only: set `llm4s.providers.main.provider` and `llm4s.providers.main.model` in
`application.properties` (for example `openai` and `gpt-4o-mini`, with `OPENAI_API_KEY` set).

## Test it

```bash
mvn verify
```

`mvn verify` runs the tests, which start a stub Ollama server and need nothing else. CI runs it against the snapshot on
every pull request, and `scripts/check-quarkus-guide-snippets.sh` checks that the guide's snippets are excerpts of
this sample.
