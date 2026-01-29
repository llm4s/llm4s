package org.llm4s.config

import org.llm4s.types.Result
import org.llm4s.error.ConfigurationError
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * Configuration data for Brave Search tool.
 *
 * @param apiKey The Brave Search API key
 * @param apiUrl The base URL for the Brave Search API (default: https://api.search.brave.com/res/v1)
 * @param count The number of search results to return per request
 * @param safeSearch The safe search level (off, moderate, or strict)
 */
final case class BraveSearchToolConfig(
  apiKey: String,
  apiUrl: String,
  count: Int,
  safeSearch: String
)

/**
 * Internal PureConfig-based loader for tools configuration.
 *
 * This loader follows the same pattern as ProviderConfigLoader and EmbeddingsConfigLoader
 * to provide a consistent, scalable approach for loading tool API keys and configurations.
 *
 * External code should use Llm4sConfig.loadBraveSearchTool() rather than this object directly.
 */
private[config] object ToolsConfigLoader {

  implicit private val braveSectionReader: PureConfigReader[BraveSearchToolConfig] =
    PureConfigReader.forProduct4("apiKey", "apiUrl", "count", "safeSearch")(BraveSearchToolConfig.apply)

  // ---- Public API used by Llm4sConfig ----

  /**
   * Load Brave Search tool configuration from the given configuration source.
   *
   * Loads the complete BraveSearchToolConfig configuration including:
   * - apiKey: The Brave Search API key
   * - apiUrl: The base URL for the Brave Search API
   * - count: The number of search results to return
   * - safeSearch: The safe search level setting
   *
   * Configuration is expected at path: llm4s.tools.brave
   *
   * @param source The configuration source to load from (typically ConfigSource.default)
   * @return Right(BraveSearchToolConfig) if configuration is valid, Left(ConfigurationError) otherwise
   */
  def loadBraveSearchTool(source: ConfigSource): Result[BraveSearchToolConfig] =
    source.at("llm4s.tools.brave").load[BraveSearchToolConfig].left.map { failures =>
      val msg = failures.toList.map(_.description).mkString("; ")
      ConfigurationError(s"Failed to load llm4s tools config via PureConfig: $msg")
    }
}
