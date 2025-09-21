package org.llm4s.toolapi

import com.azure.ai.openai.models._
import com.azure.json.JsonOptions
import com.azure.json.implementation.DefaultJsonReader

/**
 * Helper class to convert ToolRegistry to Azure OpenAI format
 */
object AzureToolHelper {

  /**
   * Adds tools from a ToolRegistry to a ChatCompletionsOptions
   *
   * @param toolRegistry The tool registry containing the tools
   * @param chatOptions The chat options to add the tools to
   * @return The updated chat options
   */
  def addToolsToOptions(
    toolRegistry: ToolRegistry,
    chatOptions: ChatCompletionsOptions
  ): ChatCompletionsOptions = {
    val tools = convertToolRegistryToAzureTools(toolRegistry)
    chatOptions.setTools(tools)
    chatOptions
  }

  /**
   * Converts a ToolRegistry to a list of Azure OpenAI tools
   *
   * @param toolRegistry The tool registry to convert
   * @return A list of Azure OpenAI tools
   */
  def convertToolRegistryToAzureTools(
    toolRegistry: ToolRegistry
  ): java.util.List[ChatCompletionsToolDefinition] = {
    val toolsJson = toolRegistry.getToolDefinitions("openai")

    val tools = new java.util.ArrayList[ChatCompletionsToolDefinition]()
    for (toolObj <- toolsJson.arr) {
      val toolStr        = ujson.write(toolObj, indent = 2)
      val reader         = DefaultJsonReader.fromString(toolStr, new JsonOptions())
      val toolDefinition = ChatCompletionsToolDefinition.fromJson(reader)
      tools.add(toolDefinition)
    }

    tools
  }
}
