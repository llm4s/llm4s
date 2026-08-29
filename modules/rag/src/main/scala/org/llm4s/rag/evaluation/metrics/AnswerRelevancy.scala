package org.llm4s.rag.evaluation.metrics

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.utils.SimilarityUtils
import org.llm4s.rag.evaluation._
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * Answer Relevancy metric: measures how well the answer addresses the question.
 *
 * Algorithm:
 * 1. Generate N questions that the provided answer would address
 * 2. Compute embedding for the original question
 * 3. Compute embeddings for the generated questions
 * 4. Calculate cosine similarity between original and generated question embeddings
 * 5. Score = average similarity across generated questions
 *
 * The intuition: if the answer is relevant to the question, then questions
 * generated from the answer should be semantically similar to the original question.
 *
 * @param llmClient LLM client for generating questions from the answer
 * @param embeddingClient Client for computing embeddings
 * @param modelConfig Embedding model configuration
 * @param numGeneratedQuestions Number of questions to generate (default: 3)
 *
 * @example
 * {{{
 * val metric = AnswerRelevancy(llmClient, embeddingClient, modelConfig)
 * val sample = EvalSample(
 *   question = "What is machine learning?",
 *   answer = "Machine learning is a subset of AI that enables systems to learn from data.",
 *   contexts = Seq("...") // contexts not used for this metric
 * )
 * val result = metric.evaluate(sample)
 * // High score if generated questions are similar to "What is machine learning?"
 * }}}
 */
class AnswerRelevancy(
  llmClient: LLMClient,
  embeddingClient: EmbeddingClient,
  modelConfig: EmbeddingModelConfig,
  numGeneratedQuestions: Int = AnswerRelevancy.DEFAULT_NUM_QUESTIONS
) extends RAGASMetric {

  require(numGeneratedQuestions > 0, "numGeneratedQuestions must be positive")

  override val name: String        = "answer_relevancy"
  override val description: String = "Measures if the answer addresses the question"

  override val requiredInputs: Set[RequiredInput] =
    Set(RequiredInput.Question, RequiredInput.Answer)

  override def evaluate(sample: EvalSample): Result[MetricResult] = {
    if (sample.answer.trim.isEmpty) {
      return Right(
        MetricResult(
          metricName = name,
          score = 0.0,
          details = Map("reason" -> "Empty answer cannot be relevant")
        )
      )
    }

    if (sample.question.trim.isEmpty) {
      return Right(
        MetricResult(
          metricName = name,
          score = 0.0,
          details = Map("reason" -> "Empty question cannot be compared")
        )
      )
    }

    for {
      generatedQuestions  <- generateQuestionsFromAnswer(sample.answer)
      originalEmbedding   <- embedText(sample.question)
      generatedEmbeddings <- embedTexts(generatedQuestions)
      similarities = generatedEmbeddings.map(ge => SimilarityUtils.cosineSimilarity(originalEmbedding, ge))
      // Normalize similarities to 0-1 range (cosine can be -1 to 1)
      normalizedSimilarities = similarities.map(s => (s + 1.0) / 2.0)
      score = if (normalizedSimilarities.isEmpty) 0.0 else normalizedSimilarities.sum / normalizedSimilarities.size
    } yield MetricResult(
      metricName = name,
      score = math.max(0.0, math.min(1.0, score)),
      details = Map(
        "generatedQuestions" -> generatedQuestions,
        "similarities"       -> similarities,
        "numQuestions"       -> generatedQuestions.size
      )
    )
  }

  /**
   * Generate questions that the answer would address.
   */
  private def generateQuestionsFromAnswer(answer: String): Result[Seq[String]] = {
    val systemPrompt =
      s"""You are an expert at generating questions.
         |Given an answer, generate exactly $numGeneratedQuestions different questions that this answer would appropriately address.
         |
         |Rules:
         |- Each question should be natural and could reasonably be asked by a user
         |- Questions should be diverse but all relevant to the answer
         |- Questions should be specific, not overly generic
         |
         |Respond with ONLY a JSON array of question strings.
         |Example: ["What is X?", "How does X work?", "Why is X important?"]""".stripMargin

    val userPrompt = s"""Generate $numGeneratedQuestions questions that this answer would address:

\"\"\"
$answer
\"\"\"

Respond with ONLY a JSON array of strings:"""

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val options = CompletionOptions(temperature = 0.3, maxTokens = Some(500))

    for {
      completion <- llmClient.complete(conversation, options)
      questions  <- parseQuestions(completion.content)
    } yield questions
  }

  /**
   * Embed a single text.
   */
  private def embedText(text: String): Result[Seq[Double]] = {
    val request = EmbeddingRequest(input = Seq(text), model = modelConfig)
    embeddingClient
      .embed(request)
      .flatMap(response =>
        response.embeddings.headOption
          .toRight(org.llm4s.error.ProcessingError("embedText", "Embedding provider returned empty embeddings list"))
      )
  }

  /**
   * Embed multiple texts.
   */
  private def embedTexts(texts: Seq[String]): Result[Seq[Seq[Double]]] =
    if (texts.isEmpty) {
      Right(Seq.empty)
    } else {
      val request = EmbeddingRequest(input = texts, model = modelConfig)
      embeddingClient.embed(request).map(_.embeddings)
    }

  /**
   * Parse generated questions from LLM response.
   */
  private def parseQuestions(response: String): Result[Seq[String]] =
    Try {
      val jsonStr = extractJsonArray(response)
      val arr     = ujson.read(jsonStr).arr
      arr.map(_.str).toSeq
    }.toResult.left.map(e => EvaluationError.parseError(s"Failed to parse generated questions: ${e.message}"))

  /**
   * Extract JSON array from potentially markdown-wrapped response.
   */
  private def extractJsonArray(response: String): String = {
    val trimmed = response.trim

    val withoutCodeBlock = if (trimmed.startsWith("```")) {
      val lines = trimmed.split("\n")
      val start = 1
      val end   = lines.lastIndexWhere(_.trim == "```")
      if (end > start) {
        lines.slice(start, end).mkString("\n")
      } else {
        trimmed.stripPrefix("```json").stripPrefix("```").stripSuffix("```")
      }
    } else {
      trimmed
    }

    val startIdx = withoutCodeBlock.indexOf('[')
    val endIdx   = withoutCodeBlock.lastIndexOf(']')

    if (startIdx >= 0 && endIdx > startIdx) {
      withoutCodeBlock.substring(startIdx, endIdx + 1)
    } else {
      withoutCodeBlock
    }
  }
}

object AnswerRelevancy {

  /**
   * Default number of questions to generate.
   */
  val DEFAULT_NUM_QUESTIONS: Int = 3

  /**
   * Create a new AnswerRelevancy metric.
   */
  def apply(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    modelConfig: EmbeddingModelConfig,
    numGeneratedQuestions: Int = DEFAULT_NUM_QUESTIONS
  ): AnswerRelevancy = new AnswerRelevancy(llmClient, embeddingClient, modelConfig, numGeneratedQuestions)
}
