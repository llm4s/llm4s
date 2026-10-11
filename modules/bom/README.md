# llm4s-bom

A POM-only bill of materials for LLM4S: it pins every published `llm4s-*` artifact to one version, so a Maven or
Gradle project that uses several modules states the version once.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.llm4s</groupId>
      <artifactId>llm4s-bom</artifactId>
      <version>VERSION</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

```kotlin
implementation(platform("org.llm4s:llm4s-bom:VERSION"))
```

The modules are then declared without a version, with the Scala binary suffix (`llm4s-core_3`). The BOM itself has
no suffix, and neither has the Kotlin coroutine API, `llm4s-kotlin-api`, which the release publishes from a Gradle
build (`modules/kotlin-api`) at the same version, so the BOM manages it too. See "Align versions with the BOM" in the
installation guide.

There is no code and there are no tests here. The managed list is generated when the POM is made, from the same
project list that `sbt listPublishedArtifacts` prints (`project/Bom.scala`), plus the Gradle-built artifacts it names
(`PublishedArtifacts.Gradle`), and `sbt bomCheck` fails when the generated
POM and that list disagree, or when a module that publishes a jar is left out of it (for example because its name
does not start with `llm4s-`). `scripts/check-bom-consumers.sh`, run in CI after `publishM2`, builds a Maven and a
Gradle project that declare `llm4s-core_3` and `llm4s-ollama_3` with no versions against the published BOM.

Gradle `platform(...)` recommends these versions; dependency conflicts can upgrade them.
Applications requiring strict pinning can use `enforcedPlatform(...)`. Published libraries should
generally avoid exporting enforced versions to their consumers.
