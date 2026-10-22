import scala.concurrent.duration._

import org.apache.spark.sql.SparkSession
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{LLMClient, LLMConnect}
import org.llm4s.llmconnect.model.{CompletionOptions, Conversation, SystemMessage, UserMessage}
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.reliability.{RateLimitConfig, ReliabilityConfig, ReliableClient, RetryPolicy}

final case class Review(id: Int, text: String)

/** A review with the model's answer, or the error that replaced it: a failed call is data, not a failed stage. */
final case class EnrichedReview(id: Int, text: String, answer: Option[String], error: Option[String])

object EnrichReviews {

  /**
   * One client per partition, built on the executor: an LLMClient is not serialisable, and building one per row
   * would repeat the configuration load and the HTTP client setup for every call.
   */
  private def clientForPartition(): Either[String, LLMClient] = {
    val registry = ProviderRegistry.default
    val client = for {
      models <- Llm4sConfig.modelRegistryService()
      config <- Llm4sConfig.defaultProvider()(registry)
      client <- LLMConnect.getClient(config)(models, registry)
    } yield client
    client.left.map(_.message).map { underlying =>
      // Retries, a deadline and a local rate limit. The rate limit is per client, so per partition: the job's
      // total is roughly requestsPerMinute times the number of partitions running at once.
      new ReliableClient(
        underlying,
        "spark-example",
        ReliabilityConfig(
          retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 2, delay = 50.millis),
          rateLimit = RateLimitConfig(enabled = true, requestsPerMinute = 600, burstCapacity = 10),
          deadline = Some(30.seconds)
        )
      )
    }
  }

  def enrich(reviews: Iterator[Review]): Iterator[EnrichedReview] =
    clientForPartition() match {
      case Left(error) =>
        // No client for this partition (a missing key, a missing provider module): every row says why.
        reviews.map(r => EnrichedReview(r.id, r.text, None, Some(s"no client: $error")))
      case Right(client) =>
        try
          reviews.map { review =>
            val conversation = Conversation(Seq(SystemMessage("Answer in one short line."), UserMessage(s"Summarise:\n${review.text}")))
            client.complete(conversation, CompletionOptions().withMaxTokens(64)) match {
              case Right(completion) => EnrichedReview(review.id, review.text, Some(completion.content), None)
              case Left(error)       => EnrichedReview(review.id, review.text, None, Some(error.message))
            }
          }.toList.iterator // finish every call before the client is closed below
        finally client.close()
    }

  def main(args: Array[String]): Unit = {
    // `--fake` points the default provider at a local fake Ollama; without it, the job uses the provider your
    // application.conf names (llm4s.providers.provider).
    val fake = if (args.contains("--fake")) Some(FakeOllama.start()) else None
    fake.foreach { server =>
      System.setProperty("llm4s.providers.provider", "local")
      System.setProperty("llm4s.providers.local.provider", "ollama")
      System.setProperty("llm4s.providers.local.model", "llama3")
      System.setProperty("llm4s.providers.local.baseUrl", s"http://localhost:${server.getAddress.getPort}")
    }

    val spark = SparkSession.builder().appName("llm4s-enrich-reviews").master("local[2]").getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    import spark.implicits._

    try {
      val reviews = Seq(
        Review(1, "The battery lasts two days"),
        Review(2, "Screen cracked in a week"),
        Review(3, "boom: this one makes the fake model fail"),
        Review(4, "Fast delivery, works as described")
      ).toDS().repartition(2)

      val enriched = reviews.mapPartitions(enrich).orderBy("id")
      enriched.show(truncate = false)
      val failed = enriched.filter($"error".isNotNull).count()
      println(s"${enriched.count()} rows, $failed with an error")
    } finally {
      spark.stop()
      fake.foreach(_.stop(0))
    }
  }
}
