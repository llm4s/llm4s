---
layout: page
title: Mill Quick Start
parent: Getting Started
nav_order: 13
---

# Mill Quick Start
{: .no_toc }

A first LLM call, and a test of it that needs no API key, in a [Mill](https://mill-build.org) project.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

Mill 1.x writes its build in Scala 3, in a `build.mill` file, and LLM4S is published for Scala 3, so a Mill project
depends on LLM4S like on any other Scala library. This page is a complete project: the build, a program that asks a
model one question, its configuration, and a unit test that runs without a model.

## Before you start

- **Nothing to install for Mill**: the project carries Mill's launcher script, `./mill`, which downloads the Mill
  version the build names the first time it runs. It downloads JDK 21 as well (the `mill-jvm-version` line), the JDK
  LLM4S needs (see [Installation](installation#prerequisites)).
- **An API key** for OpenAI, or a local [Ollama](ollama-quickstart) (no key). The test needs neither.

## The files

Make a directory with these files. The same files are in
[`modules/samples/mill`](https://github.com/llm4s/llm4s/tree/main/modules/samples/mill), where CI builds them and runs
the test against the latest release on Maven Central.

```
hello-llm4s/
  mill                            (the launcher, see below)
  build.mill
  app/src/Hello.scala
  app/resources/application.conf
  app/resources/logback.xml
  app/test/src/HelloTests.scala
```

Mill's `ScalaModule` reads sources from `<module>/src`, resources (the files put on the classpath) from
`<module>/resources`, and a test module's sources from `<module>/test/src`. The module here is `app`.

### The launcher

Download it from Maven Central and make it executable (on Windows, download `mill-dist-1.3.1-mill.bat` from the same
directory and save it as `mill.bat`):

```bash
curl -fL https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.3.1/mill-dist-1.3.1-mill.sh -o mill
chmod +x mill
```

### build.mill

```scala
//| mill-version: 1.3.1
//| mill-jvm-version: 21
package build

import mill.*, scalalib.*

val llm4sVersion = "{{ site.data.project.latest_release }}"

object app extends ScalaModule {
  def scalaVersion = "3.7.1"

  def mvnDeps = Seq(
    mvn"org.llm4s::llm4s-core:$llm4sVersion",
    mvn"org.llm4s::llm4s-openai:$llm4sVersion",
    mvn"org.llm4s::llm4s-ollama:$llm4sVersion",
    mvn"ch.qos.logback:logback-classic:1.5.34"
  )

  object test extends ScalaTests, TestModule.Munit {
    def mvnDeps = Seq(
      mvn"org.scalameta::munit:1.3.6",
      mvn"org.llm4s::llm4s-testkit:$llm4sVersion"
    )
  }
}
```

- The `//|` lines at the top are Mill's header: the Mill version, and the JDK Mill runs and compiles with.
- `::` in a `mvn"..."` dependency adds the Scala binary suffix (`llm4s-core_3`), as `%%` does in sbt; a Java library
  such as logback takes a single `:`.
- `llm4s-core` holds no provider client, so the build adds the provider modules the program can use,
  `llm4s-openai` and `llm4s-ollama`; keep only the one you use. It brings no logging backend either, so the build
  adds logback, and `app/resources/logback.xml` configures it.
- Every llm4s module is released at the same version, so `llm4sVersion` names it once.

### app/src/Hello.scala

```scala
package hello

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

object Hello {

  /** Asks `client` one question. Taking the client as a parameter is what lets the test script it. */
  def ask(client: LLMClient, question: String): Result[String] =
    client.complete(Conversation(Seq(UserMessage(question)))).map(_.content)

  def main(args: Array[String]): Unit = {
    val answer = for {
      providerConfig <- Llm4sConfig.defaultProvider()
      registry       <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      client <- LLMConnect.getClient(providerConfig)
      text   <- ask(client, "In one sentence, what is Scala?")
    } yield text

    answer.fold(error => println(s"Error: ${error.formatted}"), text => println(text))
  }
}
```

1. `Llm4sConfig.defaultProvider()` reads the section of `application.conf` that `llm4s.providers.provider` names.
   This is the one place configuration is read.
2. `LLMConnect.getClient` builds the client for that provider.
3. `client.complete` sends the conversation. Every step returns a `Result`, so the `for` stops at the first error
   and the last line prints it instead of throwing.

### app/resources/application.conf

Mill puts `app/resources` on the classpath, which is where the configuration library looks for `application.conf`.
`provider` names the section the program uses; the [Configuration guide](configuration#named-provider-sections)
covers the other providers:

```hocon
llm4s {
  providers {
    provider = "openai-main"
    provider = ${?LLM4S_PROVIDER}

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
    }

    ollama-local {
      provider = "ollama"
      model    = "llama3"
      baseUrl  = "http://localhost:11434"
      baseUrl  = ${?OLLAMA_BASE_URL}
    }
  }
}
```

The OpenAI section needs no `apiKey` line: `llm4s-openai` reads `OPENAI_API_KEY` itself. The second `provider` line
lets the environment pick the other section (`LLM4S_PROVIDER=ollama-local`). Only the section being loaded is
checked, so the Ollama run does not need an OpenAI key.

### app/resources/logback.xml

It keeps the library's own logging out of your first answer (without it, logback prints every debug message):

```xml
<configuration>
  <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder><pattern>%d{HH:mm:ss} %-5level %logger{20} - %msg%n</pattern></encoder>
  </appender>
  <root level="WARN"><appender-ref ref="STDERR"/></root>
</configuration>
```

### app/test/src/HelloTests.scala

`ScriptedLLMClient`, from `llm4s-testkit`, is an `LLMClient` that answers from a script, so the test runs with no
model, no network and no key (see [Testing with the testkit](../guide/testing-with-the-testkit)):

```scala
package hello

import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

class HelloTests extends munit.FunSuite {

  test("ask returns the model's answer") {
    val client = ScriptedLLMClient.always(Reply.text("Scala is a JVM language."))

    assertEquals(Hello.ask(client, "What is Scala?"), Right("Scala is a JVM language."))
  }

  test("ask sends the question as the user's message") {
    val client = ScriptedLLMClient.always(Reply.text("ok"))

    Hello.ask(client, "What is Scala?")

    assertEquals(client.callCount, 1)
    assertEquals(client.lastCall.flatMap(_.lastUserText), Some("What is Scala?"))
  }

  test("ask returns an error rather than throwing") {
    val client = ScriptedLLMClient.sequence() // no reply scripted: the call fails

    assert(Hello.ask(client, "What is Scala?").isLeft)
  }
}
```

## Run it

```bash
cd hello-llm4s
./mill app.test                      # no key, no model

export OPENAI_API_KEY=sk-...
./mill app.run

LLM4S_PROVIDER=ollama-local ./mill app.run   # a local Ollama instead, no key
```

The first run downloads Mill, the JDK and the dependencies, which takes a while. `app.run` should print one
sentence, for example:

```
Scala is a language that blends object-oriented and functional programming on the JVM.
```

The wording depends on the model.

Mill runs tasks in a background daemon that it starts on the first command and reuses, which makes later commands
fast. A program that reads from the terminal (a chat loop, say) works under `./mill app.run` too: since Mill 1.1 the
daemon passes the terminal through. Older guides use `./mill -i app.run` for this; in Mill 1.x `-i` (`--interactive`)
only means `--no-daemon`, which runs without the daemon and is what you want in CI.

## If something goes wrong

**`Error: ConfigurationError: Provider 'openai-main' (provider = openai) is missing required fields:`** followed by
`- apiKey: set OPENAI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf`: the key is not set
in the shell that runs `./mill`. Check `echo $OPENAI_API_KEY`, or use the Ollama command above.

**`Error: NetworkError: POST http://localhost:11434/api/chat: connection failed`** (the Ollama run): no Ollama server
is listening. Start it with `ollama serve`, or point `OLLAMA_BASE_URL` at the one you have.

**A wall of `DEBUG` lines before the answer**: `logback.xml` is not on the classpath. Check that it is in
`app/resources`.

More first-run problems are in [First-Run Troubleshooting](troubleshooting).

## Optional extras

### Other llm4s modules

Add them to `mvnDeps` with the same `llm4sVersion`. For example `llm4s-schema-derivation`, which derives the JSON
schema of a structured answer from a case class with `derives SchemaOf` (see [Derived Schemas](../guide/derived-schemas)):

```scala
mvn"org.llm4s::llm4s-schema-derivation:$llm4sVersion"
```

[Installation](installation#optional-dependencies) lists every module; its sbt lines translate one for one
(`"org.llm4s" %% "llm4s-rag" % llm4sVersion` is `mvn"org.llm4s::llm4s-rag:$llm4sVersion"`).

### Aligning versions with llm4s-bom

Instead of `llm4sVersion`, Mill can import the bill of materials, `llm4s-bom`, and take every llm4s module's version
from it. The BOM is a Maven artifact with no Scala suffix, so it takes a single `:`; the modules then name no version:

```scala
object app extends ScalaModule {
  def scalaVersion = "3.7.1"

  def bomMvnDeps = Seq(mvn"org.llm4s:llm4s-bom:{{ site.data.project.latest_release }}")

  def mvnDeps = Seq(
    mvn"org.llm4s::llm4s-core",
    mvn"org.llm4s::llm4s-openai"
  )
}
```

See [Align versions with the BOM](installation#align-versions-with-the-bom) for what the BOM does and does not pin.

## Next steps

- [First Example](first-example): conversation context, tools and streaming
- [Configuration](configuration): all providers and how keys are found
- [Testing with the testkit](../guide/testing-with-the-testkit): scripting tool calls and multi-turn conversations
- [Ollama Quick Start](ollama-quickstart): local models
