---
layout: page
title: Maven Quick Start
parent: Getting Started
nav_order: 9
---

# Maven Quick Start
{: .no_toc }

A complete `pom.xml` that compiles and runs a first call from Java, with `llm4s-java-api`.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

The project is in
[`modules/samples/maven-java`](https://github.com/llm4s/llm4s/tree/main/modules/samples/maven-java), and CI compiles
it against the library on every pull request. For Gradle, see [Gradle Integration](gradle); for the Java API itself,
the [Java guide](/guide/java).

## Before you start

- **JDK 21 or newer** and **Maven 3.9 or newer**. `llm4s-java-api` is JDK 21 bytecode, so the project sets
  `maven.compiler.release` to 21.
- **An API key** for OpenAI, or a local [Ollama](ollama-quickstart) (no key).

## The coordinate

LLM4S is published for **Scala 3 only**, and Maven does **not** add the Scala cross-version suffix: the artifact is
`org.llm4s:llm4s-java-api_3`, and you write the `_3` yourself. Its POM brings `llm4s-core`, `llm4s-agent` and the
provider modules, so it is the one llm4s dependency a Java project needs.

## The pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>

  <groupId>org.llm4s.samples</groupId>
  <artifactId>llm4s-maven-sample</artifactId>
  <version>1</version>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <llm4s.version>{{ site.data.project.latest_release }}</llm4s.version>
    <scala3.version>3.7.1</scala3.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.scala-lang</groupId>
        <artifactId>scala3-library_3</artifactId>
        <version>${scala3.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.llm4s</groupId>
      <artifactId>llm4s-java-api_3</artifactId>
      <version>${llm4s.version}</version>
    </dependency>
    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
      <version>1.5.34</version>
      <scope>runtime</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>3.5.0</version>
        <executions>
          <execution>
            <id>enforce-convergence</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules><dependencyConvergence/></rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.5.0</version>
        <configuration>
          <mainClass>org.llm4s.samples.HelloLLM4S</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

What each part is for:

- **`llm4s-java-api_3`** is the library. Change `llm4s.version` to move to another release; every llm4s artifact is
  released at the same version.
- **`logback-classic`** is the logging backend. llm4s logs through SLF4J and ships none; without one SLF4J prints a
  single "no SLF4J providers were found" warning and drops the logs.
- **`scala3-library_3`** in `dependencyManagement` pins the Scala 3 library. Several llm4s modules may ask for
  different micro-versions, and Maven's nearest-wins rule would pick one silently. Pin `scala3-library_3` by name,
  never the whole `org.scala-lang` group: Scala 3 runs on the Scala 2.13 `scala-library`, which has no 3.x release.
  The [dependency conflicts reference](/reference/dependency-conflicts) has the matrix.
- **`maven-enforcer-plugin` with `dependencyConvergence`** fails the build when two dependencies need different
  versions of one library, so a conflict shows up at build time and not as a `NoSuchMethodError` at run time.
  When it fails, it names the library and both paths: add a `dependencyManagement` entry for it, as for
  `scala3-library_3`, or an exclusion.
- **`exec-maven-plugin`** gives `mvn exec:java`, which runs the `main` class.

## The code and the configuration

`src/main/java/org/llm4s/samples/HelloLLM4S.java` creates a client from the default provider, asks one question
and prints the answer or the error. Every call returns an `LlmResult`, so there is no Scala `Either` in the code:

```java
LlmResult<JLlmClient> created = Llm4s.createDefaultClient();
if (created.isFailure()) {
    System.err.println("Could not create a client: " + created.getError().getMessage());
    return 1;
}
try (JLlmClient client = created.get()) {
    LlmResult<String> answer = client.complete("In one sentence, what is Scala?");
    // ... print answer.get(), or answer.getError().getMessage()
}
```

`src/main/resources/application.conf` names the provider, as a named section; the
[Configuration guide](configuration#named-provider-sections) covers the others:

```hocon
llm4s {
  providers {
    provider = "openai-main"

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
    }
  }
}
```

## Run it

```bash
export OPENAI_API_KEY=sk-...
mvn -q compile exec:java
```

You should see one sentence; the wording depends on the model. With a local Ollama, change the section to
`provider = "ollama"` with a `model` you have pulled, as in the [Ollama quick start](ollama-quickstart).

## If something goes wrong

**`Could not find artifact org.llm4s:llm4s-java-api:jar`** (no `_3`): add the suffix, `llm4s-java-api_3`.

**`Could not create a client: ... apiKey: set OPENAI_API_KEY`**: the key is not set in the shell that runs `mvn`.

**`Rule 0: ...DependencyConvergence failed`**: see the enforcer entry above.

**`class file has wrong version 65.0`** or similar: Maven is running on a JDK older than 21. Check `mvn -v`.

## Next steps

- [Java guide](/guide/java): conversations, options, agents and errors from Java
- [Gradle Integration](gradle): the same project in Gradle
- [Configuration](configuration): all providers and how keys are found
