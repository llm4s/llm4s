package org.llm4s.examples.http

import org.llm4s.agent.AgentBuilder

/** The agent every reference app serves, whatever its HTTP stack. */
object ChatAgent {

  /** Streaming on, so `/chat/stream` gets the answer as it is generated. */
  val configure: AgentBuilder => AgentBuilder =
    _.withStreaming().withSystemPrompt("You are a helpful assistant. Answer concisely.")
}
