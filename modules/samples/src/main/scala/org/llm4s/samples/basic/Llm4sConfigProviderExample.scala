package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

/**
 * Minimal example showing how to bootstrap an LLM client using PureConfig without any legacy reader.
 *
 * It:
 *  - Reads typed ProviderConfig via Llm4sConfig.provider()
 *  - Builds an LLMConnect client from that typed config
 *  - Prints the selected model and provider details
 *
 * To run (after setting LLM_MODEL and provider API keys):
 *   sbt "samples/runMain org.llm4s.samples.basic.Llm4sConfigProviderExample"
 */
object Llm4sConfigProviderExample {
  def main(args: Array[String]): Unit = {
    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
    } yield (providerCfg, client)

    result.fold(
      err => System.err.println(s"[PureConfigProviderExample] Failed to create client: ${err.formatted}"),
      { case (cfg, _) =>
        println("=== PureConfig Provider Example ===")
        println(s"Provider model: ${cfg.model}")
        println(s"Context window: ${cfg.contextWindow}, reserveCompletion: ${cfg.reserveCompletion}")
      }
    )
  }
}
