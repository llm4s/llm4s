package org.llm4s.eval.metrics

import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.eval.{ EvalContext, EvalMetric, EvalResult, ResponseParser }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

/**
 * Answer Relevance metric - measures if the answer addresses the query.
 * Does not require context (evaluates answer-to-query relevance only).
 */
class AnswerRelevance private (override val threshold: Double) extends EvalMetric {

  val name: String = "AnswerRelevance"

  val description: String = "Measures if the answer is relevant to the query"

  override def evaluate(context: EvalContext, llmClient: LLMClient): Result[EvalResult] = {
    val systemPrompt =
      """You are an answer relevance evaluation assistant.
        |Respond in this format:
        |SCORE: [0.0-1.0]
        |EXPLANATION: [brief explanation]""".stripMargin

    val userPrompt = s"""Query: ${context.query}
       |
       |Answer: ${context.answer}
       |
       |Is the answer relevant to the query?""".stripMargin

    val conversation = Conversation(Seq(SystemMessage(systemPrompt), UserMessage(userPrompt)))
    val options      = CompletionOptions(temperature = 0.0, maxTokens = Some(200))

    for {
      completion <- llmClient.complete(conversation, options)
      result     <- parseResponse(completion.message.content)
    } yield result
  }

  private def parseResponse(response: String): Result[EvalResult] = {
    val scoreOpt    = ResponseParser.extractScore(response)
    val explanation = ResponseParser.extractExplanation(response)

    scoreOpt match {
      case Some(rawScore) =>
        val clampedScore = Math.max(0.0, Math.min(1.0, rawScore))
        Right(EvalResult(name, clampedScore, passes(clampedScore), explanation))

      case None =>
        ResponseParser.extractFirstNumber(response) match {
          case Some(rawScore) if rawScore >= 0.0 && rawScore <= 1.0 =>
            Right(EvalResult(name, rawScore, passes(rawScore), ""))
          case _ =>
            Left(ValidationError.invalid("answer_relevance_score", s"Could not parse: ${response.take(100)}"))
        }
    }
  }
}

object AnswerRelevance {
  def apply(threshold: Double = 0.7): Result[AnswerRelevance] =
    if (threshold >= 0.0 && threshold <= 1.0) Right(new AnswerRelevance(threshold))
    else Left(ConfigurationError(s"Threshold must be in [0.0, 1.0], got $threshold"))

  def strict: Result[AnswerRelevance]  = apply(0.9)
  def lenient: Result[AnswerRelevance] = apply(0.5)
}
