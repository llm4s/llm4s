---
layout: page
title: JBang Quick Start
parent: Getting Started
nav_order: 10
---

# JBang Quick Start
{: .no_toc }

A first LLM call from one Java file, with no build tool and no project.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

[JBang](https://www.jbang.dev/) runs a Java file with its dependencies declared in the file itself, so you can try
LLM4S from Java without creating a project. `llm4s-java-api` is on Maven Central from `0.5.0`. When you want a real
project, use Maven (see the Java API module's README) or [Gradle](gradle).

## Before you start

- **JBang**, installed as described on its [download page](https://www.jbang.dev/download/).
- **JDK 21 or newer**: the file's `//JAVA 21` line makes JBang use JDK 21, and download it the first time if you
  do not have it. LLM4S needs JDK 21 (see [Installation](installation#prerequisites)).
- **A local [Ollama](ollama-quickstart)**, which needs no key: run `ollama serve` and `ollama pull llama3`.

## The files

Make a directory with these three files. The same files are in
[`modules/samples/jbang`](https://github.com/llm4s/llm4s/tree/main/modules/samples/jbang).

`HelloLlm4s.java`:

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS org.llm4s:llm4s-java-api_3:{{ site.data.project.latest_release }}
//DEPS ch.qos.logback:logback-classic:1.5.34
//FILES application.conf
//FILES logback.xml

import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;

public class HelloLlm4s {

    public static void main(String[] args) {
        LlmResult<JLlmClient> created = Llm4s.createDefaultClient();
        if (created.isFailure()) {
            System.err.println("Could not create a client: " + created.getError().getMessage());
            System.exit(1);
        }

        try (JLlmClient client = created.get()) {
            LlmResult<String> answer = client.complete("In one sentence, what is Scala?");
            if (answer.isFailure()) {
                System.err.println("The call failed: " + answer.getError().getMessage());
                System.exit(1);
            }
            System.out.println(answer.get());
        } catch (Exception e) {
            System.err.println("Closing the client failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
```

The `//DEPS` line is the whole dependency: Maven does not add the Scala suffix, so the artifact is
`llm4s-java-api_3`, and its POM brings `llm4s-core`, `llm4s-agent` and the provider modules, including Ollama.
The second `//DEPS` line is the logging backend: llm4s logs through SLF4J and ships none, so without it SLF4J prints
a "no SLF4J providers were found" warning and drops the logs. `//FILES` puts a file on the classpath.

`application.conf` names the provider, as a named section; the
[Configuration guide](configuration#named-provider-sections) covers the others:

```hocon
llm4s {
  providers {
    provider = "ollama-local"

    ollama-local {
      provider = "ollama"
      model    = "llama3"
      baseUrl  = "http://localhost:11434"
      baseUrl  = ${?OLLAMA_BASE_URL}
    }
  }
}
```

`logback.xml` keeps the library's own logging out of your first answer (without it, logback prints every debug
message):

```xml
<configuration>
  <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder><pattern>%d{HH:mm:ss} %-5level %logger{20} - %msg%n</pattern></encoder>
  </appender>
  <root level="WARN"><appender-ref ref="STDERR"/></root>
</configuration>
```

## Run it

From the directory that holds the three files:

```bash
jbang HelloLlm4s.java
```

The first run downloads the dependencies, which takes a while. You should see one sentence; the wording depends on
the model. To compile without running, use `jbang build HelloLlm4s.java`.

For OpenAI instead of Ollama, change the section in `application.conf` to `provider = "openai"` with
`model = "gpt-4o-mini"`, and set `OPENAI_API_KEY` in the shell.

## If something goes wrong

**`Could not create a client: ...`**: the message names what is missing, for example a provider key. Check
`application.conf` and the environment.

**`The call failed: ... connection failed`**: no Ollama server is listening. Start it with `ollama serve`, or point
`OLLAMA_BASE_URL` at the one you have.

**A wall of `DEBUG` lines before the answer**: `logback.xml` is not beside the file, or `//FILES logback.xml` is
missing.

**The version**: change the number in the `//DEPS org.llm4s` line to move to another release.

## Trying the code on `main`

`llm4s-java-api` ships with `0.5.0`. Before a change is released, publish it to your local Maven repository from a
checkout of this repository, as the [Gradle sample](https://github.com/llm4s/llm4s/tree/main/modules/samples/gradle-java)
does:

```bash
sbt 'set ThisBuild / version := "0.1.0-SNAPSHOT"' publishM2
```

Then change the `//DEPS` line to version `0.1.0-SNAPSHOT` and add `//REPOS mavenlocal,mavencentral` above it.

## Next steps

- [Maven Quick Start](maven): the same call in a `pom.xml`
- [Java guide](/guide/java): conversations, options, agents and errors from Java
- [Configuration](configuration): all providers and how keys are found
