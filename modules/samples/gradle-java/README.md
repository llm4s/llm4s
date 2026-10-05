# Calling llm4s from Java

A standalone Gradle project: a Java `main` that asks a model two questions through
[`llm4s-java-api`](../../java-api), the Java-friendly layer over llm4s. Every call returns an `LlmResult`, so
there is no Scala `Either`, `Option` or `Nil$.MODULE$` in the code: a failure is a value you check, and the
sample shows both ways to read one.

| File | What it shows |
|---|---|
| [`HelloLLM4S.java`](src/main/java/org/llm4s/samples/HelloLLM4S.java) | create a client, one question, a conversation with a system message, error handling, closing the client |
| [`application.conf`](src/main/resources/application.conf) | the provider, as a named section |
| [`build.gradle.kts`](build.gradle.kts) | the one dependency: `org.llm4s:llm4s-java-api_3` |

It needs a JDK (17 or newer) and [Gradle](https://gradle.org/install/) 8 or newer. No Gradle wrapper is
committed (a wrapper jar is a binary); run `gradle wrapper` once if you want one.

## Run it

`llm4s-java-api` ships with 0.5.0 and is not on Maven Central yet, so for now publish it to your local Maven
repository from the root of this repository, which is where `build.gradle.kts` looks:

```bash
sbt 'set ThisBuild / version := "0.1.0-SNAPSHOT"' publishM2
```

Then, with a provider key in the environment (the sample's default is OpenAI):

```bash
cd modules/samples/gradle-java
OPENAI_API_KEY=sk-... gradle run
```

Once 0.5.0 is published, skip the `sbt` step and run `gradle run -Pllm4sVersion=0.5.0`, and change the default
`llm4sVersion` in `build.gradle.kts` to that release.

### Another provider, or another model

The provider is the default section of [`application.conf`](src/main/resources/application.conf): edit it, or
override a key with a system property. `gradle run` does not forward `-D` options, so build the launcher and
pass them through `JAVA_OPTS`:

```bash
gradle installDist
JAVA_OPTS="-Dllm4s.providers.openai-main.model=gpt-4o" build/install/hello-llm4s/bin/hello-llm4s
```

A provider's key comes from its own environment variable (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, ...); see the
[configuration guide](https://llm4s.org/getting-started/configuration). With no key the sample says which
variable to set and exits with status 1, as it does when a call fails.

## Why `llm4s-java-api` and not `llm4s-core`

`llm4s-core` is the Scala API. From Java it works, but you meet Scala's types directly: you unwrap an `Either`,
build `CompletionOptions` with `Option.empty()` and `Nil$.MODULE$`, and pass the model registry around yourself.
`llm4s-java-api` hides all of that behind `Llm4s`, `JLlmClient`, `ConversationBuilder` and `LlmResult`, and its
POM brings the provider modules and the Scala 3 library at the right version, so this project pins none of them.

CI builds this project against the library on every pull request (the `Kotlin API` job does both), so it does
not drift from the API it demonstrates.
