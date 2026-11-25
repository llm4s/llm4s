/*
rules = [ModelIdNewtypes, CompletionFromMessage, AccessorRenames]
*/
package fix.v06

import java.time.Instant

import org.llm4s.llmconnect.config.{ ContextWindowResolver, EmbeddingModelConfig, ModelDimensionRegistry, OpenAIConfig }
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, TokenUsage, UsageSummary }
import org.llm4s.llmconnect.provider.CostEstimator
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.{ ModelMetadata, ModelRegistryService }
import org.llm4s.security.Secret
import org.llm4s.types.Result

object ModelIdNewtypesTest {
  val named = Completion(id = "1", created = Instant.EPOCH, content = "hi", model = "gpt-4o", message = AssistantMessage("hi"))

  val positional = Completion("2", Instant.EPOCH, "hi", "gpt-4o", AssistantMessage("hi"))

  def renamed(completion: Completion, model: String): Completion = completion.withModel(model)

  def renamedMetadata(metadata: ModelMetadata, id: String): ModelMetadata = metadata.withModelId(id)

  val embedding = EmbeddingModelConfig("text-embedding-3-small", 1536)

  def known(registry: ModelRegistryService): Result[ModelMetadata] = registry.lookup("gpt-4o")

  def underProvider(registry: ModelRegistryService): Result[ModelMetadata] = registry.lookup("openai", "gpt-4o")

  def listed(registry: ModelRegistryService): Result[List[ModelMetadata]] = registry.listByProvider("openai")

  def moved(metadata: ModelMetadata): ModelMetadata = metadata.withProvider("acme")

  def dimensions(using ProviderRegistry): Result[Int] = ModelDimensionRegistry.getDimension("openai", "text-embedding-3-small")

  def cost(usage: TokenUsage)(using ModelRegistryService) = CostEstimator.estimate("gpt-4o", usage)

  def counted(summary: UsageSummary, usage: TokenUsage): UsageSummary = summary.add("gpt-4o", usage, None)

  def config(key: Secret)(using ContextWindowResolver): Result[OpenAIConfig] =
    OpenAIConfig.fromValues("gpt-4o", key, "https://api.openai.com/v1")

  def proxied(config: OpenAIConfig): OpenAIConfig = config.withBaseUrl("https://proxy.example.com/v1")
}
