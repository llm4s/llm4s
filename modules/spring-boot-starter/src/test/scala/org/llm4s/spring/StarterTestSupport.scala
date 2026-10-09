package org.llm4s.spring

import org.llm4s.javaapi.JLlmClient

import java.time.Duration
import java.util.concurrent.{ ExecutorService, Executors }

/** Shared fixtures: a daemon pool for tests that build templates and indicators by hand. */
object StarterTestSupport {

  val pool: ExecutorService = Executors.newCachedThreadPool { r =>
    val t = new Thread(r, "test-pool")
    t.setDaemon(true)
    t
  }

  def template(client: JLlmClient): LLM4STemplate = new LLM4STemplate(client, pool)

  def settings(probe: Boolean = false, secrets: Seq[String] = Nil): HealthSettings =
    HealthSettings("openai", "gpt-4o", probe, Duration.ofSeconds(60), Duration.ofSeconds(5), secrets)

  def indicator(client: JLlmClient): LlmHealthIndicator = new LlmHealthIndicator(client, settings(), pool)
}
