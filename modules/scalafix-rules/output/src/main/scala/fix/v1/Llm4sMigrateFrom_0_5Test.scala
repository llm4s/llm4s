package fix.v1

import org.llm4s.error.{ LLMError, ServiceError }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion }
import org.llm4s.toolapi.ToolExecutionConfig
import java.time.Instant
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName }

// The snippets of docs/reference/migration.md's notes since 0.5.1 that a rule rewrites, as 0.5.1 code.
object Llm4sMigrateFrom_0_5Test {
  def configs(apiKey: String, baseUrl: String)(using ContextWindowResolver) = Seq(
    OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret(apiKey), OpenAIConfig.DEFAULT_BASE_URL),
    OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret(apiKey), BaseUrl(baseUrl), Some("org-1"))
  )

  val completion = Completion(id = "1", created = Instant.ofEpochSecond(1700000000L), model = ModelName("m"),
    message = AssistantMessage("hi"))
  val seconds: Long = completion.created.getEpochSecond

  def describe(error: LLMError): String = error match {
    case ServiceError(message, status, provider, requestId, _) => s"$message $status $provider $requestId"
    case _                                                  => ""
  }

  def retryable(error: ServiceError): Boolean = error.isRetryable

  def unbounded(config: ToolExecutionConfig): ToolExecutionConfig = config.withTimeout(None)

  def header(config: OpenAIConfig): String = "Bearer " + config.apiKey.reveal

  def promptBudget(client: org.llm4s.llmconnect.LLMClient): Int = client.contextWindow - client.reserveCompletion

  // A positional pattern on OpenAIConfig gets a `_` for each field added since 0.5, and the model keeps its place.
  def modelLength(config: OpenAIConfig): Int = config match {
    case OpenAIConfig(_, model, _, _, _, _, _, _, _, _, _, _, _, _) => model.toString.length
  }
}
