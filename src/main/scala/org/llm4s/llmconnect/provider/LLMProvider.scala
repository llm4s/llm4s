package org.llm4s.llmconnect.provider

sealed trait LLMProvider
object LLMProvider {
  case object OpenAI     extends LLMProvider
  case object Azure      extends LLMProvider
  case object Anthropic  extends LLMProvider
  case object OpenRouter extends LLMProvider
  case object Ollama     extends LLMProvider
}
