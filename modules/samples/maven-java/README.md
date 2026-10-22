# Calling llm4s from Maven

A standalone Maven project: a Java `main` that asks a model one question through
[`llm4s-java-api`](../../java-api). The walk-through is
[docs/getting-started/maven.md](../../../docs/getting-started/maven.md); the Gradle equivalent, with more demos, is
[`../gradle-java`](../gradle-java).

It needs JDK 21 or newer and Maven 3.9 or newer.

## Run it

`llm4s-java-api` is on Maven Central from 0.5.0. With a provider key in the environment (the default is OpenAI),
run the sample against a release:

```bash
cd modules/samples/maven-java
OPENAI_API_KEY=sk-... mvn -q compile exec:java -Dllm4s.version=0.5.1
```

Without `-Dllm4s.version` the build uses `0.1.0-SNAPSHOT`, the code in this checkout, which you publish to your
local Maven repository first. From the root of this repository:

```bash
sbt 'set ThisBuild / version := "0.1.0-SNAPSHOT"' publishM2
cd modules/samples/maven-java
OPENAI_API_KEY=sk-... mvn -q compile exec:java
```

CI runs `mvn -q compile` against that snapshot on every pull request.
