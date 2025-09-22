package org.llm4s.samples.context

import org.llm4s.config.ConfigReader
import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.context.{ ConversationTokenCounter, LLMCompressor }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Demonstrates LLMCompressor.squeezeDigest functionality in isolation.
 *
 * Purpose: Show how the LLM squeezes [HISTORY_SUMMARY] digests down to a target cap.
 * Inputs: Synthetic digest messages (technical, business, troubleshooting) + a few recent messages.
 * Knobs: capTokens (e.g., 200, 400, 600). Configure model and API key via env.
 *
 * Expected behavior:
 *   • If cap < digest size → LLM rewrites the digest, reducing tokens while preserving key facts.
 *   • If cap ≥ digest size → No squeeze needed (digest unchanged).
 *   • Dense prose (e.g., BusinessAnalysis) may only compress modestly; structured logs compress more.
 *   • Only the digest is modified — recent messages stay untouched.
 *
 * Output:
 *   • Digest tokens before → after, % reduction
 *   • Whether the digest is ≤ cap (cap achieved or not)
 *   • Short preview of squeezed digest
 *   • Summary analysis by scenario and by cap size
 *
 * To run this example:
 * ```bash
 * export LLM_MODEL=openai/gpt-4o (for example)
 * export OPENAI_API_KEY=sk-your-key-here
 * sbt "samples/runMain org.llm4s.samples.context.LlmDigestSqueezeExample"
 * ```
 */

object LlmDigestSqueezeExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting LLM Digest Squeeze Example")

    val result = for {
      config       <- getConfiguration()
      client       <- createClient(config._2)
      tokenCounter <- createTokenCounter(config._1)
      results      <- runSqueezeTests(client, tokenCounter)
    } yield results

    result.fold(
      error => logger.error(s"Example failed: $error"),
      results => {
        logger.info("Example completed successfully")
        displayResults(results)
      }
    )
  }

  private def getConfiguration(): Result[(String, ConfigReader)] =
    for {
      config <- LLMConfig()
      modelName = config.getOrElse("LLM_MODEL", "openai/gpt-4o")
      _         = logger.info(s"Using model: $modelName")
    } yield (modelName, config)

  private def createClient(config: ConfigReader): Result[org.llm4s.llmconnect.LLMClient] =
    LLMConnect.getClient(config).map { client =>
      logger.info("Created LLM client for digest squeezing")
      client
    }

  private def createTokenCounter(modelName: String): Result[ConversationTokenCounter] =
    ConversationTokenCounter.forModel(modelName).map { counter =>
      logger.info(s"Created token counter for: $modelName")
      counter
    }

  private def runSqueezeTests(
    client: org.llm4s.llmconnect.LLMClient,
    tokenCounter: ConversationTokenCounter
  ): Result[SqueezeResults] = {
    val testScenarios    = createTestScenarios()
    val capTokensOptions = Seq(200, 400, 600) // Different squeeze targets

    val allResults = for {
      (scenarioName, digestContent, recentMessages) <- testScenarios
      capTokens                                     <- capTokensOptions
    } yield {
      val testName = s"$scenarioName (cap=$capTokens)"
      testDigestSqueeze(testName, digestContent, recentMessages, capTokens, client, tokenCounter)
    }

    val successfulResults = allResults.collect { case Right(result) => result }
    Right(SqueezeResults(successfulResults))
  }

  private def createTestScenarios(): Seq[(String, String, Seq[Message])] = {
    val recentMessages = Seq(
      UserMessage("What's the current system status?"),
      AssistantMessage(
        "Based on the latest metrics, the system is performing well with minor optimization opportunities."
      ),
      UserMessage("Any recommendations for the next steps?")
    )

    Seq(
      ("TechnicalDiscussion", createTechnicalDigest(), recentMessages),
      ("BusinessAnalysis", createBusinessDigest(), recentMessages),
      ("TroubleshootingLog", createTroubleshootingDigest(), recentMessages)
    )
  }

  private def createTechnicalDigest(): String =
    """[HISTORY_SUMMARY]
Technical architecture discussion covering distributed microservices implementation for e-commerce platform. 
Key topics included service boundary design with 10 core services (User, Product, Order, Payment, Inventory, 
Shipping, Notification, Review, Recommendation, Analytics), each owning its data with well-defined APIs. 
Data consistency patterns addressed strong consistency for payments and inventory, eventual consistency for 
preferences and analytics, using Saga pattern for distributed transactions with specific order creation flow: 
reserve inventory → process payment → create shipping → send notifications. Communication protocols covered 
hybrid approach using REST/gRPC for synchronous user-facing operations and Apache Kafka/RabbitMQ for 
asynchronous event-driven workflows, with API Gateway providing single entry point for routing, 
authentication, and rate limiting. Service mesh discussion included Istio/Linkerd for service-to-service 
communication, security, and observability. Monitoring strategy outlined three pillars approach with 
Prometheus/Grafana for metrics collection including Golden Signals (latency, traffic, errors, saturation), 
RED method (rate, errors, duration), and USE method (utilization, saturation, errors) with multi-tier 
alerting strategy preventing alert fatigue through proper thresholds and escalation policies. Distributed 
tracing implementation using Jaeger/Zipkin with OpenTelemetry standardized instrumentation, trace sampling 
for performance balance, and service dependency mapping. ELK stack for centralized logging with structured 
JSON format, correlation IDs for cross-service log linking, and proper retention policies."""

  private def createBusinessDigest(): String =
    """[HISTORY_SUMMARY]
Business requirements analysis and implementation strategy discussion. Initial requirement gathering covered 
high-traffic e-commerce platform needs with distributed architecture supporting multiple geographic regions 
for global customer base. Scalability requirements specified handling peak loads during holiday seasons with 
auto-scaling capabilities at multiple levels including horizontal pod autoscaling, vertical pod autoscaling, 
and cluster autoscaling. Availability targets set at 99.99% uptime with disaster recovery requirements 
including multi-region active-passive setup, automated failover mechanisms, and comprehensive backup strategies. 
Security compliance requirements addressed PCI DSS for payment processing, GDPR for European customer data, 
and SOC 2 for service organization controls. Performance requirements specified sub-200ms API response times 
for critical user-facing operations, database query optimization for large product catalogs, and CDN integration 
for global content delivery. Business continuity planning covered incident response procedures, communication 
templates for stakeholder updates, and testing schedules for disaster recovery drills. Cost optimization 
strategies discussed including spot instance utilization with on-demand fallbacks, resource right-sizing 
based on actual usage patterns, and automated scaling policies to minimize infrastructure costs while 
maintaining performance standards. Integration requirements covered third-party payment processors, 
inventory management systems, shipping providers, and marketing analytics platforms."""

  private def createTroubleshootingDigest(): String =
    """[HISTORY_SUMMARY]
Production system troubleshooting and performance analysis session. Initial investigation triggered by 
elevated error rates and increased response times across multiple microservices during peak traffic period. 
System metrics analysis revealed CPU utilization spikes averaging 85% across 15 production servers with 
memory usage reaching 75-90% on most instances. Network throughput showed unusual patterns with 15.7 Gbps 
total cluster throughput indicating potential bottlenecks. Database performance investigation identified 
connection pool exhaustion with 70% utilization of maximum 100 connections, leading to timeout errors. 
Query analysis revealed 23 slow queries over 1 second, 7 queries over 5 seconds, and 2 queries exceeding 
10 seconds execution time. Most expensive query identified as complex JOIN operation on orders and order_items 
tables without proper indexing on created_at column. Error log analysis showed 100 critical events over 
2-hour period with database connection timeouts after 10-30 seconds, memory pressure warnings, and pool 
exhaustion messages. Affected services included order-service, payment-service, inventory-service, 
user-service, and notification-service. Immediate remediation actions implemented including connection pool 
scaling from 100 to 200 maximum connections, memory allocation increases for high-usage containers, and 
emergency index creation on frequently queried columns. Long-term optimization recommendations included 
database query optimization, connection pooling configuration review, and implementation of caching layers 
to reduce database load during peak traffic periods."""

  private def testDigestSqueeze(
    testName: String,
    originalDigestContent: String,
    recentMessages: Seq[Message],
    capTokens: Int,
    client: org.llm4s.llmconnect.LLMClient,
    tokenCounter: ConversationTokenCounter
  ): Result[SqueezeResult] = {
    // Create the conversation with the original digest + recent messages
    val originalDigest     = SystemMessage(originalDigestContent)
    val messagesWithDigest = originalDigest +: recentMessages

    val originalDigestTokens = tokenCounter.countMessage(originalDigest)
    val totalTokensBefore    = messagesWithDigest.map(tokenCounter.countMessage).sum

    logger.info(s"Testing '$testName': digest=$originalDigestTokens tokens, cap=$capTokens tokens")

    LLMCompressor.squeezeDigest(messagesWithDigest, tokenCounter, client, capTokens) match {
      case Right(squeezedMessages) =>
        val squeezedDigestOpt    = squeezedMessages.find(_.content.contains("[HISTORY_SUMMARY]"))
        val squeezedDigestTokens = squeezedDigestOpt.map(tokenCounter.countMessage).getOrElse(0)
        val totalTokensAfter     = squeezedMessages.map(tokenCounter.countMessage).sum

        val digestReductionPercent = originalDigestTokens match {
          case 0      => 0
          case tokens => ((tokens - squeezedDigestTokens).toDouble / tokens * 100).toInt
        }

        val capAchieved   = squeezedDigestTokens <= capTokens
        val digestPreview = squeezedDigestOpt.map(_.content.take(120) + "...").getOrElse("No digest found")

        logger.info(
          s"'$testName': digest $originalDigestTokens → $squeezedDigestTokens tokens ($digestReductionPercent% reduction), cap achieved: $capAchieved"
        )

        Right(
          SqueezeResult(
            testName = testName,
            originalDigestTokens = originalDigestTokens,
            squeezedDigestTokens = squeezedDigestTokens,
            totalTokensBefore = totalTokensBefore,
            totalTokensAfter = totalTokensAfter,
            digestReductionPercent = digestReductionPercent,
            capAchieved = capAchieved,
            digestPreview = digestPreview
          )
        )

      case Left(error) =>
        logger.warn(s"Digest squeeze failed for '$testName': $error")
        Right(
          SqueezeResult(
            testName = testName,
            originalDigestTokens = originalDigestTokens,
            squeezedDigestTokens = originalDigestTokens, // No reduction on failure
            totalTokensBefore = totalTokensBefore,
            totalTokensAfter = totalTokensBefore,
            digestReductionPercent = 0,
            capAchieved = false,
            digestPreview = "Squeeze failed: " + error.toString.take(100) + "..."
          )
        )
    }
  }

  private def displayResults(results: SqueezeResults): Unit = {
    logger.info("\n🤖 LLM Digest Squeeze Example Results")
    logger.info("=" * 60)

    results.results.foreach { result =>
      val capStatus = result.capAchieved match {
        case true  => "✅ Cap achieved"
        case false => "⚠️ Cap not achieved"
      }

      logger.info(f"\n📊 ${result.testName}")
      logger.info(
        f"   Digest compression: ${result.originalDigestTokens} → ${result.squeezedDigestTokens} tokens (${result.digestReductionPercent}%% reduction)"
      )
      logger.info(f"   Total conversation: ${result.totalTokensBefore} → ${result.totalTokensAfter} tokens")
      logger.info(f"   $capStatus")
      logger.info(f"   Digest preview: ${result.digestPreview}")
    }

    // Group by scenario for better analysis
    val byScenario = results.results.groupBy(_.testName.split(" \\(cap=").head)

    logger.info("\n📈 Analysis by Scenario:")
    byScenario.foreach { case (scenarioName, scenarioResults) =>
      val avgDigestReduction = scenarioResults.map(_.digestReductionPercent).sum.toDouble / scenarioResults.length
      val successRate        = scenarioResults.count(_.capAchieved).toDouble / scenarioResults.length * 100
      logger.info(
        f"   • $scenarioName: ${avgDigestReduction}%.1f%% avg digest reduction, ${successRate}%.1f%% cap success rate"
      )
    }

    // Group by cap size for analysis
    val byCap = results.results.groupBy(r => r.testName.split("cap=").last.split("\\)").head.toInt)

    logger.info("\n📊 Analysis by Cap Size:")
    byCap.toSeq.sortBy(_._1).foreach { case (capSize, capResults) =>
      val avgDigestReduction = capResults.map(_.digestReductionPercent).sum.toDouble / capResults.length
      val successRate        = capResults.count(_.capAchieved).toDouble / capResults.length * 100
      logger.info(
        f"   • Cap $capSize tokens: ${avgDigestReduction}%.1f%% avg digest reduction, ${successRate}%.1f%% success rate"
      )
    }

    logger.info("\n💡 Key Observations:")
    logger.info("   • Focus is on digest-only compression (not full conversation)")
    logger.info("   • LLM intelligently preserves important technical details")
    logger.info("   • Success depends on content density and target cap size")
    logger.info("   • Recent messages remain untouched (only digest is compressed)")
    logger.info("   • Smaller caps require more aggressive compression")
  }

  case class SqueezeResults(results: Seq[SqueezeResult])

  case class SqueezeResult(
    testName: String,
    originalDigestTokens: Int,
    squeezedDigestTokens: Int,
    totalTokensBefore: Int,
    totalTokensAfter: Int,
    digestReductionPercent: Int,
    capAchieved: Boolean,
    digestPreview: String
  )
}
