# Calling llm4s from Helidon

A standalone Helidon SE 4 application that answers `POST /chat` (and streams the reply at `POST /chat/stream`) through
[`llm4s-java-api`](../../java-api), with an `llm4s` health check. The walk-through, including the pitfalls, is
[docs/guide/helidon.md](../../../docs/guide/helidon.md).

It needs JDK 21 or newer and Gradle 8.11 or newer.

## Run it

The default provider is a local [Ollama](https://ollama.com) server at `http://localhost:11434`, which needs no key.
Against a release:

```bash
ollama pull llama3.2
cd modules/samples/helidon-java
gradle run -Pllm4sVersion=0.5.1
curl -X POST -H 'Content-Type: text/plain' -d 'What is Helidon, in one sentence?' http://localhost:8080/chat
```

Without `-Pllm4sVersion` the build uses `0.1.0-SNAPSHOT`, the code in this checkout, which you publish to your
local Maven repository first. From the root of this repository:

```bash
sbt 'set ThisBuild / version := "0.1.0-SNAPSHOT"' publishM2
cd modules/samples/helidon-java
gradle build
```

`gradle build` runs the tests, which replace the client with a `FakeLlmClient` and need no model and no key. CI
runs it against that snapshot on every pull request, and `scripts/check-framework-guide-snippets.sh` checks that the
guide's snippets are excerpts of this sample.
