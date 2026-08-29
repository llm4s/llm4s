import sbt.Keys._
import scoverage.ScoverageKeys._
import Common._
import Coverage.{ coverageDisabled, coverageFloor, coveragePolicy }

// sbt-git arrives transitively via sbt-ci-release, and its buildSettings eagerly evaluate
// `gitUncommittedChanges` through JGit. JGit cannot read a *linked git worktree* -- there
// `.git` is a file pointing into the main repo, and JGit raises
// "NoWorkTreeException: Bare Repository" -- so sbt fails to load in any worktree, which
// blocks running agents or parallel builds in worktrees. sbt-git labels the underlying key
// its "Git worktree workaround". Shelling out to the git CLI for read-only ops is correct
// everywhere and costs nothing here: the build reads no `git.*` settings, and versioning
// goes through sbt-dynver, which already uses the git CLI.
useReadableConsoleGit

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
    // Coverage floors are per-module, never inherited: every project must declare either
    // `coverageFloor(n)` or `coverageDisabled` (see project/Dependencies.scala -> Coverage).
    // This build-level default is the "no decision made" marker that `coveragePolicyCheck`
    // fails on, so a newly carved module cannot silently inherit somebody else's threshold.
    ThisBuild / coveragePolicy           := Coverage.Policy.Undeclared,
    ThisBuild / coverageHighlighting     := true,
    ThisBuild / coverageExcludedPackages := Seq(
      "org\\.llm4s\\.runner\\..*",
      "org\\.llm4s\\.samples\\..*",
      "org\\.llm4s\\.workspace\\..*"
    ).mkString(";"),
    ThisBuild / (coverageReport / aggregate) := false,
    // --- scalafix ---
    ThisBuild / scalafixDependencies += "ch.epfl.scala" %% "scalafix-rules" % "0.12.1",
    // Run Scalafix on compile only in CI (not locally to avoid developer friction);
    // local developers rely on pre-commit hooks and `sbt scalafixAll` for manual checks.
    ThisBuild / scalafixOnCompile := sys.env.getOrElse("CI", "false").toBoolean
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
addCommandAlias("chatTuiDemo", "samples/runMain org.llm4s.samples.chat.tui.ChatTuiMain")
addCommandAlias(
  "testFast",
  """;set core / Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-l", "org.llm4s.tags.SlowTest"); test"""
)
// ---- Tiered test aliases ----
// Every suite in `modules/it` declares its tier by class annotation (see project/ItTiers.scala);
// `it/itTierCheck` fails the build if one declares none. Each alias selects a tier by tag, so a
// new suite lands in a tier that actually runs instead of matching no `testOnly` pattern.
//
//   sbt test             Tier 1 - unit tests plus the `@Local` suites in modules/it
//   sbt testIntegration  Tier 2 - `@Docker`: needs Postgres/pgvector, Qdrant, Neo4j
//   sbt testWorkspace    Tier 2 - `@Workspace`: needs a built workspace-runner image + Docker
//   sbt testOllama       Tier 3 - `@Ollama`: needs a local Ollama with `qwen2.5:0.5b` pulled
//   sbt testSmoke        Tier 4 - `@Cloud`: real provider APIs, real money
//
// The aliases *replace* `it / Test / testOptions` because the default value restricts the run
// to the Local tier; adding a second `-n` would intersect to nothing.
addCommandAlias("testIntegration", ItTiers.alias(ItTiers.Docker))
addCommandAlias("testWorkspace", ItTiers.alias(ItTiers.Workspace))
addCommandAlias("testOllama", ItTiers.alias(ItTiers.Ollama))
addCommandAlias("testSmoke", ItTiers.alias(ItTiers.Cloud))

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
    Deps.log4jToSlf4j,
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

// `coveragePolicy` is read reflectively by `coveragePolicyCheck` (via Project.extract),
// so sbt's unused-setting lint cannot see the use.
Global / excludeLintKeys += coveragePolicy

// ---- coverage policy check ----
// Fails the build when a module has neither a coverage floor nor an explicit opt-out.
// The absence of a decision must be an error, not a silent default.
lazy val coveragePolicyCheck = taskKey[Unit](
  "Fail the build if any module has not explicitly declared a coverage floor or opt-out"
)

// ---- integration tier check ----
// Same principle one level down: an integration suite that declares no tier is run by no
// command and no CI job, and says nothing about it. See project/ItTiers.scala.
lazy val itTierCheck = taskKey[Unit](
  "Fail the build if any suite in modules/it has not declared exactly one test tier"
)

// ---- projects ----
lazy val llm4s = (project in file("."))
  .aggregate(
    core,
    samples,
    configPolicy,
    workspaceShared,
    workspaceRunner,
    workspaceClient,
    workspaceSamples,
    traceOpentelemetry,
    knowledgegraphNeo4j,
    benchmarks,
    // Aggregated so `it` is compiled, formatted and linted with everything else - it was
    // outside the aggregate entirely, so its suites could stop compiling unnoticed. Only the
    // `@Local` tier actually runs under `sbt test`; see `it / Test / testOptions` below.
    it,
    // Relocation stubs must be aggregated here: `sbt ci-release` publishes the root
    // aggregate, so a stub outside it would simply never be published.
    relocationCore,
    relocationWorkspaceClient,
    relocationWorkspaceShared,
    relocationTraceOpentelemetry,
    relocationKnowledgegraphNeo4j
  )
  .settings(
    publish / skip := true,
    // Root is an aggregator with no sources of its own. `coverageAggregate` runs here, and
    // the per-module floors are enforced by each module's own `coverageReport`, so the
    // aggregate number is reported but not gated (a build-wide average is exactly the kind
    // of misleading single threshold this change removes).
    coverageDisabled,
    coveragePolicyCheck / aggregate := false,
    coveragePolicyCheck := {
      val log       = streams.value.log
      val extracted = Project.extract(state.value)
      val rows = extracted.structure.allProjectRefs
        .map(ref => ref.project -> extracted.getOpt(ref / coveragePolicy).getOrElse(Coverage.Policy.Undeclared))
        .sortBy(_._1)
      val width = rows.map(_._1.length).max
      log.info("Coverage policy per module:")
      rows.foreach { case (id, policy) =>
        log.info(s"  ${id.padTo(width, ' ')}  ${Coverage.describe(policy)}")
      }
      val undeclared = rows.collect { case (id, Coverage.Policy.Undeclared) => id }
      if (undeclared.nonEmpty)
        throw new MessageOnlyException(
          s"""No coverage policy declared for: ${undeclared.mkString(", ")}
             |Every module must make an explicit decision in build.sbt - coverage floors are
             |never inherited. Add ONE of the following to the project's .settings(...):
             |  coverageFloor(<pct>)  // measured statement coverage rounded DOWN to nearest 5
             |  coverageDisabled      // with a comment saying why it is not measured
             |Also add a codecov flag for the module in codecov.yml in the same commit.""".stripMargin
        )
    }
  )

lazy val core = (project in file("modules/core"))
  .settings(
    name := "llm4s-core",
    commonSettings,
    // Measured 72.42% statement coverage (53,499 statements, 7004 tests) on main @ 5a62e2ac.
    // Floor is the measured value rounded down to the nearest 5. Ratchet it up as the
    // module is carved apart; never lower it.
    coverageFloor(70),
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
    name := "llm4s-workspace-shared",
    commonSettings,
    Compile / discoveredMainClasses := Seq.empty,
    // Not measured: excluded via ThisBuild / coverageExcludedPackages (org.llm4s.workspace.*)
    // and exercised only by containerised integration tests.
    coverageDisabled
  )

lazy val workspaceClient = (project in file("modules/workspace/workspaceClient"))
  .dependsOn(workspaceShared, core)
  .settings(
    name := "llm4s-workspace-client",
    commonSettings,
    Compile / discoveredMainClasses := Seq.empty,
    // Not measured: excluded via ThisBuild / coverageExcludedPackages (org.llm4s.workspace.*)
    // and exercised only by containerised integration tests.
    coverageDisabled,
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
    name := "llm4s-workspace-runner",
    commonSettings,
    Compile / mainClass := Some("org.llm4s.runner.RunnerMain"),
    libraryDependencies ++= Seq(
      Deps.cask,
      Deps.postgres,
      Deps.config,
      Deps.hikariCP
    ),
    publish / skip := true,
    // Not measured: Docker entry point, excluded via ThisBuild / coverageExcludedPackages
    // (org.llm4s.runner.*) and exercised only by containerised integration tests.
    coverageDisabled
  )
  .settings(WorkspaceRunnerDocker.settings)

lazy val samples = (project in file("modules//samples"))
  .dependsOn(core, knowledgegraphNeo4j)
  .settings(
    name := "llm4s-samples",
    commonSettings,
    publish / skip := true,
    // Not measured: unpublished example code, excluded via ThisBuild / coverageExcludedPackages
    // (org.llm4s.samples.*). Samples are compile-checked, not covered.
    coverageDisabled,
    libraryDependencies += Deps.termflow
  )

lazy val configPolicy = (project in file("modules/config-policy"))
  .dependsOn(core)
  .settings(
    name := "llm4s-config-policy",
    commonSettings,
    publish / skip := true,
    // Not measured: unpublished CLI tooling, verified end-to-end by the
    // config-policy-check CI job rather than by unit-test coverage.
    coverageDisabled,
    // Env-var-based engine CLI (EnvCheckPolicies) calls sys.exit, so its runMain
    // is forked. Keep the forked working directory at the repo root so the
    // catalog engine's relative --config paths (CheckPolicies) still resolve.
    run / fork  := true,
    Test / fork := true,
    run / baseDirectory := (LocalRootProject / baseDirectory).value,
    // Both engines compile here; Deps.config is also available transitively via core.
    libraryDependencies += Deps.config,
    Compile / mainClass := Some("org.llm4s.configpolicy.CheckPolicies")
  )

lazy val workspaceSamples = (project in file("modules/workspace/workspaceSamples"))
  .dependsOn(workspaceShared, workspaceRunner, workspaceClient, samples)
  .settings(
    name := "llm4s-workspace-samples",
    commonSettings,
    publish / skip := true,
    // Not measured: unpublished example code for the workspace modules.
    coverageDisabled
  )

lazy val traceOpentelemetry = (project in file("modules/trace-opentelemetry"))
  .dependsOn(core)
  .settings(
    name := "llm4s-observability-otel",
    commonSettings,
    // Measured 0.00% statement coverage (`sbt coverage traceOpentelemetry/test
    // traceOpentelemetry/coverageReport`): the module has no in-module tests at all, its
    // only suite is modules/it/.../OpenTelemetryTracingSpec, which needs a live collector.
    // Floor is the measured value rounded down to the nearest 5, i.e. 0 - measurement stays
    // ON so the number is visible, and the floor ratchets up as soon as unit tests land here.
    coverageFloor(0),
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
    // Enforce >=80% statement coverage when running with `sbt coverage test`
    // for the unit-test suite that ships with this module. Pre-existing gate, unchanged.
    coverageFloor(80)
  )

lazy val it = (project in file("modules/it"))
  .dependsOn(core, knowledgegraphNeo4j, workspaceClient, traceOpentelemetry)
  .settings(
    name := "llm4s-it",
    commonSettings,
    publish / skip := true,
    // Not measured: `modules/it` has no src/main at all - it is a test-only host for
    // integration/smoke suites that exercise the OTHER modules' code, so its own
    // statement count is zero and any floor would be meaningless. It gets a real
    // policy (and a codecov flag) when it is populated with sources in a later slice.
    coverageDisabled,
    Test / fork := true,
    // The default run - including the aggregated `sbt test` - is the Local tier only.
    // Everything else needs a database, an image build, a model server or a paid API key.
    // The tier aliases replace this setting rather than adding to it (see ItTiers.alias).
    //
    // Scoped to `test` rather than to the whole Test configuration on purpose: `testOnly`
    // names a suite explicitly, so `it/testOnly org.llm4s.vectorstore.PgVectorStoreSpec`
    // must run that suite whatever tier it is in. A filter there would answer a request for
    // one suite by running nothing, which is the failure this whole change is about.
    Test / test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-n", ItTiers.Local),
    // ContainerisedWorkspaceTest runs against whatever `workspaceRunner/Docker/publishLocal`
    // produced; take the tag from the build so the test and the image cannot drift apart.
    Test / envVars += "LLM4S_WORKSPACE_IMAGE" -> s"llm4s/workspace-runner:${(workspaceRunner / Docker / version).value}",
    itTierCheck := ItTiers.check(
      (Test / definedTests).value.map(_.name),
      (Test / fullClasspath).value.map(_.data),
      streams.value.log
    ),
    // Any tier run proves its own membership first, so a mistagged suite fails where it is
    // noticed rather than by quietly running nothing.
    Test / test := (Test / test).dependsOn(itTierCheck).value,
    libraryDependencies ++= Seq(
      Deps.scalatest % Test
    )
  )

lazy val benchmarks = (project in file("modules/benchmarks"))
  .dependsOn(core)
  .enablePlugins(JmhPlugin)
  .settings(
    name           := "llm4s-benchmarks",
    commonSettings,
    publish / skip := true,
    // Measured 100.00% statement/branch coverage (`sbt coverage benchmarks/test
    // benchmarks/coverageReport`): BenchmarkSmokeTest deliberately instantiates and runs
    // every JMH benchmark. Floor is the measured value rounded down to the nearest 5, i.e.
    // 100, which encodes exactly that policy - a new benchmark must be added to the smoke
    // test. (Codecov ignores modules/benchmarks; this is a build-side gate only.)
    coverageFloor(100),
    libraryDependencies ++= Seq(
      Deps.scalatest % Test
    )
  )

// ---- relocation stubs for the 0.4.0 artifact rename ----
// Each project below publishes ONLY a POM at the retired coordinate, carrying a Maven
// `<relocation>` that points at its replacement. They deliberately carry no sources, no
// `commonSettings` and no Scala library, so they compile nothing; see project/Relocation.scala
// for what a relocation POM does and does not achieve.
//
// Only coordinates with real published history on Maven Central get a stub - publishing a
// relocation for something that never existed would be noise. Verified present under
// https://repo1.maven.org/maven2/org/llm4s/ : core_3, workspaceclient_3, workspaceshared_3,
// trace-opentelemetry_3, knowledgegraph-neo4j_3 (all through 0.3.4).
//
// `workspacerunner`, `samples` and the other unpublished modules have `publish / skip` and
// no Central history, so they need no stub.

lazy val relocationCore = (project in file("modules/relocations/core"))
  .settings(Relocation.settings("core", "llm4s-core_3"))

lazy val relocationWorkspaceClient = (project in file("modules/relocations/workspaceclient"))
  .settings(Relocation.settings("workspaceclient", "llm4s-workspace-client_3"))

lazy val relocationWorkspaceShared = (project in file("modules/relocations/workspaceshared"))
  .settings(Relocation.settings("workspaceshared", "llm4s-workspace-shared_3"))

lazy val relocationTraceOpentelemetry = (project in file("modules/relocations/trace-opentelemetry"))
  .settings(Relocation.settings("trace-opentelemetry", "llm4s-observability-otel_3"))

lazy val relocationKnowledgegraphNeo4j = (project in file("modules/relocations/knowledgegraph-neo4j"))
  .settings(Relocation.settings("knowledgegraph-neo4j", "llm4s-knowledgegraph-neo4j_3"))
