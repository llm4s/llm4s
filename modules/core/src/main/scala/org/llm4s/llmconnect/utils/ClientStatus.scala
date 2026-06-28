package org.llm4s.llmconnect.utils

import java.time.Instant

/**
 * Connection status for LLM clients.
 *
 * Represents the current state of a provider connection: connected, disconnected,
 * connecting, or in an error state with an optional underlying cause.
 */
sealed trait ConnectionStatus

object ConnectionStatus {
  case object Connected                                              extends ConnectionStatus
  case object Disconnected                                           extends ConnectionStatus
  case object Connecting                                             extends ConnectionStatus
  case class Error(message: String, cause: Option[Throwable] = None) extends ConnectionStatus
}

/**
 * Describes the capabilities supported by an LLM provider.
 *
 * @param supportsStreaming whether the provider supports streaming responses
 * @param supportsToolCalls whether the provider supports tool/function calling
 * @param supportsFunctionCalling whether the provider supports legacy function calling
 * @param supportsVision whether the provider supports image/vision inputs
 * @param maxTokens maximum token limit for completions, if known
 * @param supportedModels list of model identifiers the provider offers
 * @param metadata additional provider-specific key-value metadata
 */
final case class ProviderCapabilities(
  supportsStreaming: Boolean,
  supportsToolCalls: Boolean,
  supportsFunctionCalling: Boolean,
  supportsVision: Boolean,
  maxTokens: Option[Int] = None,
  supportedModels: List[String] = List.empty,
  metadata: Map[String, String] = Map.empty
)

/**
 * Health check snapshot for an LLM client connection.
 *
 * @param status current connection status
 * @param lastHealthCheck timestamp of the most recent health check
 * @param responseTimeMs round-trip time of the last successful health check in milliseconds
 * @param errorCount cumulative number of errors observed on this connection
 * @param capabilities provider capabilities discovered during the health check
 */
final case class ClientHealth(
  status: ConnectionStatus,
  lastHealthCheck: Instant,
  responseTimeMs: Option[Long] = None,
  errorCount: Long = 0,
  capabilities: Option[ProviderCapabilities] = None
)
