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
 * Configuration data for Exa Search tool.
 *
 * @param apiKey The Exa API key
 * @param apiUrl Base URL (default: https://api.exa.ai)
 * @param numResults Number of results to return
 * @param searchType Search type (auto, neural, fast, deep)
 * @param maxCharacters Maximum characters for text content
 */
final case class ExaSearchToolConfig(
  apiKey: String,
  apiUrl: String,
  numResults: Int,
  searchType: String,
  maxCharacters: Int,
)

object ExaSearchToolConfig {
  // Exa API-specific validation constants
  private val ValidSearchTypes = Set("auto", "neural", "fast", "deep")

  /**
   * Validate API key is non-empty.
   */
  def validateApiKey(key: String): Result[String] = {
    val trimmed = key.trim
    if (trimmed.nonEmpty) Right(trimmed)
    else Left(ConfigurationError("apiKey is required", List("apiKey")))
  }

  /**
   * Validate URL uses HTTPS protocol.
   */
  def validateHttps(url: String): Result[String] = {
    val trimmed = url.trim
    if (trimmed.startsWith("https://"))
      Right(trimmed)
    else
      Left(ConfigurationError(s"URL must use HTTPS protocol, got: $trimmed", List("apiUrl")))
  }

  /**
   * Validate number of search results is positive.
   */
  def validateNumResults(n: Int): Result[Int] =
    if (n > 0)
      Right(n)
    else
      Left(
        ConfigurationError(
          s"numResults must be greater than 0, got: $n",
          List("numResults")
        )
      )

  /**
   * Validate maximum characters is positive.
   */
  def validateMaxCharacters(n: Int): Result[Int] =
    if (n > 0)
      Right(n)
    else
      Left(ConfigurationError(s"maxCharacters must be greater than 0, got: $n", List("maxCharacters")))

  /**
   * Validate search type is one of Exa's allowed values.
   */
  def validateSearchType(s: String): Result[String] = {
    val normalized = s.trim.toLowerCase
    if (ValidSearchTypes.contains(normalized))
      Right(normalized)
    else
      Left(
        ConfigurationError(
          s"searchType must be one of ${ValidSearchTypes.mkString(", ")} (Exa API types), got: $s",
          List("searchType")
        )
      )
  }

  /**
   * Validate entire ExaSearchToolConfig.
   * Used by both config loading (fail-fast) and tool creation (defensive).
   */
  def validated(config: ExaSearchToolConfig): Result[ExaSearchToolConfig] =
    for {
      validatedApiKey        <- validateApiKey(config.apiKey)
      validatedApiUrl        <- validateHttps(config.apiUrl)
      validatedNumResults    <- validateNumResults(config.numResults)
      validatedSearchType    <- validateSearchType(config.searchType)
      validatedMaxCharacters <- validateMaxCharacters(config.maxCharacters)
    } yield config.copy(
      apiKey = validatedApiKey,
      apiUrl = validatedApiUrl,
      numResults = validatedNumResults,
      searchType = validatedSearchType,
      maxCharacters = validatedMaxCharacters
    )
}

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

  implicit private val exaSearchSectionReader: PureConfigReader[ExaSearchToolConfig] =
    PureConfigReader.forProduct5(
      "apiKey",
      "apiUrl",
      "numResults",
      "searchType",
      "maxCharacters"
    )(ExaSearchToolConfig.apply)

  // ---- Public API used by Llm4sConfig ----

  /**
   * Load Brave Search tool configuration from `llm4s.tools.brave`.
   *
   * @param source The configuration source
   * @return BraveSearchToolConfig or error
   */
  def loadBraveSearchTool(source: ConfigSource): Result[BraveSearchToolConfig] =
    source.at("llm4s.tools.brave").load[BraveSearchToolConfig].left.map { failures =>
      val msg = failures.toList.map(_.description).mkString("; ")
      ConfigurationError(s"Failed to load llm4s tools config via PureConfig: $msg")
    }

  /**
   * Load DuckDuckGo Search tool configuration from `llm4s.tools.duckduckgo`.
   *
   * @param source The configuration source
   * @return DuckDuckGoSearchToolConfig or error
   */
  def loadDuckDuckGoSearchTool(source: ConfigSource): Result[DuckDuckGoSearchToolConfig] =
    source.at("llm4s.tools.duckduckgo").load[DuckDuckGoSearchToolConfig].left.map { failures =>
      val msg = failures.toList.map(_.description).mkString("; ")
      ConfigurationError(s"Failed to load llm4s tools config via PureConfig: $msg")
    }

  /**
   * Load Exa Search tool configuration from `llm4s.tools.exa`.
   * Validates config-specific fields: numResults, maxCharacters, searchType.
   *
   * @param source The configuration source
   * @return ExaSearchToolConfig or error
   */
  def loadExaSearchTool(source: ConfigSource): Result[ExaSearchToolConfig] =
    source
      .at("llm4s.tools.exa")
      .load[ExaSearchToolConfig]
      .left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load Exa Search tool config: $msg")
      }
      .flatMap(ExaSearchToolConfig.validated)
}
