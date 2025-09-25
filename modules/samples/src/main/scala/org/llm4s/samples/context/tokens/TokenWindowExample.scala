package org.llm4s.samples.context.tokens

import org.llm4s.config.ConfigReader
import org.llm4s.context.{ ConversationTokenCounter, TokenWindow, ConversationWindow }
import org.llm4s.context.tokens.TokenizerMapping
import org.llm4s.types.{ TokenBudget, HeadroomPercent }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Demonstrates token counting and conversation trimming with real LLM API calls.
 * Works with OpenAI, Anthropic, and Azure OpenAI models.
 *
 * To run this example:
 * ```bash
 * # OpenAI
 * export LLM_MODEL=openai/gpt-4o
 * export OPENAI_API_KEY=sk-your-key-here
 *
 * # Anthropic
 * export LLM_MODEL=anthropic/claude-3-5-sonnet-latest
 * export ANTHROPIC_API_KEY=sk-ant-your-key-here
 *
 * # Azure OpenAI
 * export LLM_MODEL=azure/your-deployment-name
 * export AZURE_OPENAI_API_KEY=your-key
 * export AZURE_OPENAI_ENDPOINT=https://your-resource.openai.azure.com/
 *
 * sbt "samples/runMain org.llm4s.samples.context.tokens.TokenWindowExample"
 * ```
 */
object TokenWindowExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting Multi-Provider Token Window Management Demo")

    val result = for {
      modelName    <-  ConfigReader.Provider().map(_.model)
      client       <- LLMConnect.fromEnv()
      tokenCounter <- createTokenCounter(modelName)
      demoResults  <- runDemo(client, tokenCounter, modelName)
    } yield demoResults

    result.fold(
      error => logger.error(s"Demo failed: $error"),
      results => {
        logger.info("Demo completed successfully")
        displayResults(results)
      }
    )
  }

  private def createTokenCounter(modelName: String): Result[ConversationTokenCounter] =
    ConversationTokenCounter.forModel(modelName).map { counter =>
      val accuracyInfo = TokenizerMapping.getAccuracyInfo(modelName)

      logger.info(s"Created token counter for model: $modelName")
      accuracyInfo.isExact match {
        case true  => logger.info(s"Using exact tokenizer for $modelName")
        case false => logger.warn(s"Using approximate tokenizer for $modelName: ${accuracyInfo.description}")
      }

      counter
    }

  private def runDemo(
    client: org.llm4s.llmconnect.LLMClient,
    tokenCounter: ConversationTokenCounter,
    modelName: String
  ): Result[DemoResults] = {
    val longConversation = createLongConversation()

    val tokenAnalysis = analyzeTokens(tokenCounter, longConversation)
    val trimmingDemo  = demonstrateTokenWindowTrimming(tokenCounter, longConversation)
    val budgetDemo    = demonstrateAutomaticBudgetDerivation(client, modelName)

    testWithRealAPI(client, tokenCounter, longConversation, modelName).map { apiResults =>
      DemoResults(tokenAnalysis, apiResults, trimmingDemo, budgetDemo)
    }
  }

  private def analyzeTokens(
    tokenCounter: ConversationTokenCounter,
    conversation: Conversation
  ): TokenAnalysis = {
    val tokenCount = tokenCounter.countConversation(conversation)
    val breakdown  = tokenCounter.getTokenBreakdown(conversation)

    TokenAnalysis(
      totalTokens = tokenCount,
      messageCount = conversation.messages.length,
      averageTokensPerMessage = tokenCount / conversation.messages.length,
      breakdown = breakdown
    )
  }

  private def testWithRealAPI(
    client: org.llm4s.llmconnect.LLMClient,
    tokenCounter: ConversationTokenCounter,
    conversation: Conversation,
    modelName: String
  ): Result[APITestResults] = {
    val predictedTokens = tokenCounter.countConversation(conversation)

    client.complete(conversation) match {
      case Right(completion) =>
        Right(APITestResults.success(predictedTokens, completion, modelName))
      case Left(error) =>
        Right(APITestResults.failure(predictedTokens, error, modelName))
    }
  }

  private def displayResults(results: DemoResults): Unit = {
    displayTokenAnalysis(results.tokenAnalysis)
    displayBudgetDemo(results.budgetDemo)
    displayTrimmingDemo(results.trimmingDemo)
    displayAPIResults(results.apiResults)
  }

  private def displayTokenAnalysis(analysis: TokenAnalysis): Unit = {
    logger.info(s"Token analysis: ${analysis.totalTokens} total tokens across ${analysis.messageCount} messages")
    logger.debug(s"Average tokens per message: ${analysis.averageTokensPerMessage}")
    logger.debug(s"Token breakdown: ${analysis.breakdown.prettyPrint()}")
  }

  private def displayBudgetDemo(budgetDemo: BudgetDemoResults): Unit = {
    logger.info(s"\n🎯 Automatic Budget Derivation for ${budgetDemo.modelName}")
    logger.info(f"   Context Window: ${budgetDemo.contextWindow}%,d tokens")
    logger.info(f"   Reserved for Completion: ${budgetDemo.reserveCompletion}%,d tokens")
    logger.info("   Calculated Budgets:")
    budgetDemo.budgets.foreach { case (name, budget) =>
      logger.info(f"     $name: ${budget}%,d tokens")
    }
    logger.info("")
  }

  private def displayAPIResults(results: APITestResults): Unit = {
    logger.info(s"API testing with model ${results.modelName}, predicted ${results.predictedTokens} tokens")

    results match {
      case APITestResults(_, _, true, Some(completion), None) =>
        displaySuccessResults(completion, results.predictedTokens, results.modelName)
      case APITestResults(_, _, false, None, Some(error)) =>
        displayErrorResults(error, results.predictedTokens, results.modelName)
      case _ =>
        logger.warn("Unexpected API results state")
    }
  }

  private def displaySuccessResults(completion: Completion, predictedTokens: Int, modelName: String): Unit = {
    logger.info(s"API call successful for model: $modelName")

    completion.usage.foreach { usage =>
      logger.info(
        f"API reported usage: ${usage.promptTokens}%,d prompt + ${usage.completionTokens}%,d completion = ${usage.totalTokens}%,d total tokens"
      )

      val accuracy = predictedTokens.toDouble / usage.promptTokens * 100
      logger.info(
        f"Token prediction accuracy: $accuracy%.1f%% (predicted: $predictedTokens, actual: ${usage.promptTokens})"
      )

      accuracy match {
        case a if a > 95 => logger.info("Excellent tokenizer accuracy achieved")
        case a if a > 85 => logger.info("Very good tokenizer accuracy achieved")
        case a if a > 70 => logger.info("Acceptable tokenizer accuracy achieved")
        case a if a > 50 => logger.warn("Fair tokenizer accuracy - expected for approximate tokenizers")
        case _           => logger.warn("Poor tokenizer accuracy - may indicate tokenizer mismatch")
      }

      // Log expectations for different providers
      modelName.toLowerCase match {
        case name if name.contains("anthropic") || name.contains("claude") =>
          logger.info("Note: Anthropic models use proprietary tokenizers, so 70-80% accuracy is expected")
        case _ => // No special note for other providers
      }
    }

    logger.debug(f"Response preview: ${completion.message.content.take(100)}...")
  }

  private def displayErrorResults(error: org.llm4s.error.LLMError, predictedTokens: Int, modelName: String): Unit = {
    logger.error(s"API call failed for model $modelName: ${error.message}")

    val lowerMessage = error.message.toLowerCase
    (lowerMessage.contains("token") || lowerMessage.contains("length")) match {
      case true =>
        logger.warn(s"Token limit error detected! Predicted $predictedTokens tokens for model $modelName")
        logger.info("This validates that our token counting successfully detected the issue")
      case false => // Not a token-related error
    }
  }

  private def createLongConversation(): Conversation = {
    val systemPrompt = SystemMessage(
      "You are a helpful AI assistant. Please provide detailed, comprehensive responses to all questions. " +
        "Include specific examples, technical details, and practical applications where relevant."
    )

    val conversationPairs = (1 to 12).flatMap(createConversationPair)
    val finalUserMessage = UserMessage(
      "Given everything we've discussed about all these topics, what are the key insights, " +
        "common patterns, and most important takeaways? Please provide a comprehensive summary " +
        "that synthesizes all the information we've covered."
    )

    val conversation = Conversation(systemPrompt +: (conversationPairs :+ finalUserMessage))
    logger.debug(s"Created test conversation with ${conversation.messages.length} messages")
    conversation
  }

  private def createConversationPair(topic: Int): Seq[Message] =
    Seq(
      UserMessage(createLongUserMessage(topic)),
      AssistantMessage(createLongAssistantResponse(topic))
    )

  private def createLongUserMessage(topic: Int): String =
    s"Please provide a comprehensive analysis of topic $topic. I need detailed information covering: " +
      s"1) Historical background and context, 2) Current applications and use cases, " +
      s"3) Technical specifications and implementation details, 4) Benefits and advantages, " +
      s"5) Challenges and limitations, 6) Future prospects and emerging trends, " +
      s"7) Best practices and recommendations, 8) Real-world examples and case studies. " +
      "Please be thorough and include specific details, statistics, and expert insights where available."

  private def createLongAssistantResponse(topic: Int): String =
    s"Thank you for your comprehensive question about topic $topic. I'll provide a detailed analysis covering all the aspects you mentioned. " +
      s"Starting with the historical background: Topic $topic has evolved significantly over the past decades, with key developments including various technological advances, regulatory changes, and market shifts. " +
      s"In terms of current applications, we see widespread adoption across multiple industries and sectors, with specific use cases ranging from enterprise solutions to consumer applications. " +
      s"The technical specifications involve complex systems and architectures that require careful consideration of performance, scalability, security, and maintainability factors. " +
      s"Key benefits include improved efficiency, cost reduction, enhanced user experience, and competitive advantages. However, challenges include implementation complexity, integration issues, skill requirements, and ongoing maintenance needs. " +
      s"Looking ahead, future trends point toward continued innovation, emerging technologies, evolving standards, and new market opportunities that will shape the landscape of topic $topic." * 2

  private def demonstrateTokenWindowTrimming(
    tokenCounter: ConversationTokenCounter,
    conversation: Conversation
  ): TrimmingDemoResults = {
    val originalTokens = tokenCounter.countConversation(conversation)
    logger.info(s"Original conversation: $originalTokens tokens, ${conversation.messages.length} messages")

    // Test different budget scenarios
    val budgets = Seq(originalTokens / 4, originalTokens / 2, originalTokens * 3 / 4)

    val trimResults = budgets.map { budget =>
      TokenWindow.trimToBudget(conversation, tokenCounter, budget) match {
        case Right(conversationWindow) =>
          logger.info(f"Budget: $budget%,d, Result: ${conversationWindow.usage.summary}")
          Some(TrimResult(budget, conversationWindow))
        case Left(error) =>
          logger.error(s"Trimming failed for budget $budget: $error")
          None
      }
    }

    TrimmingDemoResults(originalTokens, conversation.messages.length, trimResults.flatten)
  }

  private def displayTrimmingDemo(results: TrimmingDemoResults): Unit = {
    logger.info(s"=== Token Window Trimming Demo ===")
    logger.info(s"Original: ${results.originalTokens} tokens, ${results.originalMessageCount} messages")

    results.trimResults.foreach { result =>
      val window = result.conversationWindow
      logger.info(s"Budget: ${result.budget}")
      logger.info(
        s"  Result: ${window.usage.summary}, Trimmed: ${window.wasTrimmed}, Removed: ${window.removedMessageCount} messages"
      )

      window.wasTrimmed match {
        case true =>
          val savedTokens = results.originalTokens - window.usage.currentTokens
          val savedPct    = (savedTokens.toDouble / results.originalTokens * 100).round.toInt
          logger.info(s"  Saved: $savedTokens tokens ($savedPct%)")
        case false =>
          logger.info("  No trimming needed")
      }
    }
  }

  case class DemoResults(
    tokenAnalysis: TokenAnalysis,
    apiResults: APITestResults,
    trimmingDemo: TrimmingDemoResults,
    budgetDemo: BudgetDemoResults
  )

  case class TokenAnalysis(
    totalTokens: Int,
    messageCount: Int,
    averageTokensPerMessage: Int,
    breakdown: org.llm4s.context.TokenBreakdown
  )

  case class APITestResults(
    predictedTokens: Int,
    modelName: String,
    success: Boolean,
    completion: Option[Completion],
    error: Option[org.llm4s.error.LLMError]
  )

  object APITestResults {
    def success(predictedTokens: Int, completion: Completion, modelName: String): APITestResults =
      APITestResults(predictedTokens, modelName, true, Some(completion), None)

    def failure(predictedTokens: Int, error: org.llm4s.error.LLMError, modelName: String): APITestResults =
      APITestResults(predictedTokens, modelName, false, None, Some(error))
  }

  case class TrimmingDemoResults(
    originalTokens: Int,
    originalMessageCount: Int,
    trimResults: Seq[TrimResult]
  )

  case class TrimResult(
    budget: TokenBudget,
    conversationWindow: ConversationWindow
  )

  case class BudgetDemoResults(
    modelName: String,
    contextWindow: Int,
    reserveCompletion: Int,
    budgets: Map[String, TokenBudget]
  )

  private def demonstrateAutomaticBudgetDerivation(
    client: org.llm4s.llmconnect.LLMClient,
    modelName: String
  ): BudgetDemoResults = {
    logger.info("=== Demonstrating Automatic Budget Derivation ===")

    val contextWindow     = client.getContextWindow()
    val reserveCompletion = client.getReserveCompletion()

    val budgets = Map(
      "Standard (8% headroom)"      -> client.getContextBudget(HeadroomPercent.Standard),
      "Light (5% headroom)"         -> client.getContextBudget(HeadroomPercent.Light),
      "Conservative (15% headroom)" -> client.getContextBudget(HeadroomPercent.Conservative)
    )

    logger.info(s"Model: $modelName")
    logger.info(s"Context Window: $contextWindow tokens")
    logger.info(s"Reserve for Completion: $reserveCompletion tokens")
    budgets.foreach { case (name, budget) =>
      logger.info(s"  $name: $budget tokens")
    }

    BudgetDemoResults(modelName, contextWindow, reserveCompletion, budgets)
  }
}
