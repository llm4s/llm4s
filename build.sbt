import xerial.sbt.Sonatype.sonatypeCentralHost

inThisBuild(
  List(
    scalaVersion           := "3.3.3",
    version                := "0.1.0-SNAPSHOT",
    organization           := "org.llm4s",
    organizationName       := "llm4s",
    versionScheme          := Some("early-semver"),
    sonatypeCredentialHost := sonatypeCentralHost,
    // Scalafmt configuration
//    scalafmtOnCompile := true,
    // Maven central repository deployment
    homepage               := Some(url("https://github.com/llm4s/")),
    sonatypeCredentialHost := "s01.oss.sonatype.org",
    sonatypeRepository     := "https://s01.oss.sonatype.org/service/local",
    pgpPublicRing          := file("/tmp/public.asc"),
    pgpSecretRing          := file("/tmp/secret.asc"),
    pgpPassphrase          := sys.env.get("PGP_PASSPHRASE").map(_.toArray),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/llm4s/llm4s/"),
        "scm:git:git@github.com:llm4s/llm4s.git"
      )
    ),
  )
)

sonatypeRepository := "https://s01.oss.sonatype.org/service/local"

// Common Scala 3 settings for all projects
lazy val scala3Settings = List(
  scalacOptions ++= List(
    "-explain",          // Explain errors in more detail
    "-Xfatal-warnings",  // Fail on warnings
    "-source:3.3",       // Ensure Scala 3 syntax
  )
)

lazy val root = (project in file("."))
  .aggregate(shared, workspaceRunner)
  .dependsOn(shared)
  .settings(
    name := "llm4s",
    scala3Settings,
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
    scala3Settings,
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
    scala3Settings,
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
    scala3Settings,
    libraryDependencies ++= List(
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "org.scalatest" %% "scalatest"       % "3.2.19" % Test
    )
  )
  .settings(
    publish / skip := true
  )
