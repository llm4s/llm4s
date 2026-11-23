package org.llm4s.toolapi

import com.openai.core.ObjectMappers
import com.openai.models.chat.completions.{ ChatCompletionCreateParams, ChatCompletionTool }

import scala.jdk.CollectionConverters._

/**
 * Converts [[ToolFunction]]s into the tool definitions of OpenAI's Java SDK
 * (`com.openai:openai-java`), which `OpenAIClient` sends for OpenAI, Azure OpenAI and Requesty.
 *
 * This replaces `AzureToolHelper`, whose methods took and returned types from Microsoft's
 * deprecated `com.azure:azure-ai-openai` SDK; `llm4s-openai` moved off that SDK in
 * [[https://github.com/llm4s/llm4s/issues/1132 #1132]].
 *
 * Internal: its signatures name openai-java types, which llm4s does not freeze into its API
 * ([[https://github.com/llm4s/llm4s/issues/2360 #2360]]). Users get the same definitions as JSON from
 * [[ToolFunction.toolDefinitions]].
 */
private[llm4s] object OpenAIToolHelper {

  /**
   * Sets `tools` on a chat-completions request builder.
   *
   * @param tools  The tools to offer the model
   * @param params The request builder to add the tools to
   * @return The same builder, for chaining
   */
  def addToolsToParams(
    tools: Seq[ToolFunction[_, _]],
    params: ChatCompletionCreateParams.Builder
  ): ChatCompletionCreateParams.Builder =
    params.tools(chatCompletionTools(tools))

  /**
   * Converts tools to chat-completions tool definitions, one strict function tool per tool, in order.
   *
   * @param tools The tools to convert
   * @return The tool definitions
   */
  def chatCompletionTools(tools: Seq[ToolFunction[_, _]]): java.util.List[ChatCompletionTool] = {
    val toolsJson = ToolFunction.toolDefinitions(tools, strict = true)
    val mapper    = ObjectMappers.jsonMapper()
    toolsJson.arr.map(tool => mapper.readValue(ujson.write(tool), classOf[ChatCompletionTool])).toSeq.asJava
  }
}
