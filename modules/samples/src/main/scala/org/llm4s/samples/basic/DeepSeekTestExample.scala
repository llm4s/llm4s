package org.llm4s.samples.basic

import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory

/**
 * Test example for DeepSeek provider.
 * Tests simple completion, streaming, and tool calling.
 *
 * Run with:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.basic.DeepSeekTestExample"
 * }}}
 */
object DeepSeekTestExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Get API key from environment
    val apiKey = sys.env.getOrElse(
      "DEEPSEEK_API_KEY", {
        logger.error("DEEPSEEK_API_KEY environment variable not set")
        sys.exit(1)
        ""
      }
    )

    val config = DeepSeekConfig.fromValues(
      modelName = "deepseek-chat",
      apiKey = apiKey,
      baseUrl = "https://api.deepseek.com"
    )

    logger.info("=" * 60)
    logger.info("DeepSeek Provider Test Suite")
    logger.info("=" * 60)

    // Test 1: Simple completion
    logger.info("")
    logger.info("=" * 60)
    logger.info("TEST 1: Simple Completion")
    logger.info("=" * 60)
    testSimpleCompletion(config)

    // Test 2: Streaming
    logger.info("")
    logger.info("=" * 60)
    logger.info("TEST 2: Streaming")
    logger.info("=" * 60)
    testStreaming(config)

    // Test 3: Tool calling
    logger.info("")
    logger.info("=" * 60)
    logger.info("TEST 3: Tool Calling")
    logger.info("=" * 60)
    testToolCalling(config)

    logger.info("")
    logger.info("=" * 60)
    logger.info("All tests complete!")
    logger.info("=" * 60)
  }

  private def testSimpleCompletion(config: DeepSeekConfig): Unit = {
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant. Be concise."),
        UserMessage("What is 2 + 2? Answer with just the number.")
      )
    )

    val result = for {
      client     <- LLMConnect.getClient(config)
      completion <- client.complete(conversation)
    } yield completion

    result match {
      case Right(completion) =>
        logger.info("Simple completion SUCCESS")
        logger.info(s"   Model: ${completion.model}")
        logger.info(s"   Response: ${completion.content.take(100)}")
        completion.usage.foreach { u =>
          logger.info(s"   Tokens: ${u.totalTokens} (${u.promptTokens} prompt + ${u.completionTokens} completion)")
        }
      case Left(error) =>
        logger.error(s"Simple completion FAILED: ${error.formatted}")
    }
  }

  private def testStreaming(config: DeepSeekConfig): Unit = {
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("Count from 1 to 5, with each number on a new line.")
      )
    )

    var chunkCount  = 0
    val fullContent = new StringBuilder()

    val result = for {
      client <- LLMConnect.getClient(config)
      completion <- client.streamComplete(
        conversation,
        CompletionOptions(),
        onChunk = { chunk =>
          chunkCount += 1
          chunk.content.foreach(c => fullContent.append(c))
        }
      )
    } yield completion

    result match {
      case Right(completion) =>
        logger.info("Streaming SUCCESS")
        logger.info(s"   Chunks received: $chunkCount")
        logger.info(s"   Total content length: ${fullContent.length}")
        logger.info(s"   Content matches completion: ${completion.content == fullContent.toString()}")
      case Left(error) =>
        logger.error(s"Streaming FAILED: ${error.formatted}")
    }
  }

  private def testToolCalling(config: DeepSeekConfig): Unit =
    WeatherTool.toolSafe match {
      case Left(err) => logger.error(s"Failed to load weather tool: ${err.formatted}")
      case Right(weatherTool) =>
        val toolRegistry = new ToolRegistry(Seq(weatherTool))
        val conversation = Conversation(
          Seq(
            SystemMessage(
              "You are a helpful assistant. When asked about weather, always call the get_weather tool immediately."
            ),
            UserMessage("What's the weather in Paris, France in celsius? Call the tool now.")
          )
        )
        val options = CompletionOptions(tools = Seq(weatherTool))

        val result = for {
          client     <- LLMConnect.getClient(config)
          completion <- client.complete(conversation, options)
        } yield (client, completion)

        result match {
          case Right((client, completion)) =>
            if (completion.toolCalls.nonEmpty) {
              logger.info("Tool calling detected")
              completion.toolCalls.foreach { tc =>
                logger.info(s"   Tool: ${tc.name}")
                logger.info(s"   Args: ${tc.arguments}")
                logger.info(s"   ID: ${tc.id}")

                // Execute the tool
                val request    = ToolCallRequest(tc.name, tc.arguments)
                val toolResult = toolRegistry.execute(request)
                logger.info(s"   Tool result: ${toolResult.map(_.render()).getOrElse("error")}")

                // Send tool result back
                val updatedConversation = conversation
                  .addMessage(completion.message)
                  .addMessage(ToolMessage(toolResult.map(_.render()).getOrElse("error"), tc.id))

                logger.info("")
                logger.info("   Sending tool result back to model...")
                client.complete(updatedConversation, CompletionOptions()) match {
                  case Right(finalCompletion) =>
                    logger.info(s"   Final response: ${finalCompletion.content.take(200)}...")
                  case Left(error) =>
                    logger.error(s"   Final response failed: ${error.formatted}")
                }
              }
            } else {
              logger.warn("No tool calls in response (model responded directly)")
              logger.info(s"   Response: ${completion.content.take(200)}")
            }
          case Left(error) =>
            logger.error(s"Tool calling FAILED: ${error.formatted}")
        }
    }
}
