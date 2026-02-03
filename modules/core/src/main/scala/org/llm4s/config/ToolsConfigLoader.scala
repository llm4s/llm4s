package org.llm4s.config

import org.llm4s.types.Result
import org.llm4s.error.ConfigurationError
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * Configuration data for Brave Search tool.
 *
 * This configuration is loaded from application.conf at the edge (via Llm4sConfig)
 * and passed to BraveSearchTool.create() when instantiating the tool.
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
 * Configuration data for DuckDuckGo Search tool.
 *
 * This configuration is loaded from application.conf at the edge (via Llm4sConfig)
 * and passed to DuckDuckGoSearchTool.create() when instantiating the tool.
 *
 * @param apiUrl The base URL for the DuckDuckGo API (default: https://api.duckduckgo.com)
 */
final case class DuckDuckGoSearchToolConfig(
  apiUrl: String
)

/**
 * Internal PureConfig-based loader for tools configuration.
 *
 * This loader follows the "config at the edge" pattern used throughout llm4s:
 * - Configuration loading happens at the application boundary via Llm4sConfig
 * - Tool implementations receive pre-loaded config objects as constructor parameters
 * - This keeps tool code pure and testable without direct config dependencies
 *
 * Architecture:
 * 1. Application code calls Llm4sConfig.loadBraveSearchTool() or loadDuckDuckGoSearchTool()
 * 2. Llm4sConfig delegates to this loader to read from application.conf
 * 3. The loaded config is passed to the tool's create() method
 * 4. Tool operates with the provided configuration
 *
 * External code should use Llm4sConfig.loadBraveSearchTool() and Llm4sConfig.loadDuckDuckGoSearchTool()
 * rather than this object directly.
 */
private[config] object ToolsConfigLoader {

  implicit private val braveSectionReader: PureConfigReader[BraveSearchToolConfig] =
    PureConfigReader.forProduct4("apiKey", "apiUrl", "count", "safeSearch")(BraveSearchToolConfig.apply)

  implicit private val duckDuckGoSectionReader: PureConfigReader[DuckDuckGoSearchToolConfig] =
    PureConfigReader.forProduct1("apiUrl")(DuckDuckGoSearchToolConfig.apply)

  // ---- Public API used by Llm4sConfig ----

  /**
   * Load Brave Search tool configuration from the given configuration source.
   *
   * This method reads configuration from application.conf and returns a BraveSearchToolConfig
   * that should be passed to BraveSearchTool.create().
   *
   * Loads the complete BraveSearchToolConfig configuration including:
   * - apiKey: The Brave Search API key
   * - apiUrl: The base URL for the Brave Search API
   * - count: The number of search results to return
   * - safeSearch: The safe search level setting
   *
   * Configuration is expected at path: llm4s.tools.brave
   *
   * Example application.conf:
   * {{{
   * llm4s {
   *   tools {
   *     brave {
   *       apiKey = "your-api-key"
   *       apiUrl = "https://api.search.brave.com/res/v1"
   *       count = 10
   *       safeSearch = "moderate"
   *     }
   *   }
   * }
   * }}}
   *
   * @param source The configuration source to load from (typically ConfigSource.default)
   * @return Right(BraveSearchToolConfig) if configuration is valid, Left(ConfigurationError) otherwise
   */
  def loadBraveSearchTool(source: ConfigSource): Result[BraveSearchToolConfig] =
    source.at("llm4s.tools.brave").load[BraveSearchToolConfig].left.map { failures =>
      val msg = failures.toList.map(_.description).mkString("; ")
      ConfigurationError(s"Failed to load llm4s tools config via PureConfig: $msg")
    }

  /**
   * Load DuckDuckGo Search tool configuration from the given configuration source.
   *
   * This method reads configuration from application.conf and returns a DuckDuckGoSearchToolConfig
   * that should be passed to DuckDuckGoSearchTool.create().
   *
   * Configuration is expected at path: llm4s.tools.duckduckgo
   *
   * Example application.conf:
   * {{{
   * llm4s {
   *   tools {
   *     duckduckgo {
   *       apiUrl = "https://api.duckduckgo.com"
   *     }
   *   }
   * }
   * }}}
   *
   * @param source The configuration source to load from (typically ConfigSource.default)
   * @return Right(DuckDuckGoSearchToolConfig) if configuration is valid, Left(ConfigurationError) otherwise
   */
  def loadDuckDuckGoSearchTool(source: ConfigSource): Result[DuckDuckGoSearchToolConfig] =
    source.at("llm4s.tools.duckduckgo").load[DuckDuckGoSearchToolConfig].left.map { failures =>
      val msg = failures.toList.map(_.description).mkString("; ")
      ConfigurationError(s"Failed to load llm4s tools config via PureConfig: $msg")
    }
}
