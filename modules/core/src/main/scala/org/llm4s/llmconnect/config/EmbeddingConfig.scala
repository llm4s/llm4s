package org.llm4s.llmconnect.config

import org.llm4s.config.{ ConfigKeys, ConfigReader }
import ConfigKeys._
import scala.util.Try

case class EmbeddingProviderConfig(
  baseUrl: String,
  model: String,
  apiKey: String
)

object EmbeddingConfig {

  def loadEnv(name: String)(config: ConfigReader): String =
    config.get(name).getOrElse(throw new RuntimeException(s"Missing env variable: $name"))

  def loadOptionalEnv(name: String, default: String)(config: ConfigReader): String =
    config.get(name).getOrElse(default)

  /** Optional getter that trims and drops empty strings. */
  def loadOpt(name: String)(config: ConfigReader): Option[String] =
    config.get(name).map(_.trim).filter(_.nonEmpty)

  // ---------- Providers (TEXT embeddings over HTTP) ----------
  def openAI(config: ConfigReader): EmbeddingProviderConfig = EmbeddingProviderConfig(
    baseUrl = loadEnv(OPENAI_EMBEDDING_BASE_URL)(config),
    model = loadEnv(OPENAI_EMBEDDING_MODEL)(config),
    apiKey = loadEnv(OPENAI_API_KEY)(config)
  )

  def voyage(config: ConfigReader): EmbeddingProviderConfig = EmbeddingProviderConfig(
    baseUrl = loadEnv(VOYAGE_EMBEDDING_BASE_URL)(config),
    model = loadEnv(VOYAGE_EMBEDDING_MODEL)(config),
    apiKey = loadEnv(VOYAGE_API_KEY)(config)
  )

  def activeProvider(config: ConfigReader): String = loadEnv(EMBEDDING_PROVIDER)(config)

  // ---------- Paths & query (now OPTIONAL to avoid static init failures) ----------
  // These were previously required and caused crashes when using EMBEDDING_INPUT_PATHS only.
  // Return Option types for better null safety and validation
  def inputPath(config: ConfigReader): Option[String]  = loadOpt(EMBEDDING_INPUT_PATH)(config)
  def inputPaths(config: ConfigReader): Option[String] = loadOpt("EMBEDDING_INPUT_PATHS")(config)
  def query(config: ConfigReader): Option[String]      = loadOpt(EMBEDDING_QUERY)(config)

  // Legacy compatibility methods - use the Option variants above when possible
  def inputPathOrEmpty(config: ConfigReader): String  = inputPath(config).getOrElse("")
  def inputPathsOrEmpty(config: ConfigReader): String = inputPaths(config).getOrElse("")
  def queryOrEmpty(config: ConfigReader): String      = query(config).getOrElse("")

  // ---------- Text chunking ----------
  def chunkSize(config: ConfigReader): Int           = loadOptionalEnv(CHUNK_SIZE, "1000")(config).toInt
  def chunkOverlap(config: ConfigReader): Int        = loadOptionalEnv(CHUNK_OVERLAP, "100")(config).toInt
  def chunkingEnabled(config: ConfigReader): Boolean = loadOptionalEnv(CHUNKING_ENABLED, "true")(config).toBoolean

  // ---------- Local models for non-text (used by UniversalEncoder) ----------
  def imageModel(config: ConfigReader): String = loadOptionalEnv("IMAGE_MODEL", "openclip-vit-b32")(config)
  def audioModel(config: ConfigReader): String = loadOptionalEnv("AUDIO_MODEL", "wav2vec2-base")(config)
  def videoModel(config: ConfigReader): String = loadOptionalEnv("VIDEO_MODEL", "timesformer-base")(config)

  // ---------- Multimedia defaults (future encoders / windowing) ----------
  def videoFps(config: ConfigReader): Int             = loadOptionalEnv("VIDEO_FPS", "2")(config).toInt
  def videoClipSeconds(config: ConfigReader): Int     = loadOptionalEnv("VIDEO_CLIP_SECONDS", "8")(config).toInt
  def audioSampleRate(config: ConfigReader): Int      = loadOptionalEnv("AUDIO_SAMPLE_RATE", "16000")(config).toInt
  def audioWindowSeconds(config: ConfigReader): Int   = loadOptionalEnv("AUDIO_WINDOW_SECONDS", "5")(config).toInt
  def chunkOverlapRatio(config: ConfigReader): Double = loadOptionalEnv("CHUNK_OVERLAP_RATIO", "0.25")(config).toDouble

  // ---------- Configuration validation ----------
  /** Validates that a provider configuration has all required fields. */
  def validateProviderConfig(config: EmbeddingProviderConfig): Either[String, EmbeddingProviderConfig] =
    if (config.baseUrl.trim.isEmpty) Left("Provider baseUrl is required but was empty")
    else if (config.model.trim.isEmpty) Left("Provider model is required but was empty")
    else if (config.apiKey.trim.isEmpty) Left("Provider apiKey is required but was empty")
    else Right(config)

  /** Validates that required embedding configuration is present for the active provider. */
  def validateConfig(config: ConfigReader): Either[String, String] =
    Try {
      val provider = activeProvider(config)
      val providerConfig = provider.toLowerCase match {
        case "openai" => openAI(config)
        case "voyage" => voyage(config)
        case other    => throw new RuntimeException(s"Unknown embedding provider: $other")
      }
      provider -> providerConfig
    }.toEither.left
      .map(_.getMessage)
      .flatMap { case (provider, cfg) => validateProviderConfig(cfg).map(_ => provider) }
}
