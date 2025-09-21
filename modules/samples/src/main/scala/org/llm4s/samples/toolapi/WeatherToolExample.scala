package org.llm4s.samples.toolapi

import org.llm4s.toolapi._
import org.llm4s.toolapi.tools.WeatherTool

/**
 * Example demonstrating how to use the weather tool
 */
object WeatherToolExample {
  def main(args: Array[String]): Unit = {
    // Create a tool registry with the weather tool
    val toolRegistry = new ToolRegistry(Seq(WeatherTool.tool))

    // Example execution
    val weatherRequest = ToolCallRequest(
      functionName = "get_weather",
      arguments = ujson.Obj("location" -> "London, UK", "units" -> "celsius")
    )

    // Execute the tool call
    val result = toolRegistry.execute(weatherRequest)

    println("Tool execution result:")
    result match {
      case Right(json) => println(json.render(indent = 2))
      case Left(error) => println(s"Error: $error")
    }

    // Generate tool definitions for OpenAI
    val openaiTools = toolRegistry.getToolDefinitions("openai")

    println("\nOpenAI tool definition:")
    println(openaiTools.render(indent = 2))
  }
}
