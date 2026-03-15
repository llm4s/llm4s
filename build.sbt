import sbt.Keys._
import scoverage.ScoverageKeys._
import Common._

inThisBuild(
  List(
    scalaVersion       := scala3,
    organization       := "org.llm4s",
    organizationName   := "llm4s",
    versionScheme      := Some("early-semver"),
    homepage := Some(url("https://github.com/llm4s/")),
    licenses := List("MIT" -> url("https://mit-license.org/")),
    developers := List(
      Developer(
        "rorygraves",
        "Rory Graves",
        "rory.graves@fieldmark.co.uk",
        url("https://github.com/rorygraves")
      )
    ),
    // Publish to Sonatype Central Portal via staging
    ThisBuild / publishTo := {
      val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
      if (isSnapshot.value) Some("central-snapshots".at(centralSnapshots))
      else localStaging.value
    },
    pgpPublicRing := file("/tmp/public.asc"),
    pgpSecretRing := file("/tmp/secret.asc"),
    pgpPassphrase := sys.env.get("PGP_PASSPHRASE").map(_.toArray),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/llm4s/llm4s/"),
        "scm:git:git@github.com:llm4s/llm4s.git"
      )
    ),
    version := {
      dynverGitDescribeOutput.value match {
        case Some(out) if !out.isSnapshot() =>
          out.ref.value.stripPrefix("v")
        case Some(out) =>
          val baseVersion = out.ref.value.stripPrefix("v")
          s"$baseVersion+${out.commitSuffix.mkString("", "", "")}-SNAPSHOT"
        case None =>
          "0.0.0-UNKNOWN"
      }
    },
    ThisBuild / coverageMinimumStmtTotal := 50,
    ThisBuild / coverageFailOnMinimum    := true,
    ThisBuild / coverageHighlighting     := true,
    ThisBuild / coverageExcludedPackages := Seq(
      "org\\.llm4s\\.runner\\..*",
      "org\\.llm4s\\.samples\\..*",
      "org\\.llm4s\\.workspace\\..*"
    ).mkString(";"),
    ThisBuild / (coverageReport / aggregate) := false,
    // --- scalafix ---
    ThisBuild / scalafixDependencies += "ch.epfl.scala" %% "scalafix-rules" % "0.12.1",
    ThisBuild / scalafixOnCompile := false  // Disabled for now
  )
)

// ---- Handy aliases ----
addCommandAlias("cov", ";clean;coverage;test;coverageAggregate;coverageReport;coverageOff")
addCommandAlias("covReport", ";clean;coverage;test;coverageReport;coverageOff")
addCommandAlias("buildAll", ";clean;compile;test")
addCommandAlias("publishAll", ";clean;publish")
addCommandAlias("testAll", ";test")
addCommandAlias(
  "cleanTestAll",
  ";clean;testAll"
)
addCommandAlias(
  "cleanTestAllAndFormat",
  ";scalafmtAll;cleanTestAll"
)
addCommandAlias("compileAll", ";compile")
addCommandAlias(
  "testFast",
  """;set core / Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-l", "org.llm4s.tags.SlowTest"); test"""
)
// ---- Three-tier test aliases ----
// Default `test` runs unit + local HTTP server tests (Tier 1), excluding tagged tests.
// testOllama: Tier 2 — integration tests against a local Ollama instance (requires `ollama pull qwen2.5:0.5b`)
// testSmoke:  Tier 3 — cloud smoke tests against real APIs (requires API keys in .env or environment)
addCommandAlias(
  "testOllama",
  """;set core / Test / testOptions := Seq(); core/testOnly -- -n org.llm4s.tags.OllamaRequired"""
)
addCommandAlias(
  "testSmoke",
  """;set core / Test / testOptions := Seq(); core/testOnly -- -n org.llm4s.tags.CloudSmoke"""
)

// ---- shared settings ----
lazy val commonSettings = Seq(
  Compile / scalacOptions := scalacOptionsForVersion(scalaVersion.value),
  Test / scalacOptions    := scalacOptionsForVersion(scalaVersion.value),
  // Suppress ScalaDoc warnings from third-party libraries (e.g., ScalaTest)
  Compile / doc / scalacOptions ++= Seq("-Wconf:cat=scaladoc:silent"),
  semanticdbEnabled       := true,
  Test / scalafix / unmanagedSources := Seq.empty,
  Compile / packageDoc / publishArtifact := !isSnapshot.value,
  // Disable test Scaladoc generation during publish (not needed, saves memory in CI)
  Test / packageDoc / publishArtifact := false,
  Test / doc / sources := Seq.empty,
  libraryDependencies ++= Seq(
    Deps.cats,
    Deps.upickle,
    Deps.logback,
    Deps.monocleCore,
    Deps.monocleMacro,
    Deps.scalatest % Test,
    Deps.scalamock % Test,
    Deps.scalatestplusScalacheck % Test,
    Deps.fansi,
    Deps.postgres,
    Deps.sqlite,
    Deps.config,
    Deps.pureConfig,
    Deps.hikariCP
  )
)

// ---- projects ----
lazy val llm4s = (project in file("."))
  .aggregate(core, samples, workspaceShared, workspaceRunner, workspaceClient, workspaceSamples, traceOpentelemetry, knowledgegraphNeo4j)
  .settings(
    publish / skip := true
  )

lazy val core = (project in file("modules/core"))
  .settings(
    name := "llm4s-core",
    commonSettings,
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "-Xmx2g", "-Xms512m",
      "-XX:+UseG1GC",
      "-XX:+TieredCompilation",
      "-XX:TieredStopAtLevel=1"
    ),
    // Pass API key entries from .env into forked test JVM (for smoke/integration tests).
    // Only forwards *_API_KEY variables to avoid polluting test configuration
    // (e.g. TRACING_MODE would break Llm4sConfigTracingSpec defaults).
    Test / envVars ++= {
      val envFile = (ThisBuild / baseDirectory).value / ".env"
      if (envFile.exists()) {
        IO.readLines(envFile)
          .filterNot(l => l.trim.isEmpty || l.trim.startsWith("#"))
          .flatMap { line =>
            line.split("=", 2) match {
              case Array(k, v) if k.trim.endsWith("_API_KEY") => Some(k.trim -> v.trim)
              case _                                          => None
            }
          }
          .toMap
      } else Map.empty
    },
    Test / testOptions += Tests.Argument(
      TestFrameworks.ScalaTest,
      "-l", "org.llm4s.tags.OllamaRequired",
      "-l", "org.llm4s.tags.CloudSmoke"
    ),
    Compile / mainClass := None,
    Compile / discoveredMainClasses := Seq.empty,
    resolvers += "Vosk Repository" at "https://alphacephei.com/maven/",
    libraryDependencies ++= Seq(
      Deps.azureOpenAI,
      Deps.anthropic,
      Deps.jtokkit,
      Deps.websocket,
      Deps.scalatest % Test,
      Deps.scalamock % Test,
      Deps.ujson,
      Deps.pdfbox,
      Deps.commonsIO,
      Deps.tika,
      Deps.poi,
      Deps.jsoup,
      Deps.jna,
      Deps.vosk,
      Deps.postgres,
      Deps.config,
      Deps.hikariCP,
      Deps.awsS3,
      Deps.awsSts,
      Deps.prometheusCore,
      Deps.prometheusHttp
    )
  )

lazy val workspaceShared = (project in file("modules/workspace/workspaceShared"))
  .settings(
    name := "workspaceShared",
    commonSettings,
    Compile / discoveredMainClasses := Seq.empty,
    coverageEnabled := false
  )

lazy val workspaceClient = (project in file("modules/workspace/workspaceClient"))
  .dependsOn(workspaceShared, core)
  .settings(
    name := "workspaceClient",
    commonSettings,
    Compile / discoveredMainClasses := Seq.empty,
    coverageEnabled := false,
    libraryDependencies ++= Seq(
      Deps.azureOpenAI,
      Deps.anthropic,
      Deps.jtokkit,
      Deps.websocket,
      Deps.scalatest % Test,
      Deps.scalamock % Test,
      Deps.ujson,
      Deps.pdfbox,
      Deps.commonsIO,
      Deps.tika,
      Deps.poi,
      Deps.jsoup,
      Deps.jna,
      Deps.vosk,
      Deps.postgres,
      Deps.config,
      Deps.hikariCP
    )
  )

lazy val workspaceRunner = (project in file("modules/workspace/workspaceRunner"))
  .dependsOn(workspaceShared)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "workspaceRunner",
    commonSettings,
    Compile / mainClass := Some("org.llm4s.runner.RunnerMain"),
    libraryDependencies ++= Seq(
      Deps.cask,
      Deps.postgres,
      Deps.config,
      Deps.hikariCP
    ),
    publish / skip := true,
    coverageEnabled := false
  )
  .settings(WorkspaceRunnerDocker.settings)

lazy val samples = (project in file("modules//samples"))
  .dependsOn(core)
  .settings(
    name := "samples",
    commonSettings,
    publish / skip := true,
    coverageEnabled := false
  )

lazy val workspaceSamples = (project in file("modules/workspace/workspaceSamples"))
  .dependsOn(workspaceShared, workspaceRunner, workspaceClient, samples)
  .settings(
    name := "workspaceSamples",
    commonSettings,
    publish / skip := true,
    coverageEnabled := false
  )

lazy val traceOpentelemetry = (project in file("modules/trace-opentelemetry"))
  .dependsOn(core)
  .settings(
    name := "llm4s-trace-opentelemetry",
    commonSettings,
    libraryDependencies ++= Seq(
      Deps.opentelemetryApi,
      Deps.opentelemetrySdk,
      Deps.opentelemetryExporterOtlp
    )
  )

lazy val knowledgegraphNeo4j = (project in file("modules/knowledgegraph-neo4j"))
  .dependsOn(core)
  .settings(
    name             := "llm4s-knowledgegraph-neo4j",
    commonSettings,
    Test / fork      := true,
    libraryDependencies ++= Seq(
      Deps.neo4jDriver,
      Deps.scalatest % Test
    ),
    // Enforce ≥80% statement coverage when running with `sbt coverage test`
    // (requires Neo4j on bolt://localhost:7687 — tests auto-skip without it)
    coverageMinimumStmtTotal := 80,
    coverageFailOnMinimum    := false // don't fail CI when Neo4j is absent
  )
