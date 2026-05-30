// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.samples.mcp

import pureconfig._
import pureconfig.ConfigReader
import pureconfig.error.ConfigReaderFailures
import scala.concurrent.duration.FiniteDuration

/**
 * Shared configuration types and loaders for MCP samples.
 *
 * This consolidates PureConfig-based configuration loading for both
 * the MCP server and client examples, avoiding duplication.
 */
object MCPConfig {

  /** Configuration for the server's identity returned in the initialize response. */
  case class ServerInfoConfig(name: String, version: String)

  /**
   * Configuration for the MCP server.
   * @param port The port the HTTP server will bind to.
   * @param path The base path for MCP endpoints (e.g., "/mcp").
   * @param serverInfo Identity details for the server.
   */
  case class McpServerConfig(
    port: Int,
    path: String,
    serverInfo: ServerInfoConfig
  )

  /**
   * Configuration for the MCP client.
   * @param name The name identifier for the MCP server connection.
   * @param port The port to connect to.
   * @param path The endpoint path.
   * @param timeout Connection timeout duration.
   * @param cacheTTL Tool cache time-to-live duration.
   */
  case class McpClientConfig(
    name: String,
    port: Int,
    path: String,
    timeout: FiniteDuration,
    cacheTTL: FiniteDuration
  )

  /** Combined samples MCP configuration section. */
  case class McpSamplesConfig(
    mcpServer: McpServerConfig,
    mcpClient: McpClientConfig
  )

  // ConfigReaders for all types
  implicit val serverInfoReader: ConfigReader[ServerInfoConfig] =
    ConfigReader.forProduct2("name", "version")(ServerInfoConfig.apply)

  implicit val mcpServerReader: ConfigReader[McpServerConfig] =
    ConfigReader.forProduct3("port", "path", "server-info")(McpServerConfig.apply)

  implicit val mcpClientReader: ConfigReader[McpClientConfig] =
    ConfigReader.forProduct5("name", "port", "path", "timeout", "cache-ttl")(McpClientConfig.apply)

  implicit val mcpSamplesConfigReader: ConfigReader[McpSamplesConfig] =
    ConfigReader.forProduct2("mcp-server", "mcp-client")(McpSamplesConfig.apply)

  /**
   * Load MCP server configuration from application.conf.
   * @return Either configuration failures or the server config.
   */
  def loadServerConfig(): Either[ConfigReaderFailures, McpServerConfig] =
    ConfigSource.default.at("llm4s.samples.mcp-server").load[McpServerConfig]

  /**
   * Load MCP client configuration from application.conf.
   * @return Either configuration failures or the client config.
   */
  def loadClientConfig(): Either[ConfigReaderFailures, McpClientConfig] =
    ConfigSource.default.at("llm4s.samples.mcp-client").load[McpClientConfig]
}
