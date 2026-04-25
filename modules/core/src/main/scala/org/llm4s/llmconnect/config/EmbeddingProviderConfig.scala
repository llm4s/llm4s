package org.llm4s.llmconnect.config

import org.llm4s.util.Redaction

import scala.concurrent.duration._

final case class EmbeddingProviderConfig(
  baseUrl: String,
  model: String,
  apiKey: String,
  // no streamTimeout — embedding requests are always non-streaming
  requestTimeout: FiniteDuration = 2.minutes,
) {
  override def toString: String =
    s"EmbeddingProviderConfig(baseUrl=$baseUrl, model=$model, apiKey=${Redaction.secret(apiKey)})"
}
