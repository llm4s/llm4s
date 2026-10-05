package org.llm4s.toolapi

import org.llm4s.annotation.Stable

import com.openai.core.ObjectMappers
import com.openai.models.chat.completions.{ ChatCompletionCreateParams, ChatCompletionTool }

import scala.jdk.CollectionConverters._

/**
 * Converts a [[ToolRegistry]] into the tool definitions of OpenAI's Java SDK
 * (`com.openai:openai-java`), which `OpenAIClient` sends for OpenAI, Azure OpenAI and Requesty.
 *
 * This replaces `AzureToolHelper`, whose methods took and returned types from Microsoft's
 * deprecated `com.azure:azure-ai-openai` SDK; `llm4s-openai` moved off that SDK in
 * [[https://github.com/llm4s/llm4s/issues/1132 #1132]].
 */
@Stable
object OpenAIToolHelper {

  /**
   * Sets the registry's tools on a chat-completions request builder.
   *
   * @param toolRegistry The tool registry containing the tools
   * @param params The request builder to add the tools to
   * @return The same builder, for chaining
   */
  def addToolsToParams(
    toolRegistry: ToolRegistry,
    params: ChatCompletionCreateParams.Builder
  ): ChatCompletionCreateParams.Builder =
    params.tools(convertToolRegistryToOpenAITools(toolRegistry))

  /**
   * Converts a ToolRegistry to chat-completions tool definitions, one function tool per
   * registered tool, in registry order.
   *
   * @param toolRegistry The tool registry to convert
   * @return The tool definitions
   */
  def convertToolRegistryToOpenAITools(toolRegistry: ToolRegistry): java.util.List[ChatCompletionTool] = {
    val toolsJson = toolRegistry.getOpenAITools()
    val mapper    = ObjectMappers.jsonMapper()
    toolsJson.arr.map(tool => mapper.readValue(ujson.write(tool), classOf[ChatCompletionTool])).toSeq.asJava
  }
}
