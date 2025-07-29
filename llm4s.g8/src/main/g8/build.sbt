// build.sbt — at project root

// =========== Project metadata & versions ===========
ThisBuild / organization := "$package$"
ThisBuild / version := "$version$"
ThisBuild / scalaVersion := "$scala_version$"

// Enable SemanticDB for Scalafix semantic rules
ThisBuild / semanticdbEnabled  := true
ThisBuild / semanticdbVersion  := scalafixSemanticdb.revision

// =========== Dependencies ===========
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s" % "$llm4s_version$", // LLM library dependency
  "org.scalameta" %% "munit" % "$munit_version$" % Test,

  // Logger dependencies
  "ch.qos.logback" % "logback-classic" % "1.4.14", // Logback backend
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5" // scala-logging wrapper
  // integrating scala-logging into LLM (Large Language Model) scala applications is a recommended practice,
  // especially when using SLF4J and Logback.
  // This combination is widely adopted in the Scala ecosystem for its simplicity, performance, and compatibility with structured logging


  // Other Logger dependencies: For FP + LLM pipelines - LogStage is a top-tier choice—structured, efficient, observable
  // "io.7mind.izumi" %% "logstage-core" % "1.2.19", // core
  // "io.7mind.izumi" %% "logstage-rendering-circe" % "1.2.19", // JSON output
  // "io.7mind.izumi" %% "logstage-adapter-slf4j" % "1.2.19"    // optional SLF4J integration
  // LogStage, Woof are community‑endorsed for modern stacks but, not officially approved by scala core.
)

// Scalafix dependencies (needed for custom rules or built-ins)
ThisBuild / scalafixDependencies += "ch.epfl.scala" %% "scalafix-rules" % "0.14.3" // adjust version as needed

// =========== Compiler options ===========
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",         // Warn about use of deprecated APIs
  "-feature",             // Warn about misused features
  "-unchecked",           // Additional warnings for unhandled cases
  "-Xlint",               // Recommended additional warnings
  "-Wdead-code",          // Warn when dead code is identified (was -Ywarn-dead-code)
  "-Wunused:locals",      // Warn when local defs are unused (was -Ywarn-unused)
  "-encoding", "UTF-8",   // Specify character encoding
  {
    if (scalaVersion.value.startsWith("2.12"))
      "-Ywarn-unused-import" // 2.12 specific
    else
      "-Wunused:imports"     // Scala 2.13+ and Scala 3
  }
)

// =========== Project definition ===========
lazy val root = (project in file("."))
  .settings(
    name := "$name$",
    Compile / mainClass := Some("$package$.Main"), // optional
    Compile / scalafmtOnCompile := false // turn off automatic formatting on compile
  )
  .enablePlugins() // Add plugins as needed

// =========== Best Practices ===========
compileOrder := CompileOrder.Mixed
Global / onChangedBuildSource := ReloadOnSourceChanges
