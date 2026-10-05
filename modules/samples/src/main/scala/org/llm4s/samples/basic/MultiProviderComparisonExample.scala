package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.util.Using

/**
 * The same prompt against several providers, side by side: what each one answered, how many tokens it
 * reported, and how long the call took.
 *
 * It is the multi-provider point of LLM4S in one screen: `Llm4sConfig.provider(name)` and `LLMConnect.getClient`
 * are the same calls whichever vendor sits behind the name.
 *
 * Providers are '''named sections''' of your configuration, not model coordinates - `Llm4sConfig.provider` takes
 * the name of a section under `llm4s.providers`, never a `"provider/model"` string. Define the ones you want to
 * compare in `application.local.conf` next to the samples' `application.conf`:
 *
 * {{{
 * llm4s {
 *   providers {
 *     openai-main    { provider = "openai",    model = "gpt-4o-mini" }
 *     anthropic-main { provider = "anthropic", model = "claude-haiku-4-5-latest" }
 *     gemini-main    { provider = "gemini",    model = "gemini-2.0-flash" }
 *   }
 * }
 * }}}
 *
 * Each section's key comes from the vendor's usual variable (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`,
 * `GOOGLE_API_KEY`), so a section needs only `provider` and `model`. A provider that is not configured, or has
 * no key, is reported with the reason and the others still run.
 *
 * {{{
 * sbt "samples/runMain org.llm4s.samples.basic.MultiProviderComparisonExample"
 * sbt "samples/runMain org.llm4s.samples.basic.MultiProviderComparisonExample openai-main ollama-local"
 * }}}
 *
 * Arguments name the sections to compare; with none, the three above.
 */
object MultiProviderComparisonExample {

  private val logger = LoggerFactory.getLogger(getClass)

  val DefaultProviders: Seq[String] = Seq("openai-main", "anthropic-main", "gemini-main")
  val DefaultPrompt: String         = "Explain what makes Scala good for building AI applications in 2 sentences."

  /**
   * What one provider answered.
   *
   * @param text what the model said, trimmed
   * @param tokens the total tokens the provider reported, when it reports usage
   * @param latency the time of the `complete` call alone: building the client is not part of it
   */
  final case class Reply(text: String, tokens: Option[Int], latency: FiniteDuration)

  /** One provider's outcome: a reply, or the error that stopped it (not configured, no key, the call failed). */
  final case class Entry(provider: String, outcome: Result[Reply])

  def main(args: Array[String]): Unit =
    Llm4sConfig.modelRegistryService() match {
      case Left(error) =>
        logger.error(s"Cannot load the model registry: ${error.message}")
      case Right(registry) =>
        given ModelRegistryService = registry
        val names                  = if (args.nonEmpty) args.toSeq else DefaultProviders
        render(compare(names, DefaultPrompt)(name => Llm4sConfig.provider(name)), DefaultPrompt).foreach(logger.info(_))
    }

  /**
   * Ask every named provider the same prompt, one after another. A provider that cannot be loaded or fails
   * does not stop the rest.
   *
   * @param load resolves a name to its configuration; the sample passes `Llm4sConfig.provider`
   */
  def compare(names: Seq[String], prompt: String)(
    load: String => Result[ProviderConfig]
  )(using ModelRegistryService): Seq[Entry] =
    names.map(name => Entry(name, load(name).flatMap(ask(_, prompt))))

  private def ask(config: ProviderConfig, prompt: String)(using ModelRegistryService): Result[Reply] =
    LLMConnect.getClient(config).flatMap { client =>
      Using.resource(client) { c =>
        val start = System.nanoTime()
        c.complete(Conversation(Seq(UserMessage(prompt)))).map { completion =>
          Reply(completion.asText.trim, completion.usage.map(_.totalTokens), (System.nanoTime() - start).nanos)
        }
      }
    }

  /** The report: the prompt, one block per provider, and how many answered. */
  def render(entries: Seq[Entry], prompt: String): Seq[String] = {
    val rule = "=" * 60
    val blocks = entries.flatMap { entry =>
      val lines = entry.outcome match {
        case Right(reply) =>
          Seq(
            reply.text,
            s"  tokens: ${reply.tokens.fold("not reported")(_.toString)}, latency: ${reply.latency.toMillis}ms"
          )
        case Left(error) =>
          Seq(s"  FAILED: ${error.message}")
      }
      s"-- ${entry.provider}" +: lines
    }
    val answered = entries.count(_.outcome.isRight)
    Seq(rule, s"Prompt: $prompt", rule) ++ blocks ++ Seq(rule, s"$answered of ${entries.size} providers answered")
  }
}
