package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.error.NotFoundError
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.config.ProviderConfigLoader
import org.llm4s.config.ConfigKeys.LLM_MODEL
import org.llm4s.llmconnect.config.{ EmbeddingConfig => EmbCfg }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.config.{ LangfuseConfig, TracingSettings }
import org.llm4s.trace.TracingMode
import org.llm4s.config.DefaultConfig

import scala.util.Try

trait ConfigReader {
  def get(key: String): Option[String]
  def getOrElse(key: String, default: String): String = get(key).getOrElse(default)

  def require(key: String): Result[String] =
    get(key).filter(_.trim.nonEmpty).toRight(NotFoundError(s"Missing: $key", key))

  def getPath(path: String): Option[String] = get(path)
}

object ConfigReader {

  def apply(map: Map[String, String]): ConfigReader = new ConfigReader {
    override def get(key: String): Option[String] = map.get(key)
  }

  // Flat key -> llm4s.* path mapping for unified config resolution
  private val keyMapping: Map[String, String] = {
    import ConfigKeys._
    Map(
      // Core
      LLM_MODEL -> "llm4s.llm.model",
      // OpenAI
      OPENAI_API_KEY  -> "llm4s.openai.apiKey",
      OPENAI_BASE_URL -> "llm4s.openai.baseUrl",
      OPENAI_ORG      -> "llm4s.openai.organization",
      // Azure OpenAI
      AZURE_API_BASE    -> "llm4s.azure.endpoint",
      AZURE_API_KEY     -> "llm4s.azure.apiKey",
      AZURE_API_VERSION -> "llm4s.azure.apiVersion",
      // Anthropic
      ANTHROPIC_API_KEY  -> "llm4s.anthropic.apiKey",
      ANTHROPIC_BASE_URL -> "llm4s.anthropic.baseUrl",
      // Ollama
      OLLAMA_BASE_URL -> "llm4s.ollama.baseUrl",
      // Tracing (Langfuse)
      LANGFUSE_URL        -> "llm4s.tracing.langfuse.url",
      LANGFUSE_PUBLIC_KEY -> "llm4s.tracing.langfuse.publicKey",
      LANGFUSE_SECRET_KEY -> "llm4s.tracing.langfuse.secretKey",
      LANGFUSE_ENV        -> "llm4s.tracing.langfuse.env",
      LANGFUSE_RELEASE    -> "llm4s.tracing.langfuse.release",
      LANGFUSE_VERSION    -> "llm4s.tracing.langfuse.version",
      // Embeddings (core)
      EMBEDDING_PROVIDER   -> "llm4s.embeddings.provider",
      EMBEDDING_INPUT_PATH -> "llm4s.embeddings.inputPath",
      EMBEDDING_QUERY      -> "llm4s.embeddings.query",
      // Embeddings (OpenAI)
      OPENAI_EMBEDDING_BASE_URL -> "llm4s.embeddings.openai.baseUrl",
      OPENAI_EMBEDDING_MODEL    -> "llm4s.embeddings.openai.model",
      // Embeddings (Voyage)
      VOYAGE_API_KEY            -> "llm4s.embeddings.voyage.apiKey",
      VOYAGE_EMBEDDING_BASE_URL -> "llm4s.embeddings.voyage.baseUrl",
      VOYAGE_EMBEDDING_MODEL    -> "llm4s.embeddings.voyage.model",
      // Embeddings (chunking)
      CHUNK_SIZE       -> "llm4s.embeddings.chunking.size",
      CHUNK_OVERLAP    -> "llm4s.embeddings.chunking.overlap",
      CHUNKING_ENABLED -> "llm4s.embeddings.chunking.enabled"
    )
  }

  // Use anonymous class for Scala 2.13 compatibility (no SAM for Scala traits)
  def LLMConfig(): Result[ConfigReader] =
    Try {
      // Ensure we see fresh system properties between test cases/runs
      // scalafix:off DisableSyntax.NoConfigFactory
      ConfigFactory.invalidateCaches()
      val conf = ConfigFactory.load() // honors -D, application.conf (if present), reference.conf
      // scalafix:on DisableSyntax.NoConfigFactory
      new ConfigReader {
        private def readString(path: String): Option[String] =
          if (conf.hasPath(path)) scala.util.Try(conf.getString(path)).toOption.filter(_.trim.nonEmpty) else None

        override def getPath(path: String): Option[String] = readString(path)

        override def get(key: String): Option[String] = {
          val mapped = keyMapping.get(key)
          // Priority: mapped llm4s.* path -> direct path (may include dots) -> legacy flat key
          mapped.flatMap(getPath).orElse(getPath(key)).orElse(readString(key))
        }
      }
    }.toResult

  object Provider {
    def apply(): Result[ProviderConfig] =
      LLMConfig().flatMap(cfg => cfg.require(LLM_MODEL).flatMap(model => ProviderConfigLoader(model, cfg)))
  }

  object Embeddings {

    /** Returns the active provider name and its validated config. */
    def apply(): Result[(String, EmbeddingProviderConfig)] =
      LLMConfig().flatMap { cfg =>
        scala.util.Try {
          val provider = EmbCfg.activeProvider(cfg).toLowerCase
          val conf = provider match {
            case "openai" => EmbCfg.openAI(cfg)
            case "voyage" => EmbCfg.voyage(cfg)
            case other    => throw new RuntimeException(s"Unknown embedding provider: $other")
          }
          EmbCfg.validateProviderConfig(conf) match {
            case Left(err) => throw new RuntimeException(err)
            case Right(ok) => provider -> ok
          }
        }.toResult
      }
  }

  object TracingConf {
    def apply(): Result[TracingSettings] =
      LLMConfig().map { cfg =>
        val modeStr   = cfg.getPath("llm4s.tracing.mode").orElse(cfg.get("TRACING_MODE")).getOrElse("console")
        val mode      = TracingMode.fromString(modeStr)
        val url       = cfg.get("LANGFUSE_URL").getOrElse(DefaultConfig.DEFAULT_LANGFUSE_URL)
        val publicKey = cfg.get("LANGFUSE_PUBLIC_KEY").filter(_.nonEmpty)
        val secretKey = cfg.get("LANGFUSE_SECRET_KEY").filter(_.nonEmpty)
        val env       = cfg.get("LANGFUSE_ENV").getOrElse(DefaultConfig.DEFAULT_LANGFUSE_ENV)
        val release   = cfg.get("LANGFUSE_RELEASE").getOrElse(DefaultConfig.DEFAULT_LANGFUSE_RELEASE)
        val version   = cfg.get("LANGFUSE_VERSION").getOrElse(DefaultConfig.DEFAULT_LANGFUSE_VERSION)
        val lfCfg     = LangfuseConfig(url, publicKey, secretKey, env, release, version)
        org.llm4s.llmconnect.config.TracingSettings(mode, lfCfg)
      }
  }
}
