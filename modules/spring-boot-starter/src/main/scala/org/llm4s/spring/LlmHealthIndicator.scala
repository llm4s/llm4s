package org.llm4s.spring

import org.llm4s.java.JLlmClient
import org.springframework.boot.actuate.health.{ Health, HealthIndicator }

final class LlmHealthIndicator(private val client: JLlmClient) extends HealthIndicator {

  override def health(): Health =
    if (client != null)
      Health.up().withDetail("provider", "configured").build()
    else
      Health.down().withDetail("reason", "LLM client not initialised").build()
}
