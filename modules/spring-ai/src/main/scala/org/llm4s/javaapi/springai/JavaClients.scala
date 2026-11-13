package org.llm4s.javaapi.springai

import org.llm4s.javaapi.JLlmClient
import org.llm4s.llmconnect.LLMClient

/** The Scala client behind a [[JLlmClient]], which the Java facade keeps to its own package and its subpackages. */
private[llm4s] object JavaClients {
  def underlying(client: JLlmClient): LLMClient = client.underlying
}
