import xerial.sbt.Sonatype.sonatypeCentralHost

// Define supported Scala versions
val scala213 = "2.13.14"
val scala3 = "3.3.3"

inThisBuild(
  List(
    crossScalaVersions    := List(scala213, scala3),
    scalaVersion          := scala3,
    version               := "0.1.0-SNAPSHOT",
    organization          := "org.llm4s",
    organizationName      := "llm4s",
    versionScheme         := Some("early-semver"),
    sonatypeCredentialHost := sonatypeCentralHost,
    // Scalafmt configuration
//    scalafmtOnCompile := true,
    // Maven central repository deployment
    homepage              := Some(url("https://github.com/llm4s/")),
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeRepository    := "https://s01.oss.sonatype.org/service/local",
    pgpPublicRing         := file("/tmp/public.asc"),
    pgpSecretRing         := file("/tmp/secret.asc"),
    pgpPassphrase         := sys.env.get("PGP_PASSPHRASE").map(_.toArray),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/llm4s/llm4s/"),
        "scm:git:git@github.com:llm4s/llm4s.git"
      )
    ),
  )
)

sonatypeRepository := "https://s01.oss.sonatype.org/service/local"

// Scala options based on Scala version
def scalacOptionsForVersion(scalaVersion: String): Seq[String] =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, 13)) => Seq(
      // "-Xfatal-warnings",   // Temporarily disabled for cross-compilation
      "-deprecation",       // Emit warning and location for usages of deprecated APIs
      "-feature",           // Emit warning for feature usage
      "-unchecked"          // Enable warnings where generated code depends on assumptions
      // "-Wunused:imports"    // Temporarily disabled for cross-compilation
    )
    case Some((3, _)) => Seq(
      "-explain",           // Explain errors in more detail
      "-Xfatal-warnings",   // Fail on warnings
      "-source:3.3"         // Ensure Scala 3 syntax
    )
    case _ => Seq.empty
  }

// Common settings that apply to both Scala versions
lazy val commonSettings = Seq(
  Compile / scalacOptions := scalacOptionsForVersion(scalaVersion.value),

  // Source directories for cross-compilation
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
  }
)

lazy val root = (project in file("."))
  .aggregate(shared, workspaceRunner)
  .dependsOn(shared)
  .settings(
    name := "llm4s",
    commonSettings,
    libraryDependencies ++= List(
      "com.azure"      % "azure-ai-openai" % "1.0.0-beta.16",
      "com.anthropic"  % "anthropic-java"  % "1.1.0",
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "com.knuddels"   % "jtokkit"         % "1.1.0",
      "com.lihaoyi"   %% "upickle"         % "4.1.0",
      "com.lihaoyi"   %% "requests"        % "0.9.0",
      "org.scalatest" %% "scalatest"       % "3.2.19" % Test
    )
  )

lazy val shared = (project in file("shared"))
  .settings(
    name := "shared",
    commonSettings,
    libraryDependencies ++= List(
      "com.lihaoyi"   %% "upickle"         % "4.1.0",
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "org.scalatest" %% "scalatest"       % "3.2.19" % Test
    )
  )

lazy val workspaceRunner = (project in file("workspaceRunner"))
  .dependsOn(shared)
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .settings(
    Docker / maintainer := "llm4s",
    dockerExposedPorts  := Seq(8080),
    dockerBaseImage     := "eclipse-temurin:21-jdk",
//    Compile / mainClass := Some("com.llm4s.runner.RunnerMain"),
    name := "workspace-runner",
    commonSettings,
    libraryDependencies ++= List(
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "com.lihaoyi"   %% "upickle"         % "4.1.0",
      "com.lihaoyi"   %% "cask"            % "0.10.2",
      "org.scalatest" %% "scalatest"       % "3.2.19" % Test
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
    libraryDependencies ++= List(
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "org.scalatest" %% "scalatest"       % "3.2.19" % Test
    )
  )
  .settings(
    publish / skip := true
  )

val crossLibDependencies = Seq(
  "org.llm4s" %% "llm4s" % "0.1.0-SNAPSHOT",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

lazy val crossTestScala2 = (project in file("crosstest/scala2"))
  .settings(
    name := "crosstest-scala2",
    scalaVersion := scala213,
    crossScalaVersions := Seq(scala213),
    resolvers += Resolver.mavenLocal,
    resolvers += Resolver.defaultLocal,
    libraryDependencies ++= crossLibDependencies
  )


lazy val crossTestScala3 = (project in file("crosstest/scala3"))
  .settings(
    name := "crosstest-scala3",
    scalaVersion := scala3,
    crossScalaVersions := Seq(scala3),
    resolvers += Resolver.mavenLocal,
    resolvers += Resolver.defaultLocal,
    scalacOptions ++= Seq(
      "-language:strictEquality",
      "-Ysafe-init",
      "-deprecation",
      "-Wunused:imports",
      "-Xfatal-warnings"
    ),
    libraryDependencies ++= crossLibDependencies
  )

// Commands remain the same
addCommandAlias("buildAll", ";clean;+compile;+test")
addCommandAlias("publishAll", ";clean;+publish")
addCommandAlias("testAll", ";+test")
addCommandAlias("compileAll", ";+compile")
addCommandAlias("testCross", ";crossTestScala2/test;crossTestScala3/test")
// Add this to your build.sbt
addCommandAlias("fullCrossTest", ";clean ;crossTestScala2/clean ;crossTestScala3/clean ;+publishLocal ;testCross")

// MiMa settings remain the same
mimaPreviousArtifacts := Set(
  organization.value %% "llm4s" % "0.1.0-SNAPSHOT"
)
