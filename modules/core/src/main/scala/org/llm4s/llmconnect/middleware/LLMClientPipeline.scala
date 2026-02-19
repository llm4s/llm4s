package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.LLMClient

/**
 * Builder for composing middleware into a pipeline around a base LLMClient.
 *
 * Middleware is applied in FIFO order: the first middleware added is closest
 * to the base client, the last added is the outermost wrapper.
 *
 * Usage:
 * {{{
 * val client = LLMClientPipeline(openAIClient)
 *   .use(LoggingMiddleware(logger))             // innermost
 *   .use(MetricsMiddleware(collector))
 *   .use(RateLimitingMiddleware(config))         // outermost
 *   .build()
 * }}}
 */
final class LLMClientPipeline private (
  private val base: LLMClient,
  private val middlewares: Vector[LLMMiddleware]
) {

  /** Add a middleware to the pipeline. */
  def use(middleware: LLMMiddleware): LLMClientPipeline =
    new LLMClientPipeline(base, middlewares :+ middleware)

  /** Build the final client by applying all middlewares in order. */
  def build(): LLMClient =
    middlewares.foldLeft(base)((client, mw) => mw.wrap(client))

  /** Returns the names of all middleware in the pipeline, in application order. */
  def middlewareNames: Seq[String] = middlewares.map(_.name)
}

object LLMClientPipeline {
  def apply(base: LLMClient): LLMClientPipeline =
    new LLMClientPipeline(base, Vector.empty)
}
