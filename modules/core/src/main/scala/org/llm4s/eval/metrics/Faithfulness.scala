package org.llm4s.eval.metrics

import org.llm4s.error.ValidationError
import org.llm4s.eval.{ EvalContext, EvalMetric, EvalResult }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.util.Try

/**
 * Faithfulness metric - measures if the answer is grounded in the context.
 */
class Faithfulness(override val threshold: Double = 0.7) extends EvalMetric {

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
    val lines    = response.split("\n").map(_.trim).filter(_.nonEmpty)
    val scoreOpt = lines.find(_.startsWith("SCORE:")).flatMap(l => Try(l.stripPrefix("SCORE:").trim.toDouble).toOption)
    val explanation = lines.find(_.startsWith("EXPLANATION:")).map(_.stripPrefix("EXPLANATION:").trim).getOrElse("")

    scoreOpt match {
      case Some(score) =>
        Right(EvalResult(name, Math.max(0.0, Math.min(1.0, score)), passes(score), explanation))
      case None =>
        Try(response.trim.replaceAll("[^0-9.]", "").toDouble).toOption match {
          case Some(s) if s >= 0.0 && s <= 1.0 => Right(EvalResult(name, s, passes(s), ""))
          case _ => Left(ValidationError.invalid("faithfulness_parse", s"Could not parse: ${response.take(100)}"))
        }
    }
  }
}

object Faithfulness {
  def apply(): Faithfulness                  = new Faithfulness()
  def apply(threshold: Double): Faithfulness = new Faithfulness(threshold)
  def strict: Faithfulness                   = new Faithfulness(0.9)
  def lenient: Faithfulness                  = new Faithfulness(0.5)
}
