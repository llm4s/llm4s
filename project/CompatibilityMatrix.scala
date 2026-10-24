import sbt._

/**
 * `docs/_data/tested-versions.yml`: which versions of the stack around it llm4s is built and tested with (#2290).
 *
 * Read from the build, never typed by hand: the Scala version and JDK floor from `Common`, the integration libraries'
 * versions from `Versions` (project/Dependencies.scala), the JDKs and the latest Spring Boot releases CI runs from
 * `.github/workflows/ci.yml`, Kotlin from the Gradle build of `llm4s-kotlin-api` and Quarkus from the Quarkus
 * sample's POM. `sbt generateCompatibilityMatrix` writes it beside `docs/_data/capabilities.yml` (written by
 * `org.llm4s.samples.docs.CapabilityMatrix`), CI runs that again and fails when either committed file differs, and
 * `docs/reference/compatibility-matrix.md` renders both.
 *
 * A pattern below that no longer matches its file fails the task, so a reworded CI job cannot leave a stale version.
 */
object CompatibilityMatrix {

  val OutputFile = "docs/_data/tested-versions.yml"

  /** One integration: what it is, the llm4s artifact that carries it, and the versions it is built or tested with. */
  final case class Stack(name: String, artifact: String, versions: Seq[String], note: String)

  private def find(file: File, pattern: scala.util.matching.Regex, what: String): String = {
    val text = IO.read(file)
    pattern.findFirstMatchIn(text).map(_.group(1)).getOrElse {
      sys.error(s"${file.getPath}: no $what (pattern ${pattern.regex}); update project/CompatibilityMatrix.scala")
    }
  }

  def stacks(base: File): (Seq[String], Seq[Stack]) = {
    val ci = base / ".github" / "workflows" / "ci.yml"
    val ciJdks =
      find(ci, """(?m)^\s+java: \[([^\]]+)\]""".r, "`java:` matrix of the test job").split(',').map(_.trim).toSeq
    val boot3    = find(ci, """-Dllm4s\.springBootVersion=([0-9.]+)""".r, "Spring Boot 3.x (latest) version")
    val boot4    = find(ci, """-Dllm4s\.springBoot4Version=([0-9.]+)""".r, "Spring Boot 4.x version")
    val kotlinKt = base / "modules" / "kotlin-api" / "build.gradle.kts"
    val kotlin   = find(kotlinKt, """kotlin\("jvm"\) version "([^"]+)"""".r, "Kotlin plugin version")
    val coroutines =
      find(kotlinKt, """kotlinx-coroutines-core:([0-9.]+)""".r, "kotlinx-coroutines-core version")
    val quarkus =
      find(
        base / "modules" / "samples" / "quarkus-java" / "pom.xml",
        """<quarkus\.platform\.version>([^<]+)<""".r,
        "Quarkus platform version"
      )

    val stacks = Seq(
      Stack(
        "Spring Boot 3",
        "llm4s-spring-boot-starter",
        Seq(Versions.springBootBaseline, boot3).distinct,
        s"Built against ${Versions.springBootBaseline}; CI also runs the starter's specs on $boot3."
      ),
      Stack(
        "Spring Boot 4",
        "llm4s-spring-boot-starter",
        Seq(Versions.springBoot4Baseline, boot4).distinct,
        s"The same artifact's Boot 4 health indicator, built against ${Versions.springBoot4Baseline}; CI also runs the specs on $boot4."
      ),
      Stack("Cats Effect", "llm4s-effect", Seq(Versions.catsEffect), ""),
      Stack("fs2", "llm4s-effect", Seq(Versions.fs2), ""),
      Stack("ZIO", "llm4s-zio", Seq(Versions.zio), ""),
      Stack("Apache Pekko", "llm4s-pekko", Seq(Versions.pekko), ""),
      Stack("Project Reactor", "llm4s-reactor", Seq(Versions.reactor), ""),
      Stack("Kotlin", "llm4s-kotlin-api", Seq(kotlin), s"With kotlinx-coroutines $coroutines."),
      Stack(
        "Quarkus",
        "modules/samples/quarkus-java (sample)",
        Seq(quarkus),
        "A sample with a CDI producer, not an extension."
      ),
      Stack("http4s", "modules/examples-http (unpublished)", Seq(Versions.http4s), ""),
      Stack("Tapir", "modules/examples-http (unpublished)", Seq(Versions.tapir), ""),
      Stack("ZIO HTTP", "modules/examples-http (unpublished)", Seq(Versions.zioHttp), ""),
      Stack("Micrometer", "llm4s-observability-micrometer", Seq(Versions.micrometer), ""),
      Stack("OpenTelemetry", "llm4s-observability-otel", Seq(Versions.opentelemetry), ""),
      Stack("Prometheus client", "llm4s-observability-prometheus", Seq(Versions.prometheus), ""),
      Stack("circe", "docs/guide/json-libraries.md (tested recipe)", Seq(Versions.circe), ""),
      Stack("Play JSON", "docs/guide/json-libraries.md (tested recipe)", Seq(Versions.playJson), ""),
      Stack(
        "zio-json",
        "docs/guide/json-libraries.md (tested recipe)",
        Seq(Versions.zioJson),
        s"Pinned: later releases are built with a newer Scala 3 whose TASTy the ${Common.scala3} compiler cannot read."
      ),
      Stack("OpenAI Java SDK", "llm4s-openai", Seq(Versions.openaiJava), ""),
      Stack("Anthropic Java SDK", "llm4s-anthropic", Seq(Versions.anthropic), ""),
      Stack("AWS SDK", "llm4s-bedrock", Seq(Versions.awsSdk), "")
    )
    (ciJdks, stacks)
  }

  private def quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  def yaml(base: File): String = {
    val (ciJdks, all) = stacks(base)
    val header = Seq(
      "# Generated by `sbt generateCompatibilityMatrix` (project/CompatibilityMatrix.scala) from project/Dependencies.scala,",
      "# .github/workflows/ci.yml, modules/kotlin-api/build.gradle.kts and modules/samples/quarkus-java/pom.xml.",
      "# Do not edit: CI regenerates it and fails on drift. Rendered by docs/reference/compatibility-matrix.md.",
      s"scala: ${quote(Common.scala3)}",
      s"jdk_minimum: ${quote(Common.javaRelease)}",
      s"jdk_ci: ${ciJdks.map(quote).mkString("[", ", ", "]")}",
      "stacks:"
    )
    val rows = all.flatMap { s =>
      Seq(
        s"  - name: ${quote(s.name)}",
        s"    artifact: ${quote(s.artifact)}",
        s"    versions: ${s.versions.map(quote).mkString("[", ", ", "]")}",
        s"    note: ${quote(s.note)}"
      )
    }
    (header ++ rows).mkString("", "\n", "\n")
  }

  def write(base: File, log: Logger): Unit = {
    val out = base / OutputFile
    IO.write(out, yaml(base))
    log.info(s"Wrote $out")
  }
}
