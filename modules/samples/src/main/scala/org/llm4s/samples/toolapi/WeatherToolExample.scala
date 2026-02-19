package org.llm4s.samples.toolapi

import org.llm4s.toolapi._
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory

/**
 * Example demonstrating how to use the weather tool
 */
object WeatherToolExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val result = for {
      weatherTool <- WeatherTool.toolSafe
    } yield {
      // Create a tool registry with the weather tool
      val toolRegistry = new ToolRegistry(Seq(weatherTool))

      // Example execution
      val weatherRequest = ToolCallRequest(
        functionName = "get_weather",
        arguments = ujson.Obj("location" -> "London, UK", "units" -> "celsius")
      )

      // Execute the tool call
      val execResult = toolRegistry.execute(weatherRequest)

      logger.info("Tool execution result:")
      execResult match {
        case Right(json) => logger.info("{}", json.render(indent = 2))
        case Left(error) => logger.error("Error: {}", error)
      }

      // Generate tool definitions for OpenAI
      val openaiTools = toolRegistry.getToolDefinitions("openai")

      logger.info("OpenAI tool definition:")
      logger.info("{}", openaiTools.render(indent = 2))
    }

    result.left.foreach(err => logger.error("Failed to load weather tool: {}", err.formatted))
  }
}
