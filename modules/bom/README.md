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
no suffix. See "Align versions with the BOM" in the installation guide.

There is no code and there are no tests here. The managed list is generated when the POM is made, from the same
project list that `sbt listPublishedArtifacts` prints (`project/Bom.scala`), and `sbt bomCheck` fails when the generated
POM and that list disagree.
