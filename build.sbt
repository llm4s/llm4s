ThisBuild / scalaVersion     := "2.13.12"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "org.llm4s"
ThisBuild / organizationName := "llm4s"

lazy val root = (project in file("."))
  .dependsOn(shared)
  .settings(
    name := "llm4s",
    libraryDependencies ++= List(
      "com.azure" % "azure-ai-openai" % "1.0.0-beta.14",
      "com.typesafe.play" %% "play-json" % "2.9.4",  // ✅ Play JSON dependency added
      "org.scalameta" %% "munit" % "0.7.29" % Test   // ✅ Fixed munit dependency
    )
  )

lazy val shared = (project in file("shared"))
  .settings(
    name := "shared",
    libraryDependencies ++= List(
      "com.lihaoyi" %% "upickle" % "4.1.0"
    )
  )

lazy val workspaceRunner = (project in file("workspaceRunner"))
  .dependsOn(shared)
  .settings(
    name := "workspace-runner",
    libraryDependencies ++= List(
      "com.lihaoyi" %% "upickle" % "4.1.0"
    )
  )
