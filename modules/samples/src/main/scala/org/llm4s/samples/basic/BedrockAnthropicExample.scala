package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

/**
 * Demonstrates using Anthropic Claude models via AWS Bedrock.
 *
 * Authentication uses the AWS Default Credential Provider Chain — no explicit
 * API key is needed. Credentials are resolved from (in order):
 *  1. Environment variables: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_SESSION_TOKEN
 *  2. ~/.aws/credentials profile
 *  3. IAM instance role (EC2/ECS/Lambda)
 *
 * To run this example:
 * {{{
 * # Ensure AWS credentials are available, then:
 * export LLM4S_PROVIDER=bedrock-anthropic-main
 *
 * # Optionally override the model (default: anthropic.claude-3-5-sonnet-20241022-v2:0)
 * export BEDROCK_MODEL=anthropic.claude-3-5-sonnet-20241022-v2:0
 *
 * # Optionally override the region (default: us-east-1)
 * export AWS_REGION=us-west-2
 *
 * sbt "samples/runMain org.llm4s.samples.basic.BedrockAnthropicExample"
 * }}}
 */
object BedrockAnthropicExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a concise assistant."),
        UserMessage("Explain the difference between val and var in Scala in two sentences.")
      )
    )

    val result = for {
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client     <- LLMConnect.getClient(providerCfg)
      completion <- client.complete(conversation, CompletionOptions())
      _ = {
        logger.info("Provider: Bedrock Anthropic")
        logger.info("Model: {}", completion.model)
        logger.info("--- Response ---")
        logger.info("{}", completion.message.content)
        logger.info("--- End Response ---")
        completion.usage.foreach { usage =>
          logger.info(
            "Tokens used: {} ({} prompt + {} completion)",
            usage.totalTokens,
            usage.promptTokens,
            usage.completionTokens
          )
        }
      }
    } yield ()

    result.fold(
      err => {
        logger.error("Error: {}", err.formatted)
        logger.info("Tip: Ensure AWS credentials are configured (env vars, ~/.aws/credentials, or IAM role)")
        logger.info("Tip: Set LLM4S_PROVIDER=bedrock-anthropic-main in your environment")
      },
      identity
    )
  }
}
