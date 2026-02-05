package org.llm4s.samples.metrics

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
// import org.llm4s.metrics.{ MetricsCollector, PrometheusEndpoint }
import org.slf4j.LoggerFactory

/**
 * Example demonstrating Prometheus metrics collection for LLM operations.
 *
 * This example shows:
 * - Starting a Prometheus HTTP server to expose metrics
 * - Making LLM API calls that automatically record metrics
 * - Accessing the metrics endpoint
 * - Viewing collected metrics in Prometheus format
 *
 * == Quick Start ==
 *
 * 1. Set your provider and model:
 *    {{{
 *    export LLM_MODEL=openai/gpt-4o
 *    }}}
 *
 * 2. Set your API key:
 *    {{{
 *    export OPENAI_API_KEY=sk-...
 *    }}}
 *
 * 3. Run the example:
 *    {{{
 *    sbt "samples/runMain org.llm4s.samples.metrics.PrometheusMetricsExample"
 *    }}}
 *
 * 4. View metrics:
 *    {{{
 *    curl http://localhost:9090/metrics
 *    }}}
 *
 * == Metrics Collected ==
 *
 * - '''llm4s_requests_total''': Total number of LLM requests (by provider, model, status)
 * - '''llm4s_tokens_total''': Total tokens used (by provider, model, type: input/output)
 * - '''llm4s_cost_usd_total''': Estimated cost in USD (by provider, model)
 * - '''llm4s_errors_total''': Total errors (by provider, error_type)
 * - '''llm4s_request_duration_seconds''': Request latency histogram (by provider, model)
 *
 * == Using with Prometheus ==
 *
 * Add this scrape config to your `prometheus.yml`:
 * {{{
 * scrape_configs:
 *   - job_name: 'llm4s'
 *     static_configs:
 *       - targets: ['localhost:9090']
 * }}}
 *
 * == Integration Patterns ==
 *
 * '''Option 1: From Configuration (recommended)'''
 * {{{
 * val (metrics, endpointOpt) = Llm4sConfig.metrics().toOption.get
 * val client = LLMConnect.getClient(config, metrics).toOption.get
 * // ... make LLM calls ...
 * endpointOpt.foreach(_.stop())
 * }}}
 *
 * '''Option 2: Noop Metrics (when disabled)'''
 * {{{
 * val metrics = MetricsCollector.noop
 * val client = LLMConnect.getClient(config, metrics).toOption.get
 * // ... make LLM calls (no metrics recorded) ...
 * }}}
 *
 * == Expected Output ==
 * The example will:
 * 1. Start metrics server on port 9090
 * 2. Make several LLM API calls (successful and failing)
 * 3. Display metrics endpoint URL
 * 4. Show sample metrics output
 * 5. Stop the metrics server
 *
 * For more information, see: https://github.com/llm4s/llm4s/docs/guide/observability
 */
object PrometheusMetricsExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    println("=" * 80)
    println("LLM4S Prometheus Metrics Example")
    println("=" * 80)
    println()

    // Load metrics configuration
    println("Loading metrics configuration...")
    val metricsConfigResult = Llm4sConfig.metrics()

    metricsConfigResult match {
      case Left(error) =>
        logger.error("Failed to load metrics configuration", error)
        println(s"ERROR: ${error.message}")
        sys.exit(1)

      case Right((metrics, endpointOpt)) =>
        endpointOpt match {
          case Some(endpoint) =>
            println(s"✓ Metrics server started successfully")
            println(s"✓ Metrics endpoint: ${endpoint.url}")
            println()
            println("You can view metrics by running:")
            println(s"  curl ${endpoint.url}")
            println()
          case None =>
            println("✓ Metrics loaded (HTTP server disabled)")
            println()
        }

        try {
          // Load provider configuration
          val configResult = Llm4sConfig.provider()

          configResult match {
            case Left(error) =>
              logger.error("Configuration error", error)
              println(s"ERROR: ${error.message}")
              println()
              println("Please set required environment variables:")
              println("  export LLM_MODEL=openai/gpt-4o")
              println("  export OPENAI_API_KEY=sk-...")
              sys.exit(1)

            case Right(config) =>
              val providerName = config match {
                case _: org.llm4s.llmconnect.config.OpenAIConfig    => "openai"
                case _: org.llm4s.llmconnect.config.AnthropicConfig => "anthropic"
                case _: org.llm4s.llmconnect.config.OllamaConfig    => "ollama"
                case _: org.llm4s.llmconnect.config.AzureConfig     => "azure"
                case _: org.llm4s.llmconnect.config.ZaiConfig       => "zai"
                case _: org.llm4s.llmconnect.config.GeminiConfig    => "gemini"
              }

              println(s"Using model: ${config.model}")
              println(s"Provider: $providerName")
              println()

              // Get LLM client with metrics injection
              LLMConnect.getClient(config, metrics) match {
                case Left(error) =>
                  logger.error("Failed to create LLM client", error)
                  println(s"ERROR: ${error.message}")
                  sys.exit(1)

                case Right(client) =>
                  println("Making LLM API calls (metrics will be recorded automatically)...")
                  println()

                  // Example 1: Successful request
                  println("1. Simple question (should succeed):")
                  val conversation1 = Conversation(
                    Seq(
                      UserMessage("What is 2+2?")
                    )
                  )

                  client.complete(conversation1) match {
                    case Right(completion) =>
                      println(s"   Response: ${completion.content.take(100)}...")
                      completion.usage.foreach { usage =>
                        println(s"   Tokens: ${usage.promptTokens} in, ${usage.completionTokens} out")
                      }
                      println("   ✓ Metrics recorded: success, tokens, duration")

                    case Left(error) =>
                      println(s"   ✗ Error: ${error.message}")
                      println("   ✓ Metrics recorded: error, duration")
                  }
                  println()

                  // Example 2: Another successful request
                  println("2. Follow-up question (should succeed):")
                  val conversation2 = Conversation(
                    Seq(
                      UserMessage("What is the capital of France?")
                    )
                  )

                  client.complete(conversation2) match {
                    case Right(completion) =>
                      println(s"   Response: ${completion.content.take(100)}...")
                      completion.usage.foreach { usage =>
                        println(s"   Tokens: ${usage.promptTokens} in, ${usage.completionTokens} out")
                      }
                      println("   ✓ Metrics recorded: success, tokens, duration")

                    case Left(error) =>
                      println(s"   ✗ Error: ${error.message}")
                      println("   ✓ Metrics recorded: error, duration")
                  }
                  println()

                  // Example 3: Large request (to show token tracking)
                  println("3. Larger request (to demonstrate token metrics):")
                  val conversation3 = Conversation(
                    Seq(
                      UserMessage("Write a short poem about Scala programming.")
                    )
                  )

                  client.complete(conversation3) match {
                    case Right(completion) =>
                      println(s"   Response: ${completion.content.take(100)}...")
                      completion.usage.foreach { usage =>
                        println(s"   Tokens: ${usage.promptTokens} in, ${usage.completionTokens} out")
                      }
                      println("   ✓ Metrics recorded: success, tokens, duration")

                    case Left(error) =>
                      println(s"   ✗ Error: ${error.message}")
                      println("   ✓ Metrics recorded: error, duration")
                  }
                  println()

                  // Close client
                  client.close()

                  // Display metrics summary
                  println("=" * 80)
                  println("Metrics Summary")
                  println("=" * 80)
                  println()
                  println("The following metrics have been recorded:")
                  println("  • llm4s_requests_total - Total number of requests")
                  println("  • llm4s_tokens_total - Total tokens consumed (input/output)")
                  println("  • llm4s_request_duration_seconds - Request latency distribution")
                  println()
                  endpointOpt.foreach(endpoint => println(s"View all metrics at: ${endpoint.url}"))
                  println()
                  println("Sample metrics output:")
                  println("-" * 80)
                  println("# HELP llm4s_requests_total Total number of LLM requests")
                  println("# TYPE llm4s_requests_total counter")
                  println(
                    s"llm4s_requests_total{provider=\"$providerName\",model=\"${config.model}\",status=\"success\"} 3.0"
                  )
                  println()
                  println("# HELP llm4s_tokens_total Total tokens used")
                  println("# TYPE llm4s_tokens_total counter")
                  println(
                    s"llm4s_tokens_total{provider=\"$providerName\",model=\"${config.model}\",type=\"input\"} X.0"
                  )
                  println(
                    s"llm4s_tokens_total{provider=\"$providerName\",model=\"${config.model}\",type=\"output\"} Y.0"
                  )
                  println()
                  println("# HELP llm4s_request_duration_seconds Request duration in seconds")
                  println("# TYPE llm4s_request_duration_seconds histogram")
                  println(
                    s"llm4s_request_duration_seconds_bucket{provider=\"$providerName\",model=\"${config.model}\",le=\"1.0\"} 3.0"
                  )
                  println("-" * 80)
                  println()
                  println("✓ Example completed successfully!")
              }
          }

        } finally
          // Clean up: stop metrics server if running
          endpointOpt.foreach { endpoint =>
            println()
            println("Stopping metrics server...")
            endpoint.stop()
            println("✓ Server stopped")
          }
    }

    println()
    println("=" * 80)
    println("Example finished. Metrics server has been stopped.")
    println("=" * 80)
  }
}
