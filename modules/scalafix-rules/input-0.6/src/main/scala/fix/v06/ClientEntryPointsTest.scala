/*
rule = ClientEntryPoints
*/
package fix.v06

import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

object ClientEntryPointsTest {
  def plain(config: ProviderConfig)(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    LLMConnect.fromConfig(config)

  def measured(config: ProviderConfig, metrics: MetricsCollector)(using
    ModelRegistryService,
    ProviderRegistry
  ): Result[LLMClient] =
    LLMConnect.getClient(config, metrics)

  def named(provider: ProviderId, config: ProviderConfig, metrics: MetricsCollector)(using
    ModelRegistryService,
    ProviderRegistry
  ): Seq[Result[LLMClient]] =
    Seq(
      LLMConnect.getClient(provider, config),
      LLMConnect.getClient(provider, config, metrics),
      LLMConnect.fromProvider(provider, config)
    )

  def printed(client: LLMClient, conversation: Conversation, options: CompletionOptions): Result[Completion] =
    client.streamComplete(conversation, options, chunk => chunk.content.foreach(print))

  def defaulted(client: LLMClient, conversation: Conversation): Result[Completion] =
    client.streamComplete(conversation, onChunk = chunk => chunk.content.foreach(print))

  def block(client: LLMClient, conversation: Conversation, options: CompletionOptions): Result[Completion] =
    client.streamComplete(
      conversation,
      options,
      { chunk =>
        chunk.content.foreach(print)
      }
    )

  abstract class Counting(underlying: LLMClient) extends LLMClient {
    var chunks = 0

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      underlying.streamComplete(conversation, options, chunk => { chunks += 1; onChunk(chunk) })
  }
}
