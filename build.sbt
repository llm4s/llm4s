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
    scalaVersion     := scala3,
    organization     := "org.llm4s",
    organizationName := "llm4s",
    versionScheme    := Some("early-semver"),
    // The documentation site. The organization keeps the GitHub organization page, which the POM's
    // `<organization><url>` shows; both used to be the organization page.
    homepage             := Some(url("https://llm4s.org")),
    organizationHomepage := Some(url("https://github.com/llm4s/")),
    licenses             := List("MIT" -> url("https://mit-license.org/")),
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
    // Scaladex associates an artifact with a repository by the POM's `scm` element and supports only public
    // GitHub repositories (https://github.com/scalacenter/scaladex#how-it-works): a plain `https` repository URL
    // without a trailing slash is the form it parses most simply.
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/llm4s/llm4s"),
        "scm:git:https://github.com/llm4s/llm4s.git"
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
    ThisBuild / coveragePolicy       := Coverage.Policy.Undeclared,
    ThisBuild / coverageHighlighting := true,
    ThisBuild / coverageExcludedPackages := Seq(
      "org\\.llm4s\\.runner\\..*",
      // The deploy service's entry point starts a server and exits; the routes, the check and the
      // configuration behind it are measured, and the image smoke test in deploy-staged.yml runs it.
      "org\\.llm4s\\.deploy\\.DeployServiceMain",
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

// ---- binary compatibility (MiMa) ----
// MiMa compares a module with the artifact of the same name in a previous release. It can only
// run once split artifacts exist: the last release (0.4.1) is a single `llm4s-core`, and the
// modularisation (#1126) moved every package this check would cover. #1281 sets the baseline at
// 0.5.0, the first release with the split coordinates, for the frozen modules only. Until then
// `mimaBaselineVersion` is `None`, `mimaPreviousArtifacts` is empty and
// `sbt mimaReportBinaryIssues` checks nothing. Set it to `Some("0.5.0")` when 0.5.0 is
// published; see docs/reference/api-stability.md.
val mimaBaselineVersion: Option[String] = None

// `module` is the artifact name (the project's `name`). Apply this to frozen modules only.
def mimaFrozen(module: String) = Seq(
  mimaPreviousArtifacts := mimaBaselineVersion.map(v => "org.llm4s" %% module % v).toSet,
  mimaFailOnNoPrevious  := false
)

// ---- shared settings ----
lazy val commonSettings = Seq(
  // The one-sentence POM description of each published module (project/PomDescriptions.scala).
  description := PomDescriptions.of(name.value),
  // Modules outside the frozen set have no baseline; keep MiMa quiet for them.
  mimaFailOnNoPrevious    := false,
  Compile / scalacOptions := scalacOptionsForVersion(scalaVersion.value),
  Test / scalacOptions    := scalacOptionsForVersion(scalaVersion.value),
  // Suppress ScalaDoc warnings from third-party libraries (e.g., ScalaTest)
  Compile / doc / scalacOptions ++= Seq("-Wconf:cat=scaladoc:silent"),
  semanticdbEnabled                      := true,
  Test / scalafix / unmanagedSources     := Seq.empty,
  Compile / packageDoc / publishArtifact := !isSnapshot.value,
  // Disable test Scaladoc generation during publish (not needed, saves memory in CI)
  Test / packageDoc / publishArtifact := false,
  Test / doc / sources                := Seq.empty,
  // `-oD`: print each test's duration, so a slow test (or a platform that is slow at one thing,
  // such as Windows refusing a loopback connection) shows up in the log, locally and in CI.
  Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
  // Published modules log through `slf4j-api` only. Choosing a logging backend is the
  // application's decision: `logback-classic` and the `log4j-to-slf4j` bridge used to be compile
  // dependencies here, so every llm4s artifact put them on its users' classpath - a second
  // backend for an application on log4j2, and a conflict with `log4j-core` (#1133). They are
  // test-scoped here, for test output and the specs that attach a logback appender, and
  // `appLogging` adds them to the unpublished applications. `monocle` (imported nowhere) and
  // `fansi` (only core's `assistant` uses it) were dropped from this list at the same time.
  libraryDependencies ++= Seq(
    Deps.cats,
    Deps.upickle,
    Deps.slf4jApi,
    Deps.scalatest               % Test,
    Deps.scalamock               % Test,
    Deps.scalatestplusScalacheck % Test,
    Deps.logback                 % Test,
    Deps.log4jToSlf4j            % Test,
    Deps.config,
    Deps.pureConfig
  )
)

// The logging backend for the projects that are applications rather than libraries - samples,
// the workspace runner, the config-policy CLI and the benchmarks. None of them is published.
lazy val appLogging = libraryDependencies ++= Seq(Deps.logback, Deps.log4jToSlf4j)

// `Deps.postgres`, `Deps.sqlite` and `Deps.hikariCP` used to live in `commonSettings`, which
// put a JDBC driver and a connection pool on every module's classpath - including modules
// with no database code at all. Slice 2 (#1129) needs core to shed HikariCP and Postgres, and
// a shared default is not something core can shed on its own. They are now declared by the
// projects that actually open a connection: `rag`, `memory`, `memoryPostgres`, and the
// workspace projects that already declared them explicitly.

// `coveragePolicy` is read reflectively by `coveragePolicyCheck` (via Project.extract),
// so sbt's unused-setting lint cannot see the use.
Global / excludeLintKeys += coveragePolicy

// ---- published artifact check ----
// A module can ship without a stability tier or an install line, because nothing connects what the
// build publishes to the docs that name it. See project/PublishedArtifacts.scala.
lazy val publishedArtifactsCheck = taskKey[Unit](
  "Fail the build if a published llm4s-* artifact is not named in v1-scope.md and installation.md, or has no POM description of its own"
)
// What a release must put on Maven Central, one `artifact <id>`, `bom <id>` or `stub <id>` per line, for
// scripts/verify-release.sh. Run it with `sbt -error listPublishedArtifacts`.
lazy val listPublishedArtifacts = taskKey[Unit](
  "Print the Maven coordinates a release must publish: artifact lines, then the BOM, then relocation stub lines"
)
// The BOM (`org.llm4s:llm4s-bom`) lists every published artifact. It is generated from the same project list
// as `listPublishedArtifacts`; this check fails when the generated POM disagrees. See project/Bom.scala.
lazy val bomCheck = taskKey[Unit](
  "Fail the build if the generated llm4s-bom does not list exactly the artifacts the build publishes"
)

// ---- coverage policy check ----
// Fails the build when a module has neither a coverage floor nor an explicit opt-out.
// The absence of a decision must be an error, not a silent default.
lazy val coveragePolicyCheck = taskKey[Unit](
  "Fail the build if any module has not explicitly declared a coverage floor or opt-out"
)

// ---- frozen dependency check ----
// A frozen module must not resolve a document-parsing, speech, cloud-storage, database,
// observability-backend or foreign vendor-SDK dependency, directly or transitively: freezing it
// would freeze that stack too. See project/FrozenDependencies.scala. The modules are the ones that
// call `mimaFrozen`.
lazy val frozenDependencyCheck = taskKey[Unit](
  "Fail the build if a frozen module resolves a dependency the modularisation moved out of it"
)

// ---- integration tier check ----
// Same principle one level down: an integration suite that declares no tier is run by no
// command and no CI job, and says nothing about it. See project/ItTiers.scala.
lazy val itTierCheck = taskKey[Unit](
  "Fail the build if any suite in modules/it has not declared exactly one test tier"
)

// ---- stability tier check ----
// The tier of a frozen module's public types lives in the code (`@Stable` / `@Experimental`,
// `org.llm4s.annotation`), not only in docs/reference/v1-scope.md where it drifts. See
// project/StabilityTiers.scala. The modules are the ones that call `mimaFrozen`, minus
// `llm4s-agent`, whose tier waits on the typed graph runtime (#1266, open question in #1281).
lazy val stabilityTierCheck = taskKey[Unit](
  "Fail the build if a top-level public type of a frozen module is not marked @Stable or @Experimental"
)
val stabilityTierModules = Seq("core", "openai", "openai-compatible", "anthropic", "gemini", "ollama")
// Beta dialects that live inside the frozen `llm4s-openai-compatible`: 1.0 Scope does not freeze them.
val stabilityExperimentalFiles = Seq("""/(Mistral|Cohere)[A-Za-z]*\.scala$""".r)

// ---- projects ----
lazy val llm4s = (project in file("."))
  .aggregate(
    media,
    core,
    rag,
    knowledgegraph,
    memory,
    memoryPostgres,
    mcp,
    image,
    speech,
    ollama,
    gemini,
    anthropic,
    openai,
    openaiCompatible,
    voyage,
    bedrock,
    jina,
    cohere,
    watsonx,
    providerTestkit,
    llm4sEffect,
    llm4sZio,
    javaApi,
    springBootStarter,
    samples,
    configPolicy,
    workspaceShared,
    workspaceRunner,
    workspaceClient,
    workspaceSamples,
    observability,
    observabilityPrometheus,
    traceOpentelemetry,
    agent,
    agentTools,
    knowledgegraphNeo4j,
    gradleDemo,
    benchmarks,
    deployService,
    // Aggregated so `it` is compiled, formatted and linted with everything else - it was
    // outside the aggregate entirely, so its suites could stop compiling unnoticed. Only the
    // `@Local` tier actually runs under `sbt test`; see `it / Test / testOptions` below.
    it,
    // The BOM must be aggregated here for the same reason as the stubs below: `sbt ci-release`
    // publishes the root aggregate, so a BOM outside it would simply never be published.
    bom,
    // Relocation stubs must be aggregated here: `sbt ci-release` publishes the root
    // aggregate, so a stub outside it would simply never be published.
    relocationCore,
    relocationWorkspaceClient,
    relocationWorkspaceShared,
    relocationTraceOpentelemetry,
    relocationKnowledgegraphNeo4j
  )
  .settings(
    // `sbt "dumpBuildModel <file>"`: the build's projects, keys, commands and aliases as JSON, read by
    // scripts/check-doc-support.sh. See project/BuildModel.scala.
    commands += BuildModel.dumpCommand,
    publish / skip                      := true,
    mimaFailOnNoPrevious                := false,
    publishedArtifactsCheck / aggregate := false,
    publishedArtifactsCheck := {
      val projects = Def.task((name.value, (publish / skip).value)).all(ScopeFilter(inAnyProject)).value
      val described =
        Def.task((name.value, (publish / skip).value, description.value)).all(ScopeFilter(inAnyProject)).value
      PublishedArtifacts.check(projects, (ThisBuild / baseDirectory).value, streams.value.log)
      PomDescriptions.check(described, streams.value.log)
    },
    listPublishedArtifacts / aggregate := false,
    listPublishedArtifacts := {
      val projects      = Def.task((name.value, (publish / skip).value)).all(ScopeFilter(inAnyProject)).value
      val (real, stubs) = PublishedArtifacts.coordinates(projects, (ThisBuild / scalaBinaryVersion).value)
      real.foreach(a => println(s"artifact $a"))
      PublishedArtifacts.boms(projects).foreach(a => println(s"bom $a"))
      stubs.foreach(a => println(s"stub $a"))
    },
    bomCheck / aggregate := false,
    bomCheck := {
      val projects = Def.task((name.value, (publish / skip).value)).all(ScopeFilter(inAnyProject)).value
      val pom      = (bom / makePom).value
      PublishedArtifacts.checkBom(
        projects,
        (ThisBuild / scalaBinaryVersion).value,
        pom,
        (bom / version).value,
        streams.value.log
      )
    },
    // Root is an aggregator with no sources of its own. `coverageAggregate` runs here, and
    // the per-module floors are enforced by each module's own `coverageReport`, so the
    // aggregate number is reported but not gated (a build-wide average is exactly the kind
    // of misleading single threshold this change removes).
    coverageDisabled,
    stabilityTierCheck / aggregate := false,
    stabilityTierCheck := StabilityTiers.check(
      (ThisBuild / baseDirectory).value,
      stabilityTierModules,
      stabilityExperimentalFiles,
      streams.value.log
    ),
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
    },
    frozenDependencyCheck / aggregate := false,
    frozenDependencyCheck := {
      def resolved(report: UpdateReport, declared: Seq[ModuleID]) =
        report
          .configuration(ConfigRef("runtime"))
          .map(_.modules)
          .getOrElse(Vector.empty)
          .map(m => FrozenDependencies.Resolved(m.module, declared.exists(_.organization == m.module.organization)))
      FrozenDependencies.check(
        Seq(
          "llm4s-core"   -> resolved((core / update).value, (core / libraryDependencies).value),
          "llm4s-agent"  -> resolved((agent / update).value, (agent / libraryDependencies).value),
          "llm4s-openai" -> resolved((openai / update).value, (openai / libraryDependencies).value),
          "llm4s-openai-compatible" -> resolved(
            (openaiCompatible / update).value,
            (openaiCompatible / libraryDependencies).value
          ),
          "llm4s-anthropic" -> resolved((anthropic / update).value, (anthropic / libraryDependencies).value),
          "llm4s-gemini"    -> resolved((gemini / update).value, (gemini / libraryDependencies).value),
          "llm4s-ollama"    -> resolved((ollama / update).value, (ollama / libraryDependencies).value)
        ),
        streams.value.log
      )
    }
  )

// ---- shared multimodal vocabulary (#1130) ----
// A media type - MIME string, canonical extension, category - is the one thing the image,
// speech and extraction subsystems all had to name, so each grew its own copy: core carried
// three overlapping image-format enumerations and RAG matched on raw MIME prefixes. They are
// consolidated here, ahead of `image` and `speech` carving out, so those carves are pure file
// moves rather than moves plus a vocabulary change.
//
// Vocabulary only: no I/O, no content sniffing, no dependencies. Sniffing a file's real type
// needs Tika and stays in `llm4s-rag`, which resolves the MIME string it gets back through
// `MediaType.fromMimeType`. That is what keeps this module something every consumer can
// depend on without inheriting anything.

lazy val media = (project in file("modules/media"))
  .settings(
    name := "llm4s-media",
    commonSettings,
    // Measured 100.00% statement coverage (`sbt coverage media/test media/coverageReport`).
    // A dependency-free vocabulary with no I/O has no excuse for less. Never lower it.
    coverageFloor(100),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.scalatest % Test
    )
  )

lazy val llm4sEffect = (project in file("modules/llm4s-effect"))
  .dependsOn(core, agent)
  .settings(
    name := "llm4s-effect",
    commonSettings,
    // Measured 65.22% statement coverage (`sbt coverage llm4sEffect/test llm4sEffect/coverageReport`).
    // The uncovered rest is `LLMClientIO.resource`, which loads provider config from the environment.
    // Floor is the measured value rounded down to the nearest 5. Never lower it.
    coverageFloor(65),
    libraryDependencies ++= Seq(
      Deps.catsEffect,
      Deps.fs2,
      Deps.scalatest % Test
    )
  )

lazy val llm4sZio = (project in file("modules/llm4s-zio"))
  .dependsOn(core, agent)
  .settings(
    name := "llm4s-zio",
    commonSettings,
    // Measured 65.91% statement coverage (`sbt coverage llm4sZio/test llm4sZio/coverageReport`).
    // The uncovered rest is `LLMClientZ.layer`, which loads provider config from the environment.
    // Floor is the measured value rounded down to the nearest 5. Never lower it.
    coverageFloor(65),
    libraryDependencies ++= Seq(
      Deps.zio,
      Deps.zioStreams,
      Deps.zioTest    % Test,
      Deps.zioTestSbt % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

lazy val core = (project in file("modules/core"))
  .settings(
    name := "llm4s-core",
    commonSettings,
    mimaFrozen("llm4s-core"),
    // Measured 73.67% statement coverage after the `agent` carve took the agent runtime (80.80%
    // covered) out (#1242); 75.90% after the `agent-tools` carve took the built-in tools
    // (66.18% covered) out; 74.09% after the `observability-prometheus` carve took
    // `PrometheusMetrics`, `PrometheusEndpoint` and `MetricsConfigLoader` (70.56% covered) out
    // (#1133); 73.59% after the `observability` carve took Langfuse, the trace
    // collector and `CostTracker` (94.52% covered) out; 74.65% with every provider client
    // gone - Mistral, Cohere and Voyage were the last (`sbt coverage core/test core/coverageReport`); it was 75.32% after
    // `openai-compatible`, 75.57% after `openai`, 75.15% after `anthropic`, 75.27% after `gemini`, 75.86%
    // after `ollama`, 74.33% with slice 3 complete, 74.89% after `image`, 74.05% after `mcp`,
    // 73.85% after slice 2 and 72.42% on main @ 5a62e2ac before any of them. A carve moves the
    // number in whichever direction the departing code sat - `speech` (80.68%), `gemini`
    // (87.53%), `anthropic` (81.32%) and `openai-compatible` pulled it down, the slice-4 SPI work and the `ollama`
    // and `openai` (62.34%) carves pushed it up. Floor is the measured value rounded down to
    // the nearest 5; ratchet it up, never down.
    //
    // It was held at 70 while carves were in flight, since each moved this number in whichever
    // direction the departing code sat. With `agent` gone, the last carve has landed, and 73.67%
    // rounds down to 70 anyway: from here the rule above applies as written.
    coverageFloor(70),
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "-Xmx2g",
      "-Xms512m",
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
      "-l",
      "org.llm4s.tags.OllamaRequired",
      "-l",
      "org.llm4s.tags.CloudSmoke"
    ),
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.jtokkit,
      Deps.scalatest % Test,
      Deps.scalamock % Test,
      Deps.ujson,
      Deps.config
    )
  )

// ---- slice 1 of the modularisation programme (#1128) ----
// `knowledgegraph` carves first because `rag` depends on it; the reverse edge that used to
// make them inseparable (knowledgegraph/graphrag -> vectorstore) is gone, because GraphRAG
// itself now lives in `rag`. Package names are unchanged on both sides: users of 0.4.x add a
// dependency, they do not rewrite imports.

lazy val knowledgegraph = (project in file("modules/knowledgegraph"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "llm4s-knowledgegraph",
    commonSettings,
    // Measured 89.49% statement coverage (`sbt coverage knowledgegraph/test
    // knowledgegraph/coverageReport`) on the code as carved out of core.
    // Floor is the measured value rounded down to the nearest 5. Never lower it.
    coverageFloor(85),
    Test / fork := true
  )

lazy val rag = (project in file("modules/rag"))
  // `media` is declared explicitly, not inherited through core: core's edge to it is
  // temporary and goes away with the `llm4s-image` carve, but `MediaExtractor`'s does not.
  // `openai` is test-only: `RAGConfig.default` names the `openai` embedding provider, which
  // left core with `llm4s-openai` (#1132), and the mocked RAG suites build from that default.
  // `llm4s-rag` itself does not depend on it - a user names whichever provider they ship.
  // `observability` is for `RAGASLangfuseObserver`, which sends through Langfuse's batch sender;
  // it carries no third-party dependency, so it costs a RAG user nothing (#1133).
  .dependsOn(
    media,
    core % "compile->compile;test->test",
    knowledgegraph,
    observability,
    openai          % "test->compile",
    providerTestkit % Test
  )
  .settings(
    name := "llm4s-rag",
    commonSettings,
    // Measured 65.62% statement coverage (`sbt coverage rag/test rag/coverageReport`) on the
    // code as carved out of core. Floor is the measured value rounded down to the nearest 5.
    // Never lower it. The number is lower than core's because the mocked `vectorstore` and
    // `rag` suites that came with this code test less of it than their count suggests - see
    // the three real bugs #1149 found in exactly this code.
    coverageFloor(65),
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "-Xmx2g",
      "-Xms512m",
      "-XX:+UseG1GC",
      "-XX:+TieredCompilation",
      "-XX:TieredStopAtLevel=1"
    ),
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      // The six heavy dependencies this carve takes off `llm4s-core`.
      Deps.tika,
      Deps.poi,
      Deps.pdfbox,
      Deps.jsoup,
      Deps.awsS3,
      Deps.awsSts,
      Deps.ujson,
      // No longer inherited from `commonSettings` (see the note there). `vectorstore` and
      // `rag.permissions.pg` open both SQLite and Postgres connections, pooled by Hikari.
      Deps.postgres,
      Deps.sqlite,
      Deps.hikariCP,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// ---- slice 2 of the modularisation programme (#1129) ----
// `agent/memory` leaves core. The stores split across two artifacts because
// `PostgresMemoryStore` was the package's only heavyweight: keeping it with the rest would
// mean anyone using agent memory at all inherits HikariCP and a JDBC driver.
//
// Nothing outside `org.llm4s.agent.memory` referenced it, so the whole package moves with no
// facade left behind, and the package name is unchanged: users of 0.4.x add a dependency
// rather than rewriting imports.

lazy val memory = (project in file("modules/memory"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "llm4s-memory",
    commonSettings,
    // Measured 81.10% statement coverage (`sbt coverage memory/test memory/coverageReport`)
    // on the code as carved out of core. Floor is the measured value rounded down to the
    // nearest 5. Never lower it.
    coverageFloor(80),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      // `SQLiteMemoryStore` and `VectorMemoryStore` are file-backed; no pool, no Postgres.
      Deps.sqlite,
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

lazy val memoryPostgres = (project in file("modules/memory-postgres"))
  .dependsOn(memory)
  .settings(
    name := "llm4s-memory-postgres",
    commonSettings,
    // Measured 60.33% statement coverage (`sbt coverage memoryPostgres/test
    // memoryPostgres/coverageReport`) on the code as carved out of core. Floor is the measured
    // value rounded down to the nearest 5. Never lower it. The in-module suite is mock-backed;
    // the real signal is `modules/it`'s `PostgresMemoryStoreSpec`, which runs on every PR
    // against a pgvector service container (#1149) and is not counted here.
    coverageFloor(60),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      // The two dependencies this split keeps off `llm4s-memory` - and, with it, off core.
      Deps.postgres,
      Deps.hikariCP,
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// ---- slice 3 of the modularisation programme (#1130) ----
// `mcp`, `image` and `speech` are three self-contained subsystems with no inbound edge from
// the rest of core and no edge to each other, so they carve independently.

lazy val mcp = (project in file("modules/mcp"))
  // `providerTestkit % Test` is for the interruption checks, run against a server that never answers.
  .dependsOn(core, providerTestkit % Test)
  .settings(
    name := "llm4s-mcp",
    commonSettings,
    // Measured 70.79% statement coverage (`sbt coverage mcp/test mcp/coverageReport`) on the
    // code as carved out of core. Floor is the measured value rounded down to the nearest 5.
    // Never lower it.
    coverageFloor(75),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// `imagegeneration` and `imageprocessing` move together: they are two halves of one subsystem
// (generate an image, then analyse or convert it) and both are built on the same media
// vocabulary. Splitting them would leave two artifacts nobody uses apart.
//
// Both packages moved whole - nothing in core referenced them, and the one core test that did
// (`org.llm4s.async.AsyncErrorHandlingSpec`, which tests image clients exclusively despite its
// package) came with them rather than being left behind to fail.

// `speech` is the last package out of core, and the only one in slice 3 that takes a
// third-party dependency with it: Vosk, imported by exactly one file, `stt/VoskSpeechToText`.
//
// `Deps.jna` travels with it but is not a second dependency - Vosk's own POM already depends
// on `net.java.dev.jna:jna:5.7.0`, and this declaration exists to win that version conflict
// and pull 5.19.1 instead (5.7.0 predates Apple Silicon support). Keep them declared together:
// dropping the JNA line does not remove JNA, it silently downgrades it.
//
// What does NOT travel is the "Vosk Repository" resolver at alphacephei.com, which this
// removes outright. Vosk 0.3.45 publishes to Maven Central - the local Coursier cache has the
// jar under repo1.maven.org and not one artifact under alphacephei.com, whose only cache
// entries are failed `org/llm4s` lookups from sbt querying every resolver for every artifact.
// It was the build's only third-party resolver and it resolved nothing.

lazy val speech = (project in file("modules/speech"))
  // `providerTestkit % Test` is for `CloudSpeechCancellationSpec`: the testkit's interruption check, run
  // against a server that never answers. Test scope only.
  .dependsOn(core, providerTestkit % Test)
  .settings(
    name := "llm4s-speech",
    commonSettings,
    // Measured 80.68% statement coverage (`sbt coverage speech/test speech/coverageReport`) on
    // the code as carved out of core; 86.28% with the cloud providers (#1010) and their stubbed-HTTP
    // specs. Floor is the measured value rounded down to the nearest 5. Never lower it.
    coverageFloor(85),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      // Declared together on purpose - see the note above: JNA is a version pin, not an
      // addition, and removing it downgrades rather than removes.
      Deps.vosk,
      Deps.jna,
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

lazy val image = (project in file("modules/image"))
  // `observabilityPrometheus % Test` is for `ImageGenerationCostTrackingSpec`, which reads the
  // image metrics back out of a real `PrometheusMetrics` registry. Test scope only: the
  // published `llm4s-image` depends on the `MetricsCollector` contract, not on Prometheus.
  .dependsOn(media, core, observabilityPrometheus % Test, providerTestkit % Test)
  .settings(
    name := "llm4s-image",
    commonSettings,
    // Measured 75.60% statement coverage (`sbt coverage image/test image/coverageReport`) with
    // the Gemini vision client and its stub-server spec (66.82% as carved out of core). Floor is
    // the measured value rounded down to the nearest 5. Never lower it. The two `@Local`
    // vision suites in `modules/it` are not counted here.
    coverageFloor(75),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// ---- slice 5 of the modularisation programme (#1132) ----
// Provider clients leave core one module each, registered through the provider SPI that
// slice 4 built (#1131): a module lists its descriptors in an `Llm4sProviderModule` and
// declares it in META-INF/services, so depending on the artifact is what registers it.
//
// Ollama carves first - the smallest client, no vendor SDK, and a live `@Ollama` tier in
// `modules/it`, so the carve is checked against a real server rather than mocks (#1143).
// It takes its `llm4s.embeddings.ollama` reference.conf block with it; that block is keyed
// by provider id, which is what let it travel (slice 4 PR 5).
//
// Test depends on core's tests for `ModelRegistryTestSupport` and `MockMetricsCollector`,
// as `rag` does.

lazy val ollama = (project in file("modules/ollama"))
  .dependsOn(core % "compile->compile;test->test", providerTestkit % Test)
  .settings(
    name := "llm4s-ollama",
    commonSettings,
    mimaFrozen("llm4s-ollama"),
    // Measured 97.22% statement coverage (`sbt coverage ollama/test ollama/coverageReport`) after
    // the `format` (structured output) wiring; 77.44% when carved out of core. Floor is the measured value rounded down to the nearest
    // 5. Never lower it. The `@Ollama` suite in `modules/it` is not counted here.
    coverageFloor(95),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// Gemini carves second, and takes Vertex AI with it. `VertexAIClient` only calls Google's
// `publishers/google` (Gemini) models, in the same JSON format as `GeminiClient`; the two
// differ in endpoint (region/project-scoped aiplatform.googleapis.com) and auth (OAuth in
// `VertexAIAuthProvider`, hand-rolled, no Google SDK), not in dependencies. So bundling
// costs a Gemini-API user nothing, whereas splitting Vertex out later would be a breaking
// move for its users - bundling now is the safe direction. Deduplicating the two clients'
// shared JSON handling is a separate follow-up, not part of the carve.

lazy val gemini = (project in file("modules/gemini"))
  .dependsOn(core % "compile->compile;test->test", providerTestkit % Test)
  .settings(
    name := "llm4s-gemini",
    commonSettings,
    mimaFrozen("llm4s-gemini"),
    // Measured 87.53% statement coverage (`sbt coverage gemini/test gemini/coverageReport`) on
    // the code as carved out of core. Floor is the measured value rounded down to the nearest
    // 5. Never lower it. The `@Cloud` Gemini smoke suite in `modules/it` is not counted here.
    coverageFloor(85),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// Anthropic carves third, and takes the Anthropic Java SDK out of core with it: after this,
// nothing in `llm4s-core` imports `com.anthropic`. `AnthropicStreamingHandler` stays in core -
// it is an SDK-free SSE parser behind `StreamingResponseHandler.forProvider`, which
// `AnthropicClient` does not use (it streams through the SDK).

lazy val anthropic = (project in file("modules/anthropic"))
  .dependsOn(core % "compile->compile;test->test", providerTestkit % Test)
  .settings(
    name := "llm4s-anthropic",
    commonSettings,
    mimaFrozen("llm4s-anthropic"),
    // Measured 81.32% statement coverage (`sbt coverage anthropic/test anthropic/coverageReport`)
    // on the code as carved out of core. Floor is the measured value rounded down to the nearest
    // 5. Never lower it. The `@Cloud` Anthropic smoke suite in `modules/it` is not counted here.
    coverageFloor(80),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.anthropic,
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// `llm4s-openai-compatible` is a consolidation, not a pure move (#1132): DeepSeek, Z.ai and
// OpenRouter each had their own ~400-line copy of the same SDK-free chat-completions client, so
// they leave core as dialects over one `OpenAICompatibleClient`, which also serves the generic
// `openai-compatible` provider for any compatible endpoint. No dependency beyond core - keep it
// that way, so any user of an OpenAI-compatible endpoint can take it without an SDK.

lazy val openaiCompatible = (project in file("modules/openai-compatible"))
  .dependsOn(core % "compile->compile;test->test", providerTestkit % Test)
  .settings(
    name := "llm4s-openai-compatible",
    commonSettings,
    mimaFrozen("llm4s-openai-compatible"),
    // Measured 92.92% statement coverage (`sbt coverage openaiCompatible/test
    // openaiCompatible/coverageReport`) with Mistral and Cohere added as dialects; it was 92.68%
    // with the first three clients consolidated onto `OpenAICompatibleClient`, their suites moved
    // from core, and the generic provider's and dialects' own specs. Floor is the measured value
    // rounded down to the nearest 5. Never lower it. The `@Cloud` DeepSeek, OpenRouter and Cohere
    // smoke suites in `modules/it` are not counted here.
    coverageFloor(90),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// Community providers that are not OpenAI-compatible live under `modules/providers/<name>`, one
// published `llm4s-<name>` artifact each, on the same release train (#1132). Voyage is the first:
// an embedding provider only, carved as-is from core with its config keys, `reference.conf`
// block and model dimensions. No dependency beyond core.

lazy val voyage = (project in file("modules/providers/voyage"))
  .dependsOn(core % "compile->compile;test->test", providerTestkit % Test)
  .settings(
    name := "llm4s-voyage",
    commonSettings,
    // Measured 89.69% statement coverage (`sbt coverage voyage/test voyage/coverageReport`)
    // on the code as carved out of core. Floor is the measured value rounded down to the nearest
    // 5. Never lower it.
    coverageFloor(85),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// AWS Bedrock chat (#1008): rebuilt from #1029 as a `ProviderDescriptor` over the Bedrock Converse
// and ConverseStream APIs. It takes the AWS SDK v2 `bedrockruntime` artifact (Apache-2.0, the same
// SDK release train as `llm4s-rag`'s S3 client) and nothing else; core gains no dependency.

lazy val bedrock = (project in file("modules/providers/bedrock"))
  .dependsOn(core % "compile->compile;test->test", providerTestkit % Test)
  .settings(
    name := "llm4s-bedrock",
    commonSettings,
    // Measured 96.39% statement coverage (`sbt coverage bedrock/test bedrock/coverageReport`).
    // Floor is the measured value rounded down to the nearest 5. Never lower it. The `@Cloud`
    // Bedrock smoke suite in `modules/it` is not counted here.
    coverageFloor(95),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.awsBedrockRuntime,
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// Jina AI embeddings (#1028): an embedding provider only, rebuilt from #1060 as an
// `EmbeddingProviderDescriptor` with a typed `JinaTask` setting. No dependency beyond core.

lazy val jina = (project in file("modules/providers/jina"))
  .dependsOn(core % "compile->compile;test->test", providerTestkit % Test)
  .settings(
    name := "llm4s-jina",
    commonSettings,
    // Measured 100.00% statement coverage (`sbt coverage jina/test jina/coverageReport`). Floor is
    // the measured value rounded down to the nearest 5. Never lower it.
    coverageFloor(100),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// Cohere embeddings: an embedding provider only, as an `EmbeddingProviderDescriptor` with a typed
// `CohereInputType` setting. Cohere's native `/v2/embed` is not OpenAI-compatible (`texts`,
// `input_type`, `embedding_types`, vectors keyed by type), so it is a module of its own and not a
// dialect in `llm4s-openai-compatible`, where Cohere chat lives. No dependency beyond core.

lazy val cohere = (project in file("modules/providers/cohere"))
  .dependsOn(core % "compile->compile;test->test", providerTestkit % Test)
  .settings(
    name := "llm4s-cohere",
    commonSettings,
    // Measured 100.00% statement coverage (`sbt coverage cohere/test cohere/coverageReport`). Floor is
    // the measured value rounded down to the nearest 5. Never lower it. The `@Cloud`
    // `CohereEmbeddingsSmokeSpec` in `modules/it` is not counted here.
    coverageFloor(100),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// `llm4s-watsonx` (#1019): IBM watsonx.ai. Not OpenAI-compatible - its text-generation API takes
// a flattened `input` string and authenticates by exchanging an IBM Cloud API key for an IAM
// bearer token - so it is a provider module of its own rather than a dialect in
// `openai-compatible`. No dependency beyond core.

lazy val watsonx = (project in file("modules/providers/watsonx"))
  .dependsOn(core % "compile->compile;test->test", providerTestkit % Test)
  .settings(
    name := "llm4s-watsonx",
    commonSettings,
    // Measured 98.45% statement coverage (`sbt coverage watsonx/test watsonx/coverageReport`).
    // Floor is the measured value rounded down to the nearest 5. Never lower it. There is no
    // live suite in `modules/it`: watsonx.ai needs an IBM Cloud account (#1020).
    coverageFloor(95),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
    )
  )

// `llm4s-provider-testkit` (#1133) is what a provider module's `Llm4s<Name>ModuleSpec` is written
// with: discovery, sole ownership, explicit registration, the config-to-client round trip and
// the `reference.conf` credential binding, as assertions, plus config loading from a HOCON
// string and an injected environment and a local stub HTTP server. It is published so that a
// provider module outside this repository can prove itself the way the in-repo ones do; those
// helpers used to live in core's test sources, which are not published. A test library, so
// ScalaTest is a compile dependency. Every in-repo provider module dogfoods it (`% Test`).
//
// It needs JDK 21: the interruption checks run on `Thread.ofVirtual` and the local server on
// `Executors.newVirtualThreadPerTaskExecutor` (#1582). The docs say so; no module sets a
// `-release` or `javacOptions` target, and where the floor is enforced is for #1493 to decide.
//
// Core's own tests cannot use it - that would be a project cycle - so anything core's tests
// share with it lives in core's main sources, `private[llm4s]` (`config.ReferenceConfig`).
lazy val providerTestkit = (project in file("modules/provider-testkit"))
  .dependsOn(core)
  .settings(
    name := "llm4s-provider-testkit",
    commonSettings,
    // Measured 92.10% statement coverage (`sbt coverage providerTestkit/test
    // providerTestkit/coverageReport`). Floor is the measured value rounded down to the nearest
    // 5. Never lower it.
    coverageFloor(90),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.scalatest,
      Deps.ujson
    )
  )

// The OpenAI family carves fourth, split by shared client: OpenAI, Azure and Requesty all run
// on `OpenAIClient`, so they move together and took the SDK out of core - after this core has
// no vendor SDK at all. That SDK was Microsoft's `azure-ai-openai`, since deprecated; the
// client now runs on OpenAI's `openai-java`, which covers Azure too (#1132). OpenRouter,
// DeepSeek and Z.ai speak the same wire format without an SDK, so they are
// `llm4s-openai-compatible` above, which also holds `OpenAIConfig` (OpenRouter builds one).
// `openai` depends on that module for the config; it adds no SDK. The reverse edge must never
// exist - it would put `openai-java` (with OkHttp, Jackson and kotlin-stdlib) on the classpath
// of every OpenAI-compatible user.

lazy val openai = (project in file("modules/openai"))
  .dependsOn(core % "compile->compile;test->test", openaiCompatible, providerTestkit % Test)
  .settings(
    name := "llm4s-openai",
    commonSettings,
    mimaFrozen("llm4s-openai"),
    // Measured 80.42% statement coverage (`sbt coverage openai/test openai/coverageReport`)
    // after the move to `openai-java` (#1132), up from 62.34% at the carve and 73.72% at the
    // switch: `OpenAIClient` is at 85% with the streamed tool-call specs and
    // `OpenAIClientWireSpec`, which drives the real SDK transport against a local server. Floor is
    // 75, not 80, because 80.42% leaves under half a point of headroom. Never lower it.
    // `OpenAIEmbeddingProvider`'s anonymous HTTP client (12%) is still the thin spot. The `@Cloud`
    // OpenAI smoke suite in `modules/it` is not counted here.
    coverageFloor(75),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.openaiJava,
      Deps.ujson,
      Deps.scalatest % Test,
      Deps.scalamock % Test
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
  // `agent` is for `codegen.CodeWorker` and `CodeGenExample`, which drive an `Agent` (#1242).
  .dependsOn(workspaceShared, core, agent)
  .settings(
    name := "llm4s-workspace-client",
    commonSettings,
    Compile / discoveredMainClasses := Seq.empty,
    // Not measured: excluded via ThisBuild / coverageExcludedPackages (org.llm4s.workspace.*)
    // and exercised only by containerised integration tests.
    coverageDisabled,
    // The Anthropic and Azure OpenAI SDKs used to be declared here too - a stale copy of
    // core's list. Nothing in this module imports com.anthropic or com.azure; they were
    // removed with the `anthropic` carve (#1132) so the Anthropic SDK leaves this module's
    // published POM as well as core's. Since the `openai` carve, Azure no longer reaches it
    // transitively through core either. jtokkit went the same way in the slice 5 hygiene
    // follow-up: nothing here imports com.knuddels.jtokkit (token counting is core's
    // `org.llm4s.context.tokens`, which brings jtokkit with it through `dependsOn(core)`).
    libraryDependencies ++= Seq(
      Deps.websocket,
      Deps.scalatest % Test,
      Deps.scalamock % Test,
      Deps.ujson,
      // Kept deliberately: pureconfig (from commonSettings) is a facade over Typesafe Config
      // and needs it at runtime, but no source file here imports com.typesafe.config, so an
      // import-based audit reads it as dead. Tika/POI/PDFBox/jsoup/Postgres/HikariCP/commons-io
      // were removed alongside this line - none of them was on any code path in this module.
      Deps.config
    )
  )

lazy val workspaceRunner = (project in file("modules/workspace/workspaceRunner"))
  .dependsOn(workspaceShared)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "llm4s-workspace-runner",
    commonSettings,
    Compile / mainClass := Some("org.llm4s.runner.RunnerMain"),
    // The Postgres driver and HikariCP used to be declared here as well. The runner has no
    // database code - nothing imports java.sql, org.postgresql or com.zaxxer - so they only
    // added a JDBC driver and a connection pool to the workspace-runner Docker image. Removed
    // in the slice 5 hygiene follow-up (#1132).
    libraryDependencies ++= Seq(
      Deps.cask,
      Deps.config
    ),
    appLogging,
    publish / skip := true,
    // Not measured: Docker entry point, excluded via ThisBuild / coverageExcludedPackages
    // (org.llm4s.runner.*) and exercised only by containerised integration tests.
    coverageDisabled
  )
  .settings(WorkspaceRunnerDocker.settings)

// A small HTTP service - GET /health and GET /llm-check - for the staged-deployment workflow template
// (.github/workflows/deploy-staged.yml) and the Kustomize manifests in deploy/ (#846). It is its own
// module, not part of `samples`, so cask and a pinned main class do not land on the examples' classpath.
// Unpublished: it is what a downstream project copies, so it depends on the library as a user's service
// would, and builds its image with the Docker plugin like `workspaceRunner` does.
lazy val deployService = (project in file("modules/deploy-service"))
  .dependsOn(
    core % "compile->compile;test->test",
    ollama,
    gemini,
    anthropic,
    openai,
    openaiCompatible
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "llm4s-deploy-service",
    commonSettings,
    Compile / mainClass := Some("org.llm4s.deploy.DeployServiceMain"),
    // `cask.Main.main` starts the server on a background thread and returns, so an unforked `run`
    // finishes at once and sbt exits (in batch mode) with the server still starting. Forked, sbt waits
    // for the service's own JVM, which the server threads keep alive.
    run / fork := true,
    libraryDependencies ++= Seq(
      Deps.cask,
      Deps.ujson,
      Deps.scalatest % Test
    ),
    appLogging,
    publish / skip := true,
    // Measured 92.91% statement coverage (`sbt coverage deployService/test deployService/coverageReport`),
    // by unit tests plus the real routes on a real server on an ephemeral port. The entry point
    // (`DeployServiceMain`) is excluded via ThisBuild / coverageExcludedPackages. Floor is the measured
    // value rounded down to the nearest 5. Never lower it.
    coverageFloor(90)
  )
  .settings(DeployServiceDocker.settings)

lazy val docSnippetsReport = taskKey[Unit](
  "List every Scala block of the documentation pages that are compile-checked, with its hash and whether it is skipped"
)

lazy val samples = (project in file("modules//samples"))
  .dependsOn(
    core,
    rag,
    knowledgegraph,
    memory,
    memoryPostgres,
    mcp,
    image,
    speech,
    ollama,
    gemini,
    anthropic,
    openai,
    openaiCompatible,
    voyage,
    bedrock,
    jina,
    cohere,
    watsonx,
    knowledgegraphNeo4j,
    observability,
    observabilityPrometheus,
    agent,
    agentTools,
    llm4sEffect,
    llm4sZio
  )
  .settings(
    name := "llm4s-samples",
    commonSettings,
    publish / skip := true,
    // Not measured: unpublished example code, excluded via ThisBuild / coverageExcludedPackages
    // (org.llm4s.samples.*). Samples are compile-checked, not covered.
    coverageDisabled,
    libraryDependencies += Deps.termflow,
    // Test-only: `JsonLibrariesGuideSpec` runs the recipes of docs/guide/json-libraries.md against the real libraries.
    // Samples are unpublished, so none of these reaches a user's classpath or a frozen module.
    libraryDependencies ++= Seq(
      Deps.circeCore  % Test,
      Deps.ujsonCirce % Test,
      Deps.playJson   % Test,
      Deps.zioJson    % Test
    ),
    appLogging,
    // The Scala blocks of the getting-started pages are compiled as test sources, so a snippet that no longer
    // compiles fails `sbt test` (#1477). The generator and its rules are in project/DocSnippets.scala; the blocks
    // that are deliberately not compiled are listed in src/test/docs-snippets/skip.txt.
    Test / sourceGenerators += Def.task {
      DocSnippets.generate(
        (ThisBuild / baseDirectory).value / "docs",
        baseDirectory.value / "src" / "test" / "docs-snippets" / "skip.txt",
        (Test / sourceManaged).value / "docsnippets",
        streams.value.log
      )
    }.taskValue,
    // Warnings (unused imports and values, in a snippet written to be read) are not errors in generated sources.
    Test / scalacOptions += "-Wconf:src=.*docsnippets.*:s",
    docSnippetsReport := println(
      DocSnippets.report(
        (ThisBuild / baseDirectory).value / "docs",
        baseDirectory.value / "src" / "test" / "docs-snippets" / "skip.txt"
      )
    )
  )

lazy val configPolicy = (project in file("modules/config-policy"))
  // Every carved provider module, at runtime: `CheckPolicies` loads real provider config
  // through the registry, so a config naming a provider whose module is absent fails as "not
  // registered" before any policy runs. It must accept whatever a user's config names, not
  // just what CI's smoke config (ollama) happens to exercise. A provider carve adds itself
  // here; `CheckPoliciesProvidersSpec` checks each one resolves.
  .dependsOn(core, ollama, gemini, anthropic, openai, openaiCompatible, bedrock, watsonx)
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
    run / fork          := true,
    Test / fork         := true,
    run / baseDirectory := (LocalRootProject / baseDirectory).value,
    // Both engines compile here; Deps.config is also available transitively via core.
    libraryDependencies += Deps.config,
    appLogging,
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

// ---- slice 6 of the modularisation programme (#1133) ----
// `llm4s-observability` carries the tracing integrations that need nothing beyond core:
// Langfuse (registered as a `TracingBackend` through its own services entry), the trace
// collector with its model and store, and `CostTracker`. No third-party dependency, which is
// what lets `rag` depend on it for `RAGASLangfuseObserver` without passing anything on.
//
// What stays in core is the contract (D1 to D5): `Tracing`, `TraceEvent`, `TracingComposer`,
// `TracingMode` (`Console`, `NoOp`, `Named`), the `TracingBackend` SPI, `NoOpTracing`,
// `ConsoleTracing`, `TracingSettings`, and `MetricsCollector`. The `llm4s.tracing.langfuse`
// block moved with its reader into this module's `reference.conf`; core reads only
// `llm4s.tracing.mode` and hands the selected mode's block to its backend as `extras`.
//
// Test depends on core's tests for `MockHttpClient`/`FailingHttpClient` and `ReferenceConfig`.

lazy val observability = (project in file("modules/observability"))
  // `agent % Test` is for the Langfuse specs that trace a real `Agent` run. Test scope only:
  // `CostTracker` builds a `UsageSummary`, which stayed in core for this reason (D1, #1242).
  .dependsOn(core % "compile->compile;test->test", agent % Test)
  .settings(
    name := "llm4s-observability",
    commonSettings,
    // Measured 94.52% statement coverage (`sbt coverage observability/test
    // observability/coverageReport`) on the code as carved out of core. Floor is the measured
    // value rounded down to the nearest 5. Never lower it.
    coverageFloor(90),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty
  )

// `llm4s-observability-prometheus` is the one integration that brings a third-party dependency
// of its own - the Prometheus client and its HTTP server - which is why it is not part of
// `llm4s-observability` (D4): `rag` depends on that module, and would otherwise pass Prometheus
// on to every RAG user. It holds `PrometheusMetrics`, `PrometheusEndpoint` and
// `MetricsConfigLoader`, which replaces `Llm4sConfig.metrics()` (D3), and the `llm4s.metrics`
// block whose defaults used to be hard-coded in that loader. With it gone, core declares no
// observability dependency; `MetricsCollector` stays there as the contract.
//
// Test depends on core's tests for `ReferenceConfig`, which proves the `llm4s.metrics` block.
lazy val observabilityPrometheus = (project in file("modules/observability-prometheus"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "llm4s-observability-prometheus",
    commonSettings,
    // Measured 70.56% statement coverage (`sbt coverage observabilityPrometheus/test
    // observabilityPrometheus/coverageReport`) on the code as carved out of core. Floor is the
    // measured value rounded down to the nearest 5. Never lower it.
    coverageFloor(70),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.prometheusCore,
      Deps.prometheusHttp
    )
  )

lazy val traceOpentelemetry = (project in file("modules/trace-opentelemetry"))
  // Test depends on core's tests for `ReferenceConfig`, which proves this module's
  // `reference.conf` binds `OTEL_*` under `llm4s.tracing.opentelemetry` (#1133).
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "llm4s-observability-otel",
    commonSettings,
    // Measured 34.15% statement coverage (`sbt coverage traceOpentelemetry/test
    // traceOpentelemetry/coverageReport`) from its in-module suites: OpenTelemetryTracingBackendSpec
    // (#1133), which covers the backend's registration and startup, and
    // OpenTelemetryTracingConfigSpec, which came with `OpenTelemetryConfig` and the
    // `llm4s.tracing.opentelemetry` block in the `observability` carve (29.20% before it). Span
    // export is exercised by modules/it/.../OpenTelemetryTracingSpec, which this number does not
    // include. Floor is the measured value rounded down to the nearest 5.
    coverageFloor(30),
    libraryDependencies ++= Seq(
      Deps.opentelemetryApi,
      Deps.opentelemetrySdk,
      Deps.opentelemetryExporterOtlp
    )
  )

// ---- slice 7 of the modularisation programme (#1242) ----
// `llm4s-agent` is the agent runtime: `org.llm4s.agent` (the `Agent`, guardrails, handoffs, the
// typed graph runtime and streaming events) and `org.llm4s.assistant` (the console
// assistant, tiered Beta, and the only user of fansi). `agent.memory` was already carved into
// `llm4s-memory`, which does not depend on this module. Package names are unchanged.
//
// Nothing in core imports either package, and neither reads config, so the carve is a move.
// `UsageSummary` and `ModelUsage` went down to `llmconnect.model` first (D1, #1243) so that
// `llm4s-observability` need not depend on the agent runtime. Core keeps the contracts the
// agent is built on: `LLMClient`, `ToolRegistry`, `Tracing` and `TraceEvent`.
//
// Test depends on core's tests for `MockLLMClient`, `StubLLMClient` and the shared fixtures.
lazy val agent = (project in file("modules/agent"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "llm4s-agent",
    commonSettings,
    mimaFrozen("llm4s-agent"),
    // Measured 80.80% statement coverage (`sbt coverage agent/test agent/coverageReport`) on the
    // code as carved out of core. Floor is the measured value rounded down to the nearest 5.
    // Never lower it.
    coverageFloor(80),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.ujson,
      Deps.fansi,
      Deps.ox,
      Deps.scalamock % Test
    )
  )

// `llm4s-agent-tools` carries the ready-made tools: `org.llm4s.toolapi.builtin` (core utilities,
// filesystem, HTTP, shell, and the Brave, DuckDuckGo and Exa search clients) and the demo
// `toolapi.tools.WeatherTool`. They are integrations with third-party APIs, so they leave the
// frozen spine and version on their own train; core keeps the tool API they implement
// (`ToolFunction`, `ToolRegistry`, schemas, execution). Package names are unchanged.
//
// It depends on core only, not on the agent runtime (D2): the tools import nothing from
// `org.llm4s.agent`, and they serve plain tool calling through `ToolRegistry` as well. The search
// tools' config left core with them (D3): `ToolsConfigLoader` (now public; its no-argument loaders
// replace the removed `Llm4sConfig.load*SearchTool()` methods), the three `*SearchToolConfig`
// types, `ToolsConfigKeys`, and the `llm4s.tools` block of `reference.conf`.
//
// Test depends on core's tests for `ReferenceConfig`, which proves the `llm4s.tools` block.
lazy val agentTools = (project in file("modules/agent-tools"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "llm4s-agent-tools",
    commonSettings,
    // Measured 66.18% statement coverage (`sbt coverage agentTools/test agentTools/coverageReport`)
    // on the code as carved out of core. Floor is the measured value rounded down to the nearest 5.
    // Never lower it.
    coverageFloor(65),
    Test / fork                     := true,
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= Seq(
      Deps.ujson,
      Deps.scalamock % Test
    )
  )

lazy val knowledgegraphNeo4j = (project in file("modules/knowledgegraph-neo4j"))
  .dependsOn(core, knowledgegraph)
  .settings(
    name := "llm4s-knowledgegraph-neo4j",
    commonSettings,
    Test / fork := true,
    libraryDependencies ++= Seq(
      Deps.neo4jDriver,
      Deps.scalatest % Test
    ),
    // Enforce >=80% statement coverage when running with `sbt coverage test`
    // for the unit-test suite that ships with this module. Pre-existing gate, unchanged.
    coverageFloor(80)
  )

// Two OkHttp majors share this classpath, and it is left that way on purpose (#1132).
// `anthropic-java` and `openai-java` bring `com.squareup.okhttp3:okhttp:4.12.0`; OpenTelemetry's
// exporter brings `com.squareup.okhttp3:okhttp-jvm:5.x`. The artifact names differ, so nothing
// evicts either, but both jars hold package `okhttp3` (253 identical class names): the first
// jar on the classpath - 4.12.0 today - supplies the shared classes, and 5.x supplies only the
// classes 4.x lacks. The Anthropic and OpenAI clients (complete and stream) and the OTLP gRPC
// and HTTP exporters were all checked against local servers in this JVM, on this mixed
// classpath and with 4.x excluded; both work. Excluding 4.x here would test the SDKs against
// an OkHttp that `llm4s-anthropic` / `llm4s-openai` users do not get, and would hide the mix
// that users combining a provider module with `llm4s-observability-otel` do get. Note that
// the plain `okhttp:5.x` artifact is an empty stub for Maven/sbt consumers (the classes are in
// `okhttp-jvm`), so a dependencyOverrides bump of `okhttp` to 5.x would drop the classes.
// `MixedOkHttpClasspathSpec` (Local tier, so every `sbt test`) re-runs those checks here.
lazy val it = (project in file("modules/it"))
  .dependsOn(
    core,
    rag,
    knowledgegraph,
    memory,
    memoryPostgres,
    mcp,
    image,
    speech,
    ollama,
    gemini,
    anthropic,
    openai,
    openaiCompatible,
    voyage,
    bedrock,
    jina,
    cohere,
    watsonx,
    knowledgegraphNeo4j,
    workspaceClient,
    observability,
    observabilityPrometheus,
    traceOpentelemetry,
    agent
  )
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

// ---- unified Scaladoc across the published modules ----
// The docs site publishes ONE API tree at /scaladoc/org/llm4s/..., and `pages.yml` used to
// build it from `core/doc` alone. That was right when core was the whole library; each carve
// slice (#1126) moves public API out of it, so by slice 3 the published Scaladoc had silently
// lost `rag`, `knowledgegraph`, `memory` and `memory-postgres` - the pages were simply not
// generated, which reads as "this API does not exist" rather than as a failure.
//
// This project owns no sources of its own: it borrows every published module's `Compile /
// sources` and depends on them for the classpath, so `docs/doc` is one Scaladoc run across
// the whole public API, with one index and one working search. It is a hand-rolled unidoc
// because sbt-unidoc 0.5.0 still invokes `dotty.tools.dottydoc.Main`, which does not exist in
// Scala 3.7 - it fails with ClassNotFoundException before generating anything.
//
// A module is listed here if and only if it is published. When a slice adds one, add it in
// the same commit, or its API silently vanishes from the site.
lazy val docs = (project in file("modules/docs"))
  .dependsOn(
    media,
    core,
    rag,
    knowledgegraph,
    memory,
    memoryPostgres,
    mcp,
    image,
    speech,
    ollama,
    gemini,
    anthropic,
    openai,
    openaiCompatible,
    voyage,
    bedrock,
    jina,
    cohere,
    watsonx,
    providerTestkit,
    workspaceShared,
    workspaceClient,
    observability,
    observabilityPrometheus,
    traceOpentelemetry,
    agent,
    agentTools,
    knowledgegraphNeo4j,
    llm4sEffect,
    llm4sZio,
    javaApi,
    springBootStarter
  )
  .settings(
    name := "llm4s-docs",
    commonSettings,
    publish / skip := true,
    // Provided-scope in spring-boot-starter, so not on the classpath it exports.
    libraryDependencies += Deps.springBootActuator,
    // Not measured: no sources of its own - it exists only to host the aggregate `doc` task.
    coverageDisabled,
    Compile / sources := {
      (media / Compile / sources).value ++
        (core / Compile / sources).value ++
        (rag / Compile / sources).value ++
        (knowledgegraph / Compile / sources).value ++
        (memory / Compile / sources).value ++
        (memoryPostgres / Compile / sources).value ++
        (mcp / Compile / sources).value ++
        (image / Compile / sources).value ++
        (speech / Compile / sources).value ++
        (ollama / Compile / sources).value ++
        (gemini / Compile / sources).value ++
        (anthropic / Compile / sources).value ++
        (openai / Compile / sources).value ++
        (openaiCompatible / Compile / sources).value ++
        (voyage / Compile / sources).value ++
        (bedrock / Compile / sources).value ++
        (jina / Compile / sources).value ++
        (cohere / Compile / sources).value ++
        (watsonx / Compile / sources).value ++
        (providerTestkit / Compile / sources).value ++
        (workspaceShared / Compile / sources).value ++
        (workspaceClient / Compile / sources).value ++
        (observability / Compile / sources).value ++
        (observabilityPrometheus / Compile / sources).value ++
        (traceOpentelemetry / Compile / sources).value ++
        (agent / Compile / sources).value ++
        (agentTools / Compile / sources).value ++
        (knowledgegraphNeo4j / Compile / sources).value ++
        (llm4sEffect / Compile / sources).value ++
        (llm4sZio / Compile / sources).value ++
        (javaApi / Compile / sources).value ++
        (springBootStarter / Compile / sources).value
    },
    Compile / mainClass             := None,
    Compile / discoveredMainClasses := Seq.empty
  )

lazy val javaApi = (project in file("modules/java-api"))
  .dependsOn(core % "compile->compile;test->test", agent, openai, anthropic, ollama, gemini, openaiCompatible)
  .settings(
    name := "llm4s-java-api",
    commonSettings,
    // Measured 100.00% statement coverage (with the integration spec) (`sbt coverage javaApi/test javaApi/coverageReport`);
    // floor is the measured value rounded down to the nearest 5.
    coverageFloor(100),
    libraryDependencies ++= Seq(
      Deps.scalatest % Test
    )
  )

lazy val springBootStarter = (project in file("modules/spring-boot-starter"))
  .dependsOn(javaApi)
  .settings(
    name := "llm4s-spring-boot-starter",
    commonSettings,
    // Measured 100.00% statement coverage (`sbt coverage springBootStarter/test
    // springBootStarter/coverageReport`); floor is the measured value rounded down to the
    // nearest 5.
    coverageFloor(100),
    libraryDependencies ++= Seq(
      Deps.springBootAutoConfigure,
      Deps.springBootActuator          % Provided,
      Deps.scalatest                   % Test,
      Deps.springBootStarterTest       % Test,
      Deps.springBootTestAutoConfigure % Test,
      Deps.springBootActuator          % Test
    )
  )

lazy val benchmarks = (project in file("modules/benchmarks"))
  .dependsOn(core, rag)
  .enablePlugins(JmhPlugin)
  .settings(
    name := "llm4s-benchmarks",
    commonSettings,
    publish / skip := true,
    // Measured 100.00% statement/branch coverage (`sbt coverage benchmarks/test
    // benchmarks/coverageReport`): BenchmarkSmokeTest deliberately instantiates and runs
    // every JMH benchmark. Floor is the measured value rounded down to the nearest 5, i.e.
    // 100, which encodes exactly that policy - a new benchmark must be added to the smoke
    // test. (Codecov ignores modules/benchmarks; this is a build-side gate only.)
    coverageFloor(100),
    appLogging,
    libraryDependencies ++= Seq(
      Deps.scalatest % Test
    )
  )

lazy val gradleDemo = (project in file("modules/gradle-demo"))
  .dependsOn(core)
  .settings(
    name := "gradle-demo",
    commonSettings,
    publish / skip := true,
    libraryDependencies ++= Seq(
      Deps.scalatest % Test
    ),
    // Measured 100.00% statement coverage (`sbt coverage gradleDemo/test
    // gradleDemo/coverageReport`); floor is the measured value rounded down to the nearest 5.
    coverageFloor(100)
  )

// ---- relocation stubs for the 0.4.0 artifact rename ----
// Each project below publishes ONLY a POM at the retired coordinate, carrying a Maven
// `<relocation>` that points at its replacement. They deliberately carry no sources, no
// `commonSettings` and no Scala library, so they compile nothing (and have no MiMa baseline); see project/Relocation.scala
// for what a relocation POM does and does not achieve.
//
// Only coordinates with real published history on Maven Central get a stub - publishing a
// relocation for something that never existed would be noise. Verified present under
// https://repo1.maven.org/maven2/org/llm4s/ : core_3, workspaceclient_3, workspaceshared_3,
// trace-opentelemetry_3, knowledgegraph-neo4j_3 (all through 0.3.4).
//
// `workspacerunner`, `samples` and the other unpublished modules have `publish / skip` and
// no Central history, so they need no stub.

// The bill of materials: a POM that pins every published artifact to one version. Generated, not listed by
// hand: see project/Bom.scala. Its dependencyManagement block is written when the POM is made, because the
// list comes from tasks (`publish / skip` of every project) that `pomPostProcess` cannot read.
lazy val bom = (project in file("modules/bom"))
  .settings(Bom.settings)
  .settings(
    mimaFailOnNoPrevious := false,
    makePom := {
      val pom      = makePom.value
      val projects = Def.task((name.value, (publish / skip).value)).all(ScopeFilter(inAnyProject)).value
      Bom.writeManagedDependencies(
        pom,
        PublishedArtifacts.managed(projects, (ThisBuild / scalaBinaryVersion).value),
        version.value
      )
    }
  )

lazy val relocationCore = (project in file("modules/relocations/core"))
  .settings(Relocation.settings("core", "llm4s-core_3"), mimaFailOnNoPrevious := false)

lazy val relocationWorkspaceClient = (project in file("modules/relocations/workspaceclient"))
  .settings(Relocation.settings("workspaceclient", "llm4s-workspace-client_3"), mimaFailOnNoPrevious := false)

lazy val relocationWorkspaceShared = (project in file("modules/relocations/workspaceshared"))
  .settings(Relocation.settings("workspaceshared", "llm4s-workspace-shared_3"), mimaFailOnNoPrevious := false)

lazy val relocationTraceOpentelemetry = (project in file("modules/relocations/trace-opentelemetry"))
  .settings(Relocation.settings("trace-opentelemetry", "llm4s-observability-otel_3"), mimaFailOnNoPrevious := false)

lazy val relocationKnowledgegraphNeo4j = (project in file("modules/relocations/knowledgegraph-neo4j"))
  .settings(Relocation.settings("knowledgegraph-neo4j", "llm4s-knowledgegraph-neo4j_3"), mimaFailOnNoPrevious := false)
