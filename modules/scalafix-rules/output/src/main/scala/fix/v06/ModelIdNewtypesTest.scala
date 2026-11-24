package fix.v06

import java.time.Instant

import org.llm4s.llmconnect.config.{ ContextWindowResolver, EmbeddingModelConfig, OpenAIConfig }
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, TokenUsage, UsageSummary }
import org.llm4s.llmconnect.provider.CostEstimator
import org.llm4s.model.{ ModelMetadata, ModelRegistryService }
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName }

object ModelIdNewtypesTest {
  val named = Completion(id = "1", created = Instant.EPOCH, model = ModelName("gpt-4o"), message = AssistantMessage("hi"))

  val positional = Completion("2", Instant.EPOCH, ModelName("gpt-4o"), AssistantMessage("hi"))

  def renamed(completion: Completion, model: String): Completion = completion.withModel(ModelName(model))

  def renamedMetadata(metadata: ModelMetadata, id: String): ModelMetadata = metadata.withModelId(ModelName(id))

  val embedding = EmbeddingModelConfig(ModelName("text-embedding-3-small"), 1536)

  def known(registry: ModelRegistryService): Result[ModelMetadata] = registry.lookup(ModelName("gpt-4o"))

  def underProvider(registry: ModelRegistryService): Result[ModelMetadata] = registry.lookup("openai", ModelName("gpt-4o"))

  def cost(usage: TokenUsage)(using ModelRegistryService) = CostEstimator.estimate(ModelName("gpt-4o"), usage)

  def counted(summary: UsageSummary, usage: TokenUsage): UsageSummary = summary.add(ModelName("gpt-4o"), usage, None)

  def config(key: Secret)(using ContextWindowResolver): Result[OpenAIConfig] =
    OpenAIConfig.fromValues(ModelName("gpt-4o"), key, BaseUrl("https://api.openai.com/v1"))

  def proxied(config: OpenAIConfig): OpenAIConfig = config.withBaseUrl(BaseUrl("https://proxy.example.com/v1"))
}
