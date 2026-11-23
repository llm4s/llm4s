/*
rule = Llm4sMigrate_1_0
*/
package fix.v1

import org.llm4s.error.{ LLMError, ServiceError }
import org.llm4s.error.ServiceError.ServiceErrorOps
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion }
import org.llm4s.toolapi.ToolExecutionConfig

// The snippets of docs/reference/migration.md's notes since 0.5.1 that a rule rewrites, as 0.5.1 code.
object Llm4sMigrate_1_0Test {
  def configs(apiKey: String, baseUrl: String)(using ContextWindowResolver) = Seq(
    OpenAIConfig.fromValues("gpt-4o", apiKey, None, OpenAIConfig.DEFAULT_BASE_URL),
    OpenAIConfig.fromValues("gpt-4o", apiKey, Some("org-1"), baseUrl)
  )

  val completion = Completion(id = "1", created = 1700000000L, content = "hi", model = "m",
    message = AssistantMessage("hi"))
  val seconds: Long = completion.created

  def describe(error: LLMError): String = error match {
    case ServiceError(message, status, provider, requestId) => s"$message $status $provider $requestId"
    case _                                                  => ""
  }

  def retryable(error: ServiceError): Boolean = error.isRecoverableStatus

  def unbounded(config: ToolExecutionConfig): ToolExecutionConfig = config.copy(timeout = None)

  def header(config: OpenAIConfig): String = "Bearer " + config.apiKey
}
