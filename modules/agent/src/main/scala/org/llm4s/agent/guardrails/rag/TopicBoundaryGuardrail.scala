package org.llm4s.agent.guardrails.rag

import org.llm4s.agent.guardrails.{ GuardrailAction, InputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.util.Try

/**
 * Result of topic boundary evaluation.
 *
 * @param isOnTopic Whether the query is within allowed topics
 * @param relevanceScore Score for topic relevance (0.0 to 1.0)
 * @param matchedTopics Topics from the allowed list that match the query
 * @param detectedTopic The actual topic detected in the query
 * @param explanation Brief explanation of the evaluation
 */
final case class TopicBoundaryResult(
  isOnTopic: Boolean,
  relevanceScore: Double,
  matchedTopics: Seq[String],
  detectedTopic: String,
  explanation: String
)

/**
 * LLM-based guardrail to ensure queries stay within allowed topic boundaries.
 *
 * TopicBoundaryGuardrail validates that user queries are within the scope of
 * allowed topics for the RAG application. This is useful for:
 * - Keeping conversations focused on the intended domain
 * - Preventing misuse of specialized assistants
 * - Ensuring the knowledge base is appropriate for the query
 *
 * **Evaluation process:**
 * 1. The query is analyzed to determine its topic/intent
 * 2. The detected topic is compared against allowed topics
 * 3. Query passes if it matches at least one allowed topic
 *
 * **Use cases:**
 * - Domain-specific assistants (medical, legal, technical)
 * - Customer support bots with defined scope
 * - Knowledge bases with specific subject matter
 * - Compliance with usage policies
 *
 * Example usage:
 * {{{
 * val guardrail = TopicBoundaryGuardrail(
 *   llmClient,
 *   allowedTopics = Seq("scala programming", "functional programming", "software development"),
 *   threshold = 0.6
 * )
 *
 * // On-topic query
 * guardrail.validate("How do I use pattern matching in Scala?") // passes
 *
 * // Off-topic query
 * guardrail.validate("What's the best pizza restaurant?") // fails
 * }}}
 *
 * @param llmClient The LLM client for evaluation
 * @param allowedTopics List of topics that queries should relate to
 * @param threshold Minimum relevance score to be considered on-topic (default: 0.5)
 * @param onFail Action to take when query is off-topic (default: Block)
 */
class TopicBoundaryGuardrail(
  val llmClient: LLMClient,
  val allowedTopics: Seq[String],
  val threshold: Double = 0.5,
  val onFail: GuardrailAction = GuardrailAction.Block
) extends InputGuardrail {

  val name: String = "TopicBoundaryGuardrail"

  override val description: Option[String] = Some(
    s"LLM-based topic boundary validation. Allowed: ${allowedTopics.take(3).mkString(", ")}${
        if (allowedTopics.size > 3) "..." else ""
      }"
  )

  /**
   * Validate that the query is within allowed topic boundaries.
   */
  override def validate(value: String): Result[String] =
    if (allowedTopics.isEmpty) {
      // No topic restrictions
      Right(value)
    } else {
      evaluateTopicBoundary(value).flatMap { result =>
        if (result.isOnTopic) {
          Right(value)
        } else {
          handleFailure(value, result)
        }
      }
    }

  /**
   * Handle off-topic query based on configured action.
   */
  private def handleFailure(query: String, result: TopicBoundaryResult): Result[String] =
    onFail match {
      case GuardrailAction.Block =>
        Left(
          ValidationError.invalid(
            "topic_boundary",
            s"Query is outside allowed topic boundaries. " +
              s"Detected topic: '${result.detectedTopic}'. " +
              s"Relevance score: ${"%.2f".format(result.relevanceScore)}. " +
              s"Allowed topics: ${allowedTopics.mkString(", ")}"
          )
        )

      case GuardrailAction.Warn =>
        Right(query)

      case GuardrailAction.Fix =>
        // Can't fix an off-topic query - fall back to block
        Left(
          ValidationError.invalid(
            "topic_boundary",
            s"Query is outside allowed topic boundaries and cannot be automatically adjusted. " +
              s"Please rephrase to relate to: ${allowedTopics.mkString(", ")}"
          )
        )
    }

  /**
   * Evaluate if the query is within topic boundaries.
   */
  private def evaluateTopicBoundary(query: String): Result[TopicBoundaryResult] = {
    val systemPrompt = buildSystemPrompt()
    val userPrompt   = buildUserPrompt(query)

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val options = CompletionOptions(
      temperature = 0.0,
      maxTokens = Some(200)
    )

    for {
      completion <- llmClient.complete(conversation, options)
      result     <- parseTopicResult(completion.message.content)
    } yield result
  }

  private def buildSystemPrompt(): String =
    """You are a topic classification assistant. Your task is to determine whether
      |a user query is related to a set of allowed topics.
      |
      |You MUST respond in this exact format:
      |IS_ON_TOPIC: [YES or NO]
      |RELEVANCE_SCORE: [number between 0.0 and 1.0]
      |MATCHED_TOPICS: [comma-separated list of matching allowed topics, or NONE]
      |DETECTED_TOPIC: [the actual topic of the query]
      |EXPLANATION: [brief explanation]
      |
      |Scoring guidelines:
      |- 1.0: Query directly and clearly relates to allowed topics
      |- 0.7-0.9: Query is related with some tangential aspects
      |- 0.4-0.6: Query has weak or indirect relation to allowed topics
      |- 0.1-0.3: Query has minimal overlap with allowed topics
      |- 0.0: Query is completely unrelated to all allowed topics""".stripMargin

  private def buildUserPrompt(query: String): String = {
    val topicsList = allowedTopics.zipWithIndex
      .map { case (topic, i) =>
        s"  ${i + 1}. $topic"
      }
      .mkString("\n")

    s"""Allowed Topics:
       |$topicsList
       |
       |User Query: "$query"
       |
       |Determine if this query is related to any of the allowed topics.""".stripMargin
  }

  /**
   * Parse the structured topic result from LLM response.
   */
  private def parseTopicResult(response: String): Result[TopicBoundaryResult] = {
    val lines = response.split("\n").map(_.trim).filter(_.nonEmpty)

    val isOnTopic = lines.find(_.startsWith("IS_ON_TOPIC:")).exists { line =>
      line.stripPrefix("IS_ON_TOPIC:").trim.toUpperCase.startsWith("YES")
    }

    val relevanceScore = lines
      .find(_.startsWith("RELEVANCE_SCORE:"))
      .flatMap { line =>
        val value = line.stripPrefix("RELEVANCE_SCORE:").trim.replaceAll("[^0-9.]", "")
        Try(value.toDouble).toOption.map(s => Math.max(0.0, Math.min(1.0, s)))
      }
      .getOrElse(if (isOnTopic) 0.7 else 0.3)

    val matchedTopics = lines
      .find(_.startsWith("MATCHED_TOPICS:"))
      .map { line =>
        val topics = line.stripPrefix("MATCHED_TOPICS:").trim
        if (topics.toUpperCase == "NONE" || topics.isEmpty) Seq.empty
        else topics.split(",").map(_.trim).filter(_.nonEmpty).toSeq
      }
      .getOrElse(Seq.empty)

    val detectedTopic = lines
      .find(_.startsWith("DETECTED_TOPIC:"))
      .map(_.stripPrefix("DETECTED_TOPIC:").trim)
      .getOrElse("Unknown")

    val explanation = lines
      .find(_.startsWith("EXPLANATION:"))
      .map(_.stripPrefix("EXPLANATION:").trim)
      .getOrElse("No explanation provided")

    // Apply threshold to determine final on-topic status
    val finalIsOnTopic = isOnTopic && relevanceScore >= threshold

    Right(
      TopicBoundaryResult(
        isOnTopic = finalIsOnTopic,
        relevanceScore = relevanceScore,
        matchedTopics = matchedTopics,
        detectedTopic = detectedTopic,
        explanation = explanation
      )
    )
  }
}

object TopicBoundaryGuardrail {

  /**
   * Create a topic boundary guardrail with specified allowed topics.
   */
  def apply(llmClient: LLMClient, allowedTopics: Seq[String]): TopicBoundaryGuardrail =
    new TopicBoundaryGuardrail(llmClient, allowedTopics)

  /**
   * Create a topic boundary guardrail with custom threshold.
   */
  def apply(
    llmClient: LLMClient,
    allowedTopics: Seq[String],
    threshold: Double
  ): TopicBoundaryGuardrail =
    new TopicBoundaryGuardrail(llmClient, allowedTopics, threshold = threshold)

  /**
   * Create a topic boundary guardrail with full customization.
   */
  def apply(
    llmClient: LLMClient,
    allowedTopics: Seq[String],
    threshold: Double,
    onFail: GuardrailAction
  ): TopicBoundaryGuardrail =
    new TopicBoundaryGuardrail(llmClient, allowedTopics, threshold = threshold, onFail = onFail)

  /**
   * Preset: Strict mode - requires high topic relevance.
   */
  def strict(llmClient: LLMClient, allowedTopics: Seq[String]): TopicBoundaryGuardrail =
    new TopicBoundaryGuardrail(
      llmClient,
      allowedTopics,
      threshold = 0.7,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Balanced mode - good default for most applications.
   */
  def balanced(llmClient: LLMClient, allowedTopics: Seq[String]): TopicBoundaryGuardrail =
    new TopicBoundaryGuardrail(
      llmClient,
      allowedTopics,
      threshold = 0.5,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Lenient mode - allows loosely related queries.
   */
  def lenient(llmClient: LLMClient, allowedTopics: Seq[String]): TopicBoundaryGuardrail =
    new TopicBoundaryGuardrail(
      llmClient,
      allowedTopics,
      threshold = 0.3,
      onFail = GuardrailAction.Block
    )

  /**
   * Preset: Monitoring mode - tracks off-topic queries without blocking.
   */
  def monitoring(llmClient: LLMClient, allowedTopics: Seq[String]): TopicBoundaryGuardrail =
    new TopicBoundaryGuardrail(
      llmClient,
      allowedTopics,
      threshold = 0.5,
      onFail = GuardrailAction.Warn
    )

  // ==========================================================================
  // Domain-Specific Presets
  // ==========================================================================

  /**
   * Preset for software development topics.
   */
  def softwareDevelopment(llmClient: LLMClient): TopicBoundaryGuardrail =
    balanced(
      llmClient,
      Seq(
        "software development",
        "programming",
        "coding",
        "software engineering",
        "debugging",
        "testing",
        "version control",
        "APIs",
        "databases",
        "system design"
      )
    )

  /**
   * Preset for customer support topics.
   */
  def customerSupport(llmClient: LLMClient, productTopics: Seq[String]): TopicBoundaryGuardrail =
    balanced(
      llmClient,
      productTopics ++ Seq(
        "product questions",
        "technical support",
        "troubleshooting",
        "account issues",
        "billing questions",
        "feature requests"
      )
    )

  /**
   * Preset for educational topics.
   */
  def education(llmClient: LLMClient, subjects: Seq[String]): TopicBoundaryGuardrail =
    balanced(
      llmClient,
      subjects ++ Seq(
        "learning",
        "studying",
        "homework help",
        "explanations",
        "examples",
        "practice problems"
      )
    )
}
