package org.llm4s.javaapi

import org.llm4s.llmconnect.LLMClient

object JLlmClientTestFactory {

  def create(underlying: LLMClient): JLlmClient = Llm4s.wrapClient(underlying)
}
