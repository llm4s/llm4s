package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model.{ Conversation, SystemMessage, UserMessage }
import org.slf4j.LoggerFactory

/**
 * Small diagnostic sample for validating provider credentials outside the dashboard demo.
 *
 * This sample intentionally keeps the prompt fixed and loads everything else
 * from the standard llm4s configuration stack, including `application.local.conf`.
 *
 * Typical use:
 *   1. Put provider/model credentials in `modules/samples/src/main/resources/application.local.conf`
 *   2. Run:
 *      `sbt "samples/runMain org.llm4s.samples.basic.ProviderKeyValidationMain"`
 *
 * A successful run tells us that config loading, client creation, authentication,
 * and a basic completion request all work independently of the dashboard.
 */

@main
def ProviderKeyValidationMain(): Unit =
  val logger = LoggerFactory.getLogger("ProviderKeyValidationMain")

  val validationPrompt =
    "Reply with exactly: CONNECTED"

  def logResolvedConfig(providerCfg: ProviderConfig): Unit = {
    val providerType = providerCfg.getClass.getSimpleName.stripSuffix("$")
    logger.info("=== Provider Key Validation ===")
    logger.info("Resolved provider type: {}", providerType)
    logger.info("Resolved model: {}", providerCfg.model)
    logger.info("Context window: {}, reserveCompletion: {}", providerCfg.contextWindow, providerCfg.reserveCompletion)
    logger.info("Sending fixed validation prompt...")
  }

  val conversation = Conversation(
    Seq(
      SystemMessage("You are a concise assistant helping validate API connectivity."),
      UserMessage(validationPrompt)
    )
  )

  val result = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)
    _ = logResolvedConfig(providerCfg)
    completion <- client.complete(conversation)
    _ = {
      logger.info("Validation call succeeded.")
      logger.info("Completion model: {}", completion.model)
      logger.info("Response: {}", completion.message.content.trim)
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
      logger.error("Provider key validation failed.")
      logger.error("{}", err.formatted)
      logger.info(
        "Check the provider settings resolved from application.local.conf, application.conf, env vars, or -D overrides."
      )
    },
    identity
  )
