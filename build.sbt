import com.typesafe.sbt.packager.docker.Cmd
import sbt.Keys.{libraryDependencies, *}


// Define supported Scala versions
val scala213 = "2.13.16"
val scala3   = "3.7.1"
val scala3CompilerOptions = Seq(
  "-explain",
  "-explain-types",
  "-Wconf:cat=unused:s",   // suppress unused warnings
  "-Wconf:cat=deprecation:s", // suppress deprecation warnings
  "-source:3.3",
  "-Wsafe-init",
  "-deprecation",
  "-Wunused:all"
)
val scala2CompilerOptions = Seq(
  "-Xfatal-warnings",
  "-feature",
  "-unchecked",
  "-deprecation",
  "-Wunused:imports",
  "-Wunused:privates",
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
          out.ref.value
        case Some(out) =>
          s"${out.ref.value}+${out.commitSuffix.mkString("", "", "")}-SNAPSHOT"
        case None =>
          "0.0.0-UNKNOWN"
      }
    }
  )
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
    "com.lihaoyi"   %% "upickle"         % "4.2.1",
    "ch.qos.logback" % "logback-classic" % "1.5.18",
    "org.scalatest" %% "scalatest"       % "3.2.19" % Test
  )
)

lazy val root = (project in file("."))
  .aggregate(shared, workspaceRunner)
  .dependsOn(shared)
  .settings(
    name := "llm4s",
    commonSettings,
    libraryDependencies ++= List(
      "com.azure"          % "azure-ai-openai" % "1.0.0-beta.16",
      "com.anthropic"      % "anthropic-java"  % "1.1.0",
      "com.knuddels"       % "jtokkit"         % "1.1.0",
      "com.lihaoyi"       %% "requests"        % "0.9.0",
      "org.java-websocket" % "Java-WebSocket"  % "1.5.3",
      "org.typelevel"     %% "cats-core"       % "2.13.0",
      "org.scalatest"     %% "scalatest"       % "3.2.19" % Test,
      "org.scalamock"     %% "scalamock"       % "7.3.3"  % Test,
      "com.softwaremill.sttp.client4" %% "core"  % "4.0.0-M7",
      "com.lihaoyi"                   %% "ujson" % "4.2.1",
      "org.apache.pdfbox" % "pdfbox" % "2.0.27",
      "org.apache.poi" % "poi-ooxml" % "5.2.3",
      "com.lihaoyi" %% "requests" % "0.8.0",
      "org.jsoup" % "jsoup" % "1.17.2"

    )
  )

lazy val shared = (project in file("shared"))
  .settings(
    name := "shared",
    commonSettings
  )

lazy val workspaceRunner = (project in file("workspaceRunner"))
  .dependsOn(shared)
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .settings(
    Docker / maintainer := "llm4s",
    dockerExposedPorts  := Seq(8080),
    dockerBaseImage     := "eclipse-temurin:21-jdk",
    Compile / mainClass := Some("org.llm4s.runner.RunnerMain"),
    name                := "workspace-runner",
    commonSettings,
    libraryDependencies ++= List(
      "com.lihaoyi"   %% "cask"            % "0.9.7",
      "com.lihaoyi"   %% "requests"        % "0.9.0",
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
    commonSettings
  )
  .settings(
    publish / skip := true
  )

lazy val crossLibDependencies = Def.setting {
  Seq(
    "org.llm4s"     %% "llm4s"     % version.value,
    "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    "com.softwaremill.sttp.client4" %% "core"  % "4.0.0-M7",
    "com.lihaoyi"                   %% "ujson" % "4.2.1",
    "org.apache.pdfbox" % "pdfbox" % "2.0.27",
    "org.apache.poi" % "poi-ooxml" % "5.2.3",
    "com.lihaoyi" %% "requests" % "0.8.0",
    "org.jsoup" % "jsoup" % "1.17.2"

  )
}

lazy val crossTestScala2 = (project in file("crosstest/scala2"))
  .settings(
    name               := "crosstest-scala2",
    scalaVersion       := scala213,
    resolvers += Resolver.mavenLocal,
    resolvers += Resolver.defaultLocal,
    libraryDependencies ++= crossLibDependencies.value
  )

lazy val crossTestScala3 = (project in file("crosstest/scala3"))
  .settings(
    name               := "crosstest-scala3",
    scalaVersion       := scala3,
    resolvers += Resolver.mavenLocal,
    resolvers += Resolver.defaultLocal,
    libraryDependencies ++= crossLibDependencies.value,
    scalacOptions ++= scala3CompilerOptions
  )

addCommandAlias("buildAll", ";clean;+compile;+test")
addCommandAlias("publishAll", ";clean;+publish")
addCommandAlias("testAll", ";+test")
addCommandAlias("compileAll", ";+compile")
addCommandAlias("testCross", ";crossTestScala2/test;crossTestScala3/test")
addCommandAlias("fullCrossTest", ";clean ;crossTestScala2/clean ;crossTestScala3/clean ;+publishLocal ;testCross")

mimaPreviousArtifacts := Set(
  organization.value %% "llm4s" % "0.1.4"
)
