package org.llm4s.eval.metrics

import org.llm4s.error.ConfigurationError
import org.llm4s.eval.{ EvalContext, EvalMetric, EvalResult, ResponseParser }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

/**
 * Context Precision metric - measures if retrieved context is useful.
 * Uses 0.5 threshold (vs 0.7) as retrieval systems often return mixed results.
 */
class ContextPrecision private (override val threshold: Double) extends EvalMetric {

  val name: String = "ContextPrecision"

  val description: String = "Measures if retrieved context chunks are relevant to the query"

  override def evaluate(context: EvalContext, llmClient: LLMClient): Result[EvalResult] = {
    if (context.contexts.isEmpty) {
      return Right(EvalResult.fail(name, 0.0, "No context provided"))
    }

    val systemPrompt =
      """You are a context relevance evaluation assistant.
        |Respond in this format:
        |SCORE: [0.0-1.0]
        |RELEVANT_CHUNKS: [comma-separated chunk numbers or NONE]
        |EXPLANATION: [brief explanation]""".stripMargin

    val chunksFormatted =
      context.contexts.zipWithIndex.map { case (c, i) => s"[${i + 1}] ${c.take(300)}" }.mkString("\n")

    val userPrompt = s"""Query: ${context.query}
       |
       |Chunks:
       |$chunksFormatted
       |
       |How relevant are these chunks?""".stripMargin

    val conversation = Conversation(Seq(SystemMessage(systemPrompt), UserMessage(userPrompt)))
    val options      = CompletionOptions(temperature = 0.0, maxTokens = Some(300))

    for {
      completion <- llmClient.complete(conversation, options)
      result     <- parseResponse(completion.message.content)
    } yield result
  }

  private def parseResponse(response: String): Result[EvalResult] = {
    val lines       = response.split("\n").map(_.trim).filter(_.nonEmpty)
    val scoreOpt    = ResponseParser.extractScore(response)
    val explanation = ResponseParser.extractExplanation(response)
    val relevant =
      lines.find(_.startsWith("RELEVANT_CHUNKS:")).map(_.stripPrefix("RELEVANT_CHUNKS:").trim).getOrElse("")

    val detailedExplanation =
      if (relevant.nonEmpty && relevant != "NONE") s"$explanation (Relevant: $relevant)" else explanation

    scoreOpt match {
      case Some(rawScore) =>
        val clampedScore = Math.max(0.0, Math.min(1.0, rawScore))
        Right(EvalResult(name, clampedScore, passes(clampedScore), detailedExplanation))

      case None =>
        ResponseParser.extractFirstNumber(response) match {
          case Some(rawScore) if rawScore >= 0.0 && rawScore <= 1.0 =>
            Right(EvalResult(name, rawScore, passes(rawScore), ""))
          case _ =>
            Left(ConfigurationError(s"Could not parse context precision score: ${response.take(100)}"))
        }
    }
  }
}

object ContextPrecision {
  def apply(threshold: Double = 0.5): Result[ContextPrecision] =
    if (threshold >= 0.0 && threshold <= 1.0) Right(new ContextPrecision(threshold))
    else Left(ConfigurationError(s"Threshold must be in [0.0, 1.0], got $threshold"))

  def strict: Result[ContextPrecision]  = apply(0.75)
  def lenient: Result[ContextPrecision] = apply(0.25)

  def unsafe(threshold: Double = 0.5): ContextPrecision =
    apply(threshold).fold(e => throw new IllegalArgumentException(e.message), identity)
}
