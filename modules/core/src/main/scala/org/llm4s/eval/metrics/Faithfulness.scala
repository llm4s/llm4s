package org.llm4s.eval.metrics

import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.eval.{ EvalContext, EvalMetric, EvalResult, ResponseParser }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

/**
 * Faithfulness metric - measures if the answer is grounded in the context.
 * Uses 0.7 threshold by default.
 */
class Faithfulness private (override val threshold: Double) extends EvalMetric {

  val name: String = "Faithfulness"

  val description: String = "Measures if the answer is grounded in the retrieved context"

  override def evaluate(context: EvalContext, llmClient: LLMClient): Result[EvalResult] = {
    if (context.contexts.isEmpty) {
      return Right(EvalResult.fail(name, 0.0, "No context provided"))
    }

    val systemPrompt =
      """You are a faithfulness evaluation assistant.
        |Respond in this format:
        |SCORE: [0.0-1.0]
        |EXPLANATION: [brief explanation]""".stripMargin

    val userPrompt = s"""Query: ${context.query}
       |
       |Context:
       |${context.contexts.mkString("\n\n")}
       |
       |Answer: ${context.answer}
       |
       |Is the answer faithful to the context?""".stripMargin

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
            Left(ValidationError.invalid("faithfulness_score", s"Could not parse: ${response.take(100)}"))
        }
    }
  }
}

object Faithfulness {
  def apply(threshold: Double = 0.7): Result[Faithfulness] =
    if (threshold >= 0.0 && threshold <= 1.0) Right(new Faithfulness(threshold))
    else Left(ConfigurationError(s"Threshold must be in [0.0, 1.0], got $threshold"))

  def strict: Result[Faithfulness]  = apply(0.9)
  def lenient: Result[Faithfulness] = apply(0.5)
}
