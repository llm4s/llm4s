import sbt._

object Dependencies {
  lazy val munit = "org.scalameta" %% "munit" % "0.7.29"
}


object Versions {
  val cats     = "2.13.0"
  val upickle  = "4.4.3"
  val logback  = "1.5.34"
  val log4j    = "2.26.0"
  val monocle  = "3.3.0"
  val termflow = "0.4.0"
  val scalatest               = "3.2.20"
  val scalamock               = "7.5.5"
  val scalatestplusScalacheck = "3.2.19.0"
  val fansi    = "0.5.1"
  val postgres = "42.7.11"
  val sqlite   = "3.53.2.0"
  val config   = "1.4.9"
  // NOTE: HikariCP 7.x is available but is a 2-major jump (5->6->7) with a higher
  // JDK baseline; held for separate review.
  val hikariCP = "5.1.0"
  val pureconfig = "0.17.10"

  val azureOpenAI = "1.0.0-beta.16"
  val anthropic   = "2.42.0"
  val jtokkit     = "1.1.0"
  val websocket   = "1.6.0"
  val ujson       = "4.4.3"
  val pdfbox      = "3.0.7"
  val commonsIO   = "2.22.0"
  val tika        = "3.3.1"
  val poi         = "5.5.1"
  val jsoup       = "1.22.2"
  val jna         = "5.19.1"
  val vosk        = "0.3.45"

  // NOTE: cask 0.11.x is available but 0.x minor bumps can be breaking; held for review.
  val cask       = "0.10.2"

  // AWS SDK
  val awsSdk     = "2.46.14"
  val opentelemetry = "1.63.0"

  // Prometheus (1.x stable)
  val prometheus = "1.8.0"

  // Neo4j
  // NOTE: neo4j-java-driver 6.x is available but is a major bump; held for separate
  // review alongside the existing neo4j-harness/Netty compatibility constraint.
  val neo4j = "5.27.0"
}

object Deps {

  val cats                    = "org.typelevel" %% "cats-core" % Versions.cats
  val upickle                 = "com.lihaoyi"   %% "upickle"   % Versions.upickle
  val logback                 = "ch.qos.logback" % "logback-classic" % Versions.logback
  val log4jToSlf4j            = "org.apache.logging.log4j" % "log4j-to-slf4j" % Versions.log4j
  val termflow                = "org.llm4s" %% "termflow" % Versions.termflow
  val monocleCore             = "dev.optics" %% "monocle-core"  % Versions.monocle
  val monocleMacro            = "dev.optics" %% "monocle-macro" % Versions.monocle
  val scalatest               = "org.scalatest"    %% "scalatest"          % Versions.scalatest
  val scalamock               = "org.scalamock"    %% "scalamock"          % Versions.scalamock
  val scalatestplusScalacheck = "org.scalatestplus" %% "scalacheck-1-18"   % Versions.scalatestplusScalacheck
  val fansi                   = "com.lihaoyi"   %% "fansi"     % Versions.fansi
  val postgres                = "org.postgresql" % "postgresql" % Versions.postgres
  val sqlite                  = "org.xerial"     % "sqlite-jdbc" % Versions.sqlite
  val config                  = "com.typesafe"   % "config"    % Versions.config
  val hikariCP                = "com.zaxxer"     % "HikariCP"  % Versions.hikariCP
  val pureConfig              = "com.github.pureconfig" %% "pureconfig-core" % Versions.pureconfig


  val azureOpenAI = "com.azure"     % "azure-ai-openai" % Versions.azureOpenAI
  val anthropic   = "com.anthropic" % "anthropic-java"  % Versions.anthropic
  val jtokkit     = "com.knuddels"  % "jtokkit"         % Versions.jtokkit
  val websocket   = "org.java-websocket" % "Java-WebSocket" % Versions.websocket
  val ujson       = "com.lihaoyi"  %% "ujson"           % Versions.ujson
  val pdfbox      = "org.apache.pdfbox" % "pdfbox"      % Versions.pdfbox
  val commonsIO   = "commons-io"   % "commons-io"       % Versions.commonsIO
  val tika        = "org.apache.tika" % "tika-core"     % Versions.tika
  val poi         = "org.apache.poi" % "poi-ooxml"      % Versions.poi
  val jsoup       = "org.jsoup"    % "jsoup"            % Versions.jsoup
  val jna         = "net.java.dev.jna" % "jna"          % Versions.jna
  val vosk        = "com.alphacephei"  % "vosk"         % Versions.vosk

  val cask       = "com.lihaoyi" %% "cask" % Versions.cask

  // AWS SDK
  val awsS3      = "software.amazon.awssdk" % "s3"  % Versions.awsSdk
  val awsSts     = "software.amazon.awssdk" % "sts" % Versions.awsSdk

  val opentelemetryApi = "io.opentelemetry" % "opentelemetry-api" % Versions.opentelemetry
  val opentelemetrySdk = "io.opentelemetry" % "opentelemetry-sdk" % Versions.opentelemetry
  val opentelemetryExporterOtlp = "io.opentelemetry" % "opentelemetry-exporter-otlp" % Versions.opentelemetry
  // Prometheus metrics
  val prometheusCore = "io.prometheus" % "prometheus-metrics-core" % Versions.prometheus
  val prometheusHttp = "io.prometheus" % "prometheus-metrics-exporter-httpserver" % Versions.prometheus

  // Neo4j
  val neo4jDriver = "org.neo4j.driver" % "neo4j-java-driver" % Versions.neo4j
  // Note: neo4j-harness is not included as a dependency because neo4j-harness 5.26.x
  // has a hard-coded incompatibility with Netty 4.1.115.Final on modern JVMs.
  // Integration tests use a real Neo4j instance via Neo4jGraphStore.local().
}

object Common {
  val scala3 = "3.7.1"

  private val scala3CompilerOptions = Seq(
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

  def scalacOptionsForVersion(scalaVersion: String): Seq[String] =
    scala3CompilerOptions
}
