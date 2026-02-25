package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.LLMClient

/**
 * A middleware that wraps an LLMClient to add cross-cutting behavior.
 *
 * Middleware instances are composable and applied in order via LLMClientPipeline.
 * The last middleware added is the outermost wrapper (first to execute).
 */
trait LLMMiddleware {

  /** Human-readable name for logging/debugging. */
  def name: String

  /**
   * Wrap the given LLMClient, returning a new client with added behavior.
   *
   * Implementations should delegate all LLMClient methods to `next`,
   * adding behavior before/after delegation as needed.
   *
   * @param next the next client in the pipeline
   * @return a new LLMClient with this middleware's behavior added
   */
  def wrap(next: LLMClient): LLMClient
}
