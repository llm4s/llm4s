package org.llm4s.java

import org.llm4s.llmconnect.LLMClient

object JLlmClientTestFactory {

  def create(underlying: LLMClient): JLlmClient = new JLlmClient(underlying)
}
