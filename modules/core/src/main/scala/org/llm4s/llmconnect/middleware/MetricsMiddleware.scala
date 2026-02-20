package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation }
import org.llm4s.metrics.{ ErrorKind, MetricsCollector, Outcome }
import org.llm4s.types.Result
import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS }

/**
 * Middleware that collects metrics for LLM operations.
 *
 * Replaces the provider-baked MetricsRecording trait with a composable layer.
 * Records request duration, success/failure counts, token usage, and costs.
 */
class MetricsMiddleware(
  collector: MetricsCollector,
  providerName: String,
  modelName: String
) extends LLMMiddleware {

  override def name: String = "metrics"

  override def wrap(client: LLMClient): LLMClient = new MiddlewareClient(client) {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      val start    = System.nanoTime()
      val result   = next.complete(conversation, options)
      val duration = FiniteDuration(System.nanoTime() - start, NANOSECONDS)

      recordMetrics(result, duration)
      result
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: org.llm4s.llmconnect.model.StreamedChunk => Unit
    ): Result[Completion] = {
      val start    = System.nanoTime()
      val result   = next.streamComplete(conversation, options, onChunk)
      val duration = FiniteDuration(System.nanoTime() - start, NANOSECONDS)

      recordMetrics(result, duration)
      result
    }

    private def recordMetrics(result: Result[Completion], duration: FiniteDuration): Unit =
      result match {
        case Right(completion) =>
          collector.observeRequest(providerName, modelName, Outcome.Success, duration)

          completion.usage.foreach { usage =>
            collector.addTokens(providerName, modelName, usage.promptTokens.toLong, usage.completionTokens.toLong)
          }

          completion.estimatedCost.foreach(cost => collector.recordCost(providerName, modelName, cost))

        case Left(error) =>
          val kind = ErrorKind.fromLLMError(error)
          collector.observeRequest(providerName, modelName, Outcome.Error(kind), duration)
      }
  }
}
