package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.Completion

/** A reply `parseCompletion` must read: its `Completion`, or the test fails with the error it returned. */
extension (client: OpenAICompatibleClient)
  private[provider] def parsedCompletion(json: ujson.Value): Completion =
    client.parseCompletion(json).fold(e => throw new AssertionError(s"parseCompletion failed: ${e.message}"), identity)
