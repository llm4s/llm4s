import com.typesafe.sbt.packager.docker.Cmd
import sbt.Keys.{libraryDependencies, *}
import scoverage.ScoverageKeys._


// Define supported Scala versions
val scala213 = "2.13.16"
val scala3   = "3.7.1"
val scala3CompilerOptions = Seq(
  "-explain",
  "-explain-types",
  "-Wunused:nowarn",
  "-feature",
  "-unchecked",
  "-source:3.3",
  "-Wsafe-init",
  "-deprecation",
  "-Wunused:all",
  "-Werror"
)
val scala2CompilerOptions = Seq(
  "-Xfatal-warnings",
  "-feature",
  "-unchecked",
  "-deprecation",
  "-Wunused:nowarn",
  "-Wunused:imports",
  "-Wunused:locals",
  "-Wunused:patvars",
  "-Wunused:params",
  "-Wunused:linted"
)

inThisBuild(
  List(
    crossScalaVersions := List(scala213, scala3),
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
          // Strip the 'v' prefix if present
          out.ref.value.stripPrefix("v")
        case Some(out) =>
          // Strip the 'v' prefix from snapshot versions too
          val baseVersion = out.ref.value.stripPrefix("v")
          s"$baseVersion+${out.commitSuffix.mkString("", "", "")}-SNAPSHOT"
        case None =>
          "0.0.0-UNKNOWN"
      }
    },

    // ---- Test coverage (scoverage) ----
    // Global defaults; tweak locally with: set coverageMinimumStmtTotal := 85
    ThisBuild / coverageMinimumStmtTotal := 80,
    ThisBuild / coverageFailOnMinimum    := false,
    ThisBuild / coverageHighlighting     := true,
    // Exclude non-library entry points and samples from coverage stats
    ThisBuild / coverageExcludedPackages :=
      """
        |org\.llm4s\.runner\..*
        |org\.llm4s\.samples\..*
      """.stripMargin.replaceAll("\n", ";")
    ,
    // Generate HTML only at the current project (root when using aliases)
    ThisBuild / (coverageReport / aggregate) := false,

    // ---- Scalafix / SemanticDB ----
    // Do not enable on-compile globally; we enable per-project where applicable
    // Provide built-in rule implementations (e.g., Disable)
    ThisBuild / scalafixDependencies += "ch.epfl.scala" %% "scalafix-rules" % "0.12.1"
  )
)

// Handy aliases for coverage runs
addCommandAlias(
  "cov",
  ";clean;coverage;test;coverageAggregate;coverageReport;coverageOff"
)
addCommandAlias(
  "covReport",
  ";clean;coverage;test;coverageReport;coverageOff"
)

def scalacOptionsForVersion(scalaVersion: String): Seq[String] =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, 13)) =>
      scala2CompilerOptions
    case Some((3, _)) =>
      scala3CompilerOptions
    case _ => Seq.empty
  }

lazy val commonSettings = Seq(
  Compile / scalacOptions := scalacOptionsForVersion(scalaVersion.value),
  Test / scalacOptions    := scalacOptionsForVersion(scalaVersion.value),
  // Enable SemanticDB only where supported; Disable rules do not require it.
  semanticdbEnabled := (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _))  => true
    case _             => false
  }),
  Compile / packageDoc / publishArtifact := !isSnapshot.value,
  Compile / unmanagedSourceDirectories ++= {
    val sourceDir = (Compile / sourceDirectory).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 13)) => Seq(sourceDir / "scala-2.13")
      case Some((3, _))  => Seq(sourceDir / "scala-3")
      case _             => Nil
    }
  },
  Test / unmanagedSourceDirectories ++= {
    val sourceDir = (Test / sourceDirectory).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 13)) => Seq(sourceDir / "scala-2.13")
      case Some((3, _))  => Seq(sourceDir / "scala-3")
      case _             => Nil
    }
  },
  libraryDependencies ++= List(
    "org.typelevel" %% "cats-core"       % "2.13.0",
    "com.lihaoyi"   %% "upickle"         % "4.2.1",
    "ch.qos.logback" % "logback-classic" % "1.5.18",
    "dev.optics" %% "monocle-core"  % "3.3.0",
    "dev.optics" %% "monocle-macro" % "3.3.0",
    "org.scalatest" %% "scalatest"       % "3.2.19" % Test,
    "org.scalamock" %% "scalamock" %      "7.4.2" % Test,
    "com.lihaoyi"   %% "fansi"           % "0.5.0",
    "org.postgresql" % "postgresql" % "42.7.3",   // JDBC driver
    "com.typesafe"   % "config"      % "1.4.3"    // loads reference.conf
  )
)

lazy val root = (project in file("."))
  .aggregate(shared, workspaceRunner)
  .dependsOn(shared)
  .settings(
    name := "llm4s",
    commonSettings,
    // Enable scalafix on compile here (has ConfigReader)
    scalafixOnCompile := true,
    // Ensure consistent enhanced messages in root tests
    Test / fork := true,
    // Library project: do not expose or auto-discover mains
    Compile / mainClass := None,
    Compile / discoveredMainClasses := Seq.empty,
    resolvers += "Vosk Repository" at "https://alphacephei.com/maven/",
    libraryDependencies ++= List(
      "com.azure"          % "azure-ai-openai" % "1.0.0-beta.16",
      "com.anthropic"      % "anthropic-java"  % "2.2.0",
      "com.knuddels"       % "jtokkit"         % "1.1.0",
      "com.lihaoyi"       %% "requests"        % "0.9.0",
      "org.java-websocket" % "Java-WebSocket"  % "1.6.0",
      "org.scalatest"     %% "scalatest"       % "3.2.19" % Test,
      "org.scalamock"     %% "scalamock"       % "7.4.0"  % Test,
      "com.softwaremill.sttp.client4" %% "core"  % "4.0.9",
      "com.lihaoyi"                   %% "ujson" % "4.2.1",
      "org.apache.pdfbox" % "pdfbox" % "3.0.5",
      "commons-io"        % "commons-io"      % "2.18.0",
      "org.apache.tika" % "tika-core" % "3.2.1",
      "org.apache.poi" % "poi-ooxml" % "5.4.1",
      "com.lihaoyi" %% "requests" % "0.9.0",
      "org.jsoup" % "jsoup" % "1.21.1",
      "io.github.cdimascio" % "dotenv-java" % "3.0.0",
          // Speech: Vosk for lightweight STT (replacing abandoned Sphinx4)
    "net.java.dev.jna" % "jna" % "5.13.0",
    "com.alphacephei" % "vosk" % "0.3.45",
      "org.postgresql" % "postgresql" % "42.7.3",   // JDBC driver
      "com.typesafe"   % "config"      % "1.4.3"    // loads reference.conf
    )

  )

lazy val shared = (project in file("shared"))
  .settings(
    name := "shared",
    commonSettings,
    // No ConfigReader here; keep scalafix off on compile
    scalafixOnCompile := true,
    // Pure library: avoid main discovery noise
    Compile / discoveredMainClasses := Seq.empty
  )

lazy val workspaceRunner = (project in file("workspaceRunner"))
  .dependsOn(shared)
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .settings(
    Docker / maintainer := "llm4s",
    Docker / packageName := "llm4s/workspace-runner",
    dockerExposedPorts  := Seq(8080),
    dockerBaseImage     := "eclipse-temurin:21-jdk",
    Compile / mainClass := Some("org.llm4s.runner.RunnerMain"),
    name                := "workspace-runner",
    // Apply Scalafix warnings on compile for workspaceRunner
    scalafixOnCompile := false,
    commonSettings,
    libraryDependencies ++= List(
      "com.lihaoyi"   %% "cask"            % "0.10.2",
      "com.lihaoyi"   %% "requests"        % "0.9.0",
      "org.postgresql" % "postgresql" % "42.7.3",   // JDBC driver
      "com.typesafe"   % "config"      % "1.4.3"
    ),
    Docker / dockerBuildOptions := Seq("--platform=linux/amd64"),
    dockerCommands ++= Seq(
      Cmd("USER", "root"),
      Cmd("RUN", "apt-get update && apt-get install -y curl gnupg apt-transport-https ca-certificates zip unzip"),
      Cmd(
        "RUN",
        "echo 'deb https://repo.scala-sbt.org/scalasbt/debian all main' | tee /etc/apt/sources.list.d/sbt.list"
      ),
      Cmd(
        "RUN",
        "curl -sL 'https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823' | apt-key add"
      ),
      Cmd("RUN", "apt-get update && apt-get install -y sbt"),
      Cmd("RUN", "curl -s 'https://get.sdkman.io' | bash"),
      Cmd(
        "RUN",
        "bash -c 'source /root/.sdkman/bin/sdkman-init.sh && sdk install scala " + scala3 + " && sdk install scala " + scala213 + "'"
      ),
      Cmd("ENV", "PATH=/root/.sdkman/candidates/scala/current/bin:$PATH")
    )
  )
  .settings(
    publish / skip := true
  )

lazy val samples = (project in file("samples"))
  .dependsOn(shared, root)
  .settings(
    name := "samples",
    commonSettings,
    // Samples see ConfigReader via root; enable scalafix on compile
    scalafixOnCompile := true
  )
  .settings(
    publish / skip := true
  )

lazy val crossLibDependencies = Def.setting {
  Seq(
    "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    "com.softwaremill.sttp.client4" %% "core"  % "4.0.9",
    "com.lihaoyi"                   %% "ujson" % "4.2.1",
    "org.apache.pdfbox" % "pdfbox" % "3.0.5",
    "org.apache.poi" % "poi-ooxml" % "5.4.1",
    "org.apache.tika" % "tika-core" % "3.2.1",
    "com.lihaoyi" %% "requests" % "0.9.0",
    "org.jsoup" % "jsoup" % "1.21.1",
    "org.postgresql" % "postgresql" % "42.7.3",   // JDBC driver
    "com.typesafe"   % "config"      % "1.4.3"    // loads reference.conf
  )
}

lazy val crossTestScala2 = (project in file("crosstest/scala2"))
  .settings(
    name         := "crosstest-scala2",
    scalaVersion := scala213,
    Test / fork  := true,
    libraryDependencies ++= crossLibDependencies.value ++ Seq(
      "org.llm4s" %% "llm4s" % (ThisBuild / version).value % Test
    )
  )


lazy val crossTestScala3 = (project in file("crosstest/scala3"))
  .dependsOn(root % "compile->compile;test->test") // fine: both are Scala 3
  .settings(
    name         := "crosstest-scala3",
    scalaVersion := scala3,
    Test / fork  := true,
    resolvers   += Resolver.mavenLocal,
    resolvers   += Resolver.defaultLocal,
    libraryDependencies ++= crossLibDependencies.value,
    scalacOptions ++= scala3CompilerOptions
  )


addCommandAlias("buildAll", ";clean;+compile;+test")
addCommandAlias("publishAll", ";clean;+publish")
// Run tests across all modules, including samples and crossTest modules
addCommandAlias(
  "testAll",
  ";project root; +test; project shared; +test; project workspaceRunner; +test; project samples; +test; project root; +publishLocal; project crossTestScala2; test; project crossTestScala3; test"
)

addCommandAlias(
  "cleanTestAll",
  ";project root; clean; project shared; clean; project workspaceRunner; clean; project samples; clean; project crossTestScala2; clean; project crossTestScala3; clean; project root; testAll"
)

addCommandAlias(
  "cleanTestAllAndFormat",
  ";scalafmtAll;project root; clean; project shared; clean; project workspaceRunner; clean; project samples; clean; project crossTestScala2; clean; project crossTestScala3; clean; project root; testAll"
)
addCommandAlias("compileAll", ";+compile")
addCommandAlias("testCross", ";crossTestScala2/test;crossTestScala3/test")
addCommandAlias("fullCrossTest", ";clean ;crossTestScala2/clean ;crossTestScala3/clean ;+publishLocal ;testCross")

mimaPreviousArtifacts := Set(
  organization.value %% "llm4s" % "0.1.4"
)
