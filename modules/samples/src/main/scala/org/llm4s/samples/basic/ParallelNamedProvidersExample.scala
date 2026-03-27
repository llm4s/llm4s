package samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * Demonstrates sending the same prompt to a list of named providers in parallel.
 *
 * Usage:
 *   sbt "samples/runMain samples.basic.ParallelNamedProvidersExample"
 */
object ParallelNamedProvidersExample:
  private given ExecutionContext = ExecutionContext.global

  def main(args: Array[String]): Unit =
    val prompt = "Reply in one short sentence: who are you?"

    val providerNames = List(
      "openai-main",
      "anthropic-main",
      "gemini-main",
      "deepseek-main",
      "mistral-main",
      "ollama-local"
    )

    val logger       = LoggerFactory.getLogger("samples.basic.ParallelNamedProvidersExample")
    val conversation = Conversation(Seq(UserMessage(prompt)))

    logger.info("=== Parallel Named Providers Example ===")
    logger.info("Prompt: {}", prompt)
    logger.info("Provider names: {}", providerNames.mkString(", "))

    val resultsFuture: Future[Seq[String]] =
      Future.traverse(providerNames): providerName =>
        runProvider(providerName, conversation)
          .map(result => formatProviderBlock(providerName, result))
          .recover { case throwable =>
            formatProviderBlock(
              providerName,
              Left(org.llm4s.error.UnknownError(s"Unexpected failure: ${throwable.getMessage}", throwable))
            )
          }

    val blocks = Await.result(resultsFuture, 5.minutes)
    blocks.foreach(logger.info(_))

  private def runProvider(
    providerName: String,
    conversation: Conversation
  ): Future[Result[(org.llm4s.llmconnect.config.ProviderConfig, Completion)]] =
    Future:
      for
        providerCfg <- Llm4sConfig.provider(providerName)
        client      <- LLMConnect.getClient(providerCfg)
        completion  <- client.complete(conversation)
      yield (providerCfg, completion)

  private def formatProviderBlock(
    providerName: String,
    result: Result[(org.llm4s.llmconnect.config.ProviderConfig, Completion)]
  ): String =
    result.fold(
      err => s"""
           |
           |=== $providerName ===
           |Status: FAILED
           |Error: ${err.formatted}
           |""".stripMargin.trim,
      { case (cfg, completion) =>
        val usageLine =
          completion.usage
            .map(usage =>
              s"Tokens used: ${usage.totalTokens} (${usage.promptTokens} prompt + ${usage.completionTokens} completion)"
            )
            .getOrElse("Tokens used: unavailable")

        s"""
           |
           |=== $providerName ===
           |Status: SUCCESS
           |Configured model: ${cfg.model}
           |Completion model: ${completion.model}
           |Response: ${completion.message.content}
           |$usageLine
           |""".stripMargin.trim
      }
    )
