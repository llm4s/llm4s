package fix.v1

import org.llm4s.error.{ LLMError, ServiceError }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion }
import org.llm4s.toolapi.ToolExecutionConfig
import java.time.Instant
import org.llm4s.security.Secret

// The snippets of docs/reference/migration.md's notes since 0.5.1 that a rule rewrites, as 0.5.1 code.
object Llm4sMigrate_1_0Test {
  def configs(apiKey: String, baseUrl: String)(using ContextWindowResolver) = Seq(
    OpenAIConfig.fromValues("gpt-4o", Secret(apiKey), OpenAIConfig.DEFAULT_BASE_URL),
    OpenAIConfig.fromValues("gpt-4o", Secret(apiKey), baseUrl, Some("org-1"))
  )

  val completion = Completion(id = "1", created = Instant.ofEpochSecond(1700000000L), model = "m",
    message = AssistantMessage("hi"))
  val seconds: Long = completion.created.getEpochSecond

  def describe(error: LLMError): String = error match {
    case ServiceError(message, status, provider, requestId, _) => s"$message $status $provider $requestId"
    case _                                                  => ""
  }

  def retryable(error: ServiceError): Boolean = error.isRecoverableStatus

  def unbounded(config: ToolExecutionConfig): ToolExecutionConfig = config.withTimeout(None)

  def header(config: OpenAIConfig): String = "Bearer " + config.apiKey.reveal
}
