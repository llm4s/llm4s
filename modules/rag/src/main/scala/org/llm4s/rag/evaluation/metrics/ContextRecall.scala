package org.llm4s.rag.evaluation.metrics

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.rag.evaluation._
import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * Context Recall metric: measures if all relevant information was retrieved.
 *
 * Algorithm:
 * 1. Extract key facts/sentences from the ground truth answer
 * 2. For each fact, check if it can be attributed to the retrieved contexts
 * 3. Score = Number of facts covered by contexts / Total facts in ground truth
 *
 * The intuition: if all facts needed to answer the question correctly are
 * present in the retrieved contexts, recall is 1.0. Missing facts lower the score.
 *
 * @param llmClient The LLM client for fact extraction and attribution
 *
 * @example
 * {{{{
 * val metric = ContextRecall(llmClient)
 * val sample = EvalSample(
 *   question = "What are the symptoms of diabetes?",
 *   answer = "...",  // answer not used for this metric
 *   contexts = Seq(
 *     "Diabetes symptoms include excessive thirst and frequent urination.",
 *     "Type 2 diabetes may cause fatigue and blurred vision."
 *   ),
 *   groundTruth = Some("Symptoms of diabetes include increased thirst, frequent urination, fatigue, and blurred vision.")
 * )
 * val result = metric.evaluate(sample)
 * // Score = facts covered / total facts from ground truth
 * }}}}
 */
class ContextRecall(llmClient: LLMClient) extends RAGASMetric {

  override val name: String        = "context_recall"
  override val description: String = "Measures if all relevant information was retrieved"

  override val requiredInputs: Set[RequiredInput] =
    Set(RequiredInput.Contexts, RequiredInput.GroundTruth)

  override def evaluate(sample: EvalSample): Result[MetricResult] =
    sample.groundTruth match {
      case None =>
        Left(EvaluationError.missingInput("ground_truth"))

      case Some(gt) if gt.trim.isEmpty =>
        Right(
          MetricResult(
            metricName = name,
            score = 1.0,
            details = Map("reason" -> "Empty ground truth has no facts to verify")
          )
        )

      case Some(gt) =>
        if (sample.contexts.isEmpty || sample.contexts.forall(_.trim.isEmpty)) {
          return Right(
            MetricResult(
              metricName = name,
              score = 0.0,
              details = Map("reason" -> "No contexts provided to check coverage against")
            )
          )
        }

        for {
          gtFacts      <- extractFacts(gt)
          attributions <- attributeFacts(gtFacts, sample.contexts)
          coveredCount = attributions.count(_.covered)
          score        = if (gtFacts.isEmpty) 1.0 else coveredCount.toDouble / gtFacts.size
        } yield MetricResult(
          metricName = name,
          score = math.max(0.0, math.min(1.0, score)),
          details = Map(
            "totalFacts"       -> gtFacts.size,
            "coveredFacts"     -> coveredCount,
            "missingFacts"     -> attributions.filterNot(_.covered).map(_.fact),
            "factAttributions" -> attributions
          )
        )
    }

  /**
   * Extract key facts from the ground truth answer.
   */
  private def extractFacts(groundTruth: String): Result[Seq[String]] = {
    val systemPrompt =
      """You are an expert at extracting factual statements from text.
        |Given a ground truth answer, extract all key facts that would need to be
        |present in retrieved documents to correctly answer the question.
        |
        |Rules:
        |- Each fact should be a single, atomic piece of information
        |- Facts should be self-contained and verifiable
        |- Extract facts that are essential for answering the question correctly
        |- Ignore stylistic elements, focus on information content
        |
        |Respond with ONLY a JSON array of fact strings.
        |Example: ["Paris is the capital of France", "Paris has a population of over 2 million"]""".stripMargin

    val userPrompt = s"""Extract all key facts from this ground truth answer:

\"\"\"
$groundTruth
\"\"\"

Respond with ONLY a JSON array of strings:"""

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val options = CompletionOptions(temperature = 0.0, maxTokens = Some(1000))

    for {
      completion <- llmClient.complete(conversation, options)
      facts      <- parseFacts(completion.content)
    } yield facts
  }

  /**
   * Check which ground truth facts are covered by the retrieved contexts.
   */
  private def attributeFacts(
    facts: Seq[String],
    contexts: Seq[String]
  ): Result[Seq[FactAttribution]] =
    if (facts.isEmpty) {
      Right(Seq.empty)
    } else {
      val combinedContext = contexts.zipWithIndex
        .map { case (ctx, i) => s"[Context ${i + 1}]: $ctx" }
        .mkString("\n\n")

      val factsFormatted = facts.zipWithIndex
        .map { case (fact, i) => s"${i + 1}. $fact" }
        .mkString("\n")

      val systemPrompt =
        """You are an expert at verifying if facts are supported by reference documents.
          |For each fact from the ground truth, determine if it can be attributed to
          |(i.e., is supported by or can be inferred from) the provided contexts.
          |
          |Rules:
          |- A fact is COVERED if any context contains the same information or strongly implies it
          |- A fact is NOT COVERED if no context mentions or supports the information
          |- Be thorough: even paraphrased or implied information counts as covered
          |
          |Respond with ONLY a JSON array of objects with "fact", "covered" (boolean), and "source" (context number or null).
          |Example: [{"fact": "Paris is the capital", "covered": true, "source": 1}]""".stripMargin

      val userPrompt = s"""Contexts:
$combinedContext

Ground Truth Facts to verify:
$factsFormatted

For each fact, determine if it is covered by any of the contexts. Respond with ONLY JSON:"""

      val conversation = Conversation(
        Seq(
          SystemMessage(systemPrompt),
          UserMessage(userPrompt)
        )
      )

      val options = CompletionOptions(temperature = 0.0, maxTokens = Some(2000))

      for {
        completion   <- llmClient.complete(conversation, options)
        attributions <- parseAttributions(completion.content, facts)
      } yield attributions
    }

  /**
   * Parse extracted facts from LLM response.
   */
  private def parseFacts(response: String): Result[Seq[String]] =
    Try {
      val jsonStr = extractJsonArray(response)
      val arr     = ujson.read(jsonStr).arr
      arr.map(_.str).toSeq
    }.toResult.left.map(e => EvaluationError.parseError(s"Failed to parse facts: ${e.message}"))

  /**
   * Parse fact attributions from LLM response.
   */
  private def parseAttributions(response: String, originalFacts: Seq[String]): Result[Seq[FactAttribution]] =
    Try {
      val jsonStr = extractJsonArray(response)
      val arr     = ujson.read(jsonStr).arr

      arr.zipWithIndex.map { case (v, idx) =>
        val obj     = v.obj
        val fact    = obj.get("fact").map(_.str).getOrElse(originalFacts.lift(idx).getOrElse(""))
        val covered = obj.get("covered").exists(_.bool)
        val source  = obj.get("source").flatMap(s => if (s.isNull) None else Some(s.num.toInt))
        FactAttribution(fact, covered, source)
      }.toSeq
    }.toResult.left.map(e => EvaluationError.parseError(s"Failed to parse attributions: ${e.message}"))

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

/**
 * Result of attributing a fact to contexts.
 *
 * @param fact The fact from ground truth
 * @param covered Whether the fact is covered by any context
 * @param sourceContext The context number (1-indexed) that covers this fact, if any
 */
final case class FactAttribution(
  fact: String,
  covered: Boolean,
  sourceContext: Option[Int] = None
)

object ContextRecall {

  /**
   * Create a new ContextRecall metric.
   */
  def apply(llmClient: LLMClient): ContextRecall = new ContextRecall(llmClient)
}
