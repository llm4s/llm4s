# quarkus-llm4s

The Quarkus extension for llm4s ([#2280](https://github.com/llm4s/llm4s/issues/2280)): `JLlmClient` and
`JEmbeddingClient` beans configured in `application.properties`, checked when the application is built, with a
readiness check. The guide is [docs/guide/quarkus.md](../../docs/guide/quarkus.md); the sample that uses it is
[modules/samples/quarkus-java](../samples/quarkus-java).

Built and tested against Quarkus 3.40.1, JVM mode only. Not yet published.

| Module | Artifact | What it holds |
|---|---|---|
| `runtime` | `org.llm4s:quarkus-llm4s` | the recorder that builds the clients, the readiness check, `Llm4sHealthConfig` |
| `deployment` | `org.llm4s:quarkus-llm4s-deployment` | the build steps: the configuration check, the beans, the health registration; the tests |

The configuration is read and checked through `org.llm4s.javaapi.FrameworkConfig` in `llm4s-java-api` (Scala, tested
by `FrameworkConfigSpec`), so the Maven build holds Quarkus code only.

## Build and test

A Quarkus extension is built by Maven with Quarkus's own plugins, not by sbt. It consumes the llm4s modules from the
local Maven repository, so publish them first. From the root of the repository:

```bash
sbt 'set ThisBuild / version := "0.1.0-SNAPSHOT"' publishM2
cd modules/quarkus
mvn install
```

The tests are `QuarkusUnitTest` applications over `llm4s-ollama` and a stub Ollama server on a loopback port: no model,
no key, no Docker. CI's `Quarkus extension` job runs them, then builds and tests the sample against the extension.
