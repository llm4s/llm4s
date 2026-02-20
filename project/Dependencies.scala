import sbt._

object Dependencies {
  lazy val munit = "org.scalameta" %% "munit" % "0.7.29"
}


object Versions {
  val cats     = "2.13.0"
  val upickle  = "4.2.1"
  val logback  = "1.5.18"
  val monocle  = "3.3.0"
  val scalatest = "3.2.19"
  val scalamock = "7.4.2"
  val fansi    = "0.5.0"
  val postgres = "42.7.3"
  val sqlite   = "3.45.3.0"
  val config   = "1.4.3"
  val hikariCP = "5.1.0"
  val pureconfig = "0.17.6"

  val azureOpenAI = "1.0.0-beta.16"
  val anthropic   = "2.11.1"
  val jtokkit     = "1.1.0"
  val websocket   = "1.6.0"
  val ujson       = "4.2.1"
  val pdfbox      = "3.0.5"
  val commonsIO   = "2.18.0"
  val tika        = "3.2.1"
  val poi         = "5.4.1"
  val jsoup       = "1.21.1"
  val jna         = "5.13.0"
  val vosk        = "0.3.45"

  val cask       = "0.10.2"

  // AWS SDK
  val awsSdk     = "2.29.51"
  val opentelemetry = "1.34.1"

  // Prometheus (1.x stable)
  val prometheus = "1.4.3"
}

object Deps {

  val cats      = "org.typelevel" %% "cats-core" % Versions.cats
  val upickle   = "com.lihaoyi"   %% "upickle"   % Versions.upickle
  val logback   = "ch.qos.logback" % "logback-classic" % Versions.logback
  val monocleCore  = "dev.optics" %% "monocle-core"  % Versions.monocle
  val monocleMacro = "dev.optics" %% "monocle-macro" % Versions.monocle
  val scalatest = "org.scalatest" %% "scalatest" % Versions.scalatest
  val scalamock = "org.scalamock" %% "scalamock" % Versions.scalamock
  val fansi     = "com.lihaoyi"   %% "fansi"     % Versions.fansi
  val postgres  = "org.postgresql" % "postgresql" % Versions.postgres
  val sqlite    = "org.xerial"     % "sqlite-jdbc" % Versions.sqlite
  val config    = "com.typesafe"   % "config"    % Versions.config
  val hikariCP  = "com.zaxxer"     % "HikariCP"  % Versions.hikariCP
  val pureConfig  = "com.github.pureconfig" %% "pureconfig-core" % Versions.pureconfig


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
}

object Common {
  val scala213 = "2.13.16"
  val scala3 = "3.7.1"

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
    "-Wunused:linted",
    "-Ytasty-reader"
  )

  // ---- scalacOptions helper ----
  def scalacOptionsForVersion(scalaVersion: String): Seq[String] =
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((2, 13)) => scala2CompilerOptions
      case Some((3, _)) => scala3CompilerOptions
      case _ => Seq.empty
    }
}