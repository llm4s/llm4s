// Portable llm4s spike (#2338): how much of llm4s compiles for Scala.js and Scala Native, and one portable
// OpenAI-compatible chat client. A self-contained build, run from this directory; the llm4s build never loads it.
// Findings: docs/design/portable-spike.md.
import sbtcrossproject.CrossPlugin.autoImport.{ crossProject, CrossType }

val scala3        = "3.7.1"  // the llm4s build's version
val upickle       = "4.4.3"  // the llm4s build's version
val cats          = "2.13.0" // the llm4s build's version
val slf4j         = "2.0.17" // the llm4s build's version
val scalaTst      = "3.2.20" // the llm4s build's version
val sttp4         = "4.0.27" // 4.1.x is built with Scala 3.9, whose TASTy 3.7.1 cannot read
val scalaJavaTime = "2.7.0"  // what sttp4 already brings to Scala.js
val laminar       = "17.2.1"

ThisBuild / scalaVersion := scala3
// `sbt portableNative/publishLocal` puts the prototype where the Scala CLI demo (demos/cli) resolves it.
ThisBuild / organization := "org.llm4s"
ThisBuild / version      := "0.0.0-spike"
// The llm4s build's options (project/Dependencies.scala), but `-java-output-version 21`, which only the JVM reads.
ThisBuild / scalacOptions ++= Seq(
  "-feature",
  "-unchecked",
  "-source:3.3",
  "-Wsafe-init",
  "-deprecation",
  "-Wunused:all",
  "-Werror"
)

// This directory is docs/design/portable-spike/build in the llm4s repository.
val repoRoot = file("../../../..").getCanonicalFile
val coreSrc  = repoRoot / "modules/core/src/main/scala"
val mediaSrc = repoRoot / "modules/media/src/main/scala"

// llm4s-core's own files, compiled unchanged on all three platforms. Each path is relative to core's
// src/main/scala/org/llm4s; the media vocabulary comes whole. The inventory in the design record says why every
// other file in these packages is left out.
val portableCoreFiles = Seq(
  "core/safety/ErrorMapper.scala",
  "core/safety/Safety.scala",
  "llmconnect/config/EmbeddingModelConfig.scala",
  "reliability/RetryPolicy.scala",
  "security/KnownSecrets.scala",
  "security/Secret.scala",
  "util/BoundedJson.scala",
  "util/DurationText.scala",
  "util/ExactMatcher.scala",
  "util/Redaction.scala",
  "util/SecretPatterns.scala",
  "error/APIError.scala",
  "error/AssistantError.scala",
  "error/AuthenticationError.scala",
  "error/CancelledError.scala",
  "error/ConfigurationError.scala",
  "error/ContextError.scala",
  "error/ErrorRecovery.scala",
  "error/ExecutionError.scala",
  "error/InvalidInputError.scala",
  "error/LLMError.scala",
  "error/NetworkError.scala",
  "error/NonRecoverableError.scala",
  "error/NotFoundError.scala",
  "error/OptimisticLockFailure.scala",
  "error/ProcessingError.scala",
  "error/RateLimitError.scala",
  "error/RecoverableError.scala",
  "error/ServiceError.scala",
  "error/SimpleError.scala",
  "error/SystemError.scala",
  "error/ThrowableOps.scala",
  "error/TimeoutError.scala",
  "error/TokenizerError.scala",
  "error/TroubleshootingLinks.scala",
  "error/UnknownError.scala",
  "error/UnsupportedContentPartError.scala",
  "error/ValidationError.scala",
  "llmconnect/model/Citation.scala",
  "llmconnect/model/Completion.scala",
  "llmconnect/model/CompletionOptions.scala",
  "llmconnect/model/ContentPart.scala",
  "llmconnect/model/Conversation.scala",
  "llmconnect/model/EmbeddingError.scala",
  "llmconnect/model/EmbeddingRequest.scala",
  "llmconnect/model/EmbeddingResponse.scala",
  "llmconnect/model/EmbeddingVector.scala",
  "llmconnect/model/InputPurpose.scala",
  "llmconnect/model/Message.scala",
  "llmconnect/model/Modality.scala",
  "llmconnect/model/ReasoningEffort.scala",
  "llmconnect/model/ResponseFormat.scala",
  "llmconnect/model/ThinkingBlock.scala",
  "llmconnect/model/UsageSummary.scala",
  "toolapi/Backoff.scala",
  "toolapi/SafeParameterExtractor.scala",
  "toolapi/Schema.scala",
  "toolapi/SchemaDefinition.scala",
  "toolapi/ToolCallInfo.scala",
  "toolapi/ToolExecutionConfig.scala",
  "toolapi/ToolExecutionStrategy.scala",
  "toolapi/ToolFunction.scala",
  "toolapi/ToolHints.scala",
  "toolapi/ToolParameterError.scala",
  "toolapi/ToolRegistry.scala",
  "types/package.scala",
  "types/ProviderModelTypes.scala"
)

lazy val root = (project in file("."))
  .aggregate(portable.jvm, portable.js, portable.native, laminarDemo, cliDemo)
  .settings(publish / skip := true)

lazy val portable = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("portable"))
  .settings(
    name := "llm4s-portable-spike",
    Compile / unmanagedSources ++= portableCoreFiles.map(p => coreSrc / "org/llm4s" / p),
    Compile / unmanagedSourceDirectories += mediaSrc,
    libraryDependencies ++= Seq(
      "org.typelevel"                 %%% "cats-core" % cats,
      "com.lihaoyi"                   %%% "upickle"   % upickle,
      "com.softwaremill.sttp.client4" %%% "core"      % sttp4,
      "org.scalatest"                 %%% "scalatest" % scalaTst % Test
    )
  )
  .jvmSettings(
    // The JVM build compiles core's Java annotations themselves; JS and Native have no javac, so they get
    // Scala stand-ins (portable/js-native/src/main/scala).
    Compile / unmanagedSourceDirectories += repoRoot / "modules/core/src/main/java",
    libraryDependencies += "org.slf4j" % "slf4j-api" % slf4j
  )
  .platformsSettings(JSPlatform, NativePlatform)(
    // `Completion.created` and `ErrorRecovery` use java.time, which neither platform's javalib has.
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTime
  )
  .jsSettings(jsSettings)
  .nativeSettings(nativeSettings)

// `Redaction`'s patterns use look-behind, which Scala.js's regex needs ECMAScript 2018 for (the default is 2015).
// Every current browser and Node 10+ has it.
lazy val jsSettings = Seq(
  scalaJSLinkerConfig ~= (_.withESFeatures(_.withESVersion(org.scalajs.linker.interface.ESVersion.ES2018)))
)

// sttp's Scala Native build links libcurl and libidn2 (`apt-get install libcurl4-openssl-dev libidn2-dev`,
// `brew install libidn2`; macOS ships libcurl). Homebrew on Apple silicon is not on the linker's default path.
lazy val nativeSettings = Seq(
  nativeConfig ~= { c =>
    val brew = file("/opt/homebrew/lib")
    if (brew.isDirectory) c.withLinkingOptions(c.linkingOptions :+ s"-L$brew") else c
  }
)

// The Laminar page (demos/laminar): `sbt laminarDemo/fullLinkJS`, then serve demos/laminar over HTTP.
lazy val laminarDemo = (project in file("demos/laminar"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(portable.js)
  .settings(
    jsSettings,
    publish / skip                  := true,
    scalaJSUseMainModuleInitializer := true,
    // FetchStreamingSpec runs the browser path on Node's own fetch, ReadableStream and TextDecoder (Node 18+).
    Test / scalaJSUseMainModuleInitializer := false,
    libraryDependencies ++= Seq(
      "com.raquo"     %%% "laminar"   % laminar,
      "org.scalatest" %%% "scalatest" % scalaTst % Test
    )
  )

// The command-line chat (demos/cli/chat.scala), which Scala CLI builds too: `sbt cliDemo/nativeLink`.
lazy val cliDemo = (project in file("demos/cli"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(portable.native)
  .settings(
    nativeSettings,
    publish / skip                       := true,
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value),
    nativeConfig ~= (_.withMode(scala.scalanative.build.Mode.releaseFast))
  )
