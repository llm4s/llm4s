package org.llm4s.llmconnect.utils

import java.time.Instant

/**
 * Connection status for LLM clients
 */
sealed trait ConnectionStatus

object ConnectionStatus {
  case object Connected                                              extends ConnectionStatus
  case object Disconnected                                           extends ConnectionStatus
  case object Connecting                                             extends ConnectionStatus
  case class Error(message: String, cause: Option[Throwable] = None) extends ConnectionStatus
}

/**
 * Provider capabilities information
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
 * Health check information
 */
final case class ClientHealth(
  status: ConnectionStatus,
  lastHealthCheck: Instant,
  responseTimeMs: Option[Long] = None,
  errorCount: Long = 0,
  capabilities: Option[ProviderCapabilities] = None
)
