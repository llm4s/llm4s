package org.llm4s.samples.basic

import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory

/**
 * Test example for Google Gemini provider.
 * Tests simple completion, streaming, and tool calling.
 *
 * Run with:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.basic.GeminiTestExample"
 * }}}
 */

object GeminiTestExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {

    val result = for {
      providerCfg <- org.llm4s.config.Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      _ = {
        logger.info("=" * 60)
        logger.info("Gemini Provider Test Suite")
        logger.info("=" * 60)

        testSimpleCompletion(client)
        testStreaming(client)
        testToolCalling(client)

        logger.info("=" * 60)
        logger.info("All tests complete!")
        logger.info("=" * 60)
      }
    } yield ()

    result.fold(
      err => logger.error(s"Configuration error: ${err.formatted}"),
      identity
    )
  }

  private def testSimpleCompletion(client: org.llm4s.llmconnect.LLMClient): Unit = {

    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant. Be concise."),
        UserMessage("What is 2 + 2? Answer with just the number.")
      )
    )

    client.complete(conversation) match {
      case Right(completion) =>
        logger.info(" Simple completion SUCCESS")
        logger.info(s"   Model: ${completion.model}")
        logger.info(s"   Response: ${completion.message.content.take(100)}")
        completion.usage.foreach { u =>
          logger.info(s"   Tokens: ${u.totalTokens} (${u.promptTokens} prompt + ${u.completionTokens} completion)")
        }

      case Left(error) =>
        logger.error(s" Simple completion FAILED: ${error.formatted}")
    }
  }

  private def testStreaming(client: org.llm4s.llmconnect.LLMClient): Unit = {

    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("Count from 1 to 5, each on new line.")
      )
    )

    var chunkCount  = 0
    val fullContent = new StringBuilder()

    val result = client.streamComplete(
      conversation,
      CompletionOptions(),
      onChunk = { chunk =>
        chunkCount += 1
        chunk.content.foreach { c =>
          fullContent.append(c)
          print(c)
        }
      }
    )

    println()

    result match {
      case Right(completion) =>
        logger.info("\n Streaming SUCCESS")
        logger.info(s"   Chunks received: $chunkCount")
        logger.info(s"   Total content length: ${fullContent.length}")
        logger.info(s"   Content matches completion: ${completion.message.content == fullContent.toString()}")

      case Left(error) =>
        logger.error(s" Streaming FAILED: ${error.formatted}")
    }
  }

  private def testToolCalling(client: org.llm4s.llmconnect.LLMClient): Unit =
    WeatherTool.toolSafe match {
      case Left(err) => println(s"Failed to load weather tool: ${err.formatted}")
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

        client.complete(conversation, options) match {
          case Right(completion) =>
            if (completion.toolCalls.nonEmpty) {
              println(s"✅ Tool calling detected")
              completion.toolCalls.foreach { tc =>
                println(s"   Tool: ${tc.name}")
                println(s"   Args: ${tc.arguments}")
                println(s"   ID: ${tc.id}")

                // Execute the tool
                val request    = ToolCallRequest(tc.name, tc.arguments)
                val toolResult = toolRegistry.execute(request)
                println(s"   Tool result: ${toolResult.map(_.render()).getOrElse("error")}")

                // Send tool result back
                val updatedConversation = conversation
                  .addMessage(completion.message)
                  .addMessage(ToolMessage(toolResult.map(_.render()).getOrElse("error"), tc.id))

                println("\n   Sending tool result back to model...")
                client.complete(updatedConversation, CompletionOptions()) match {
                  case Right(finalCompletion) =>
                    println(s"   ✅ Final response: ${finalCompletion.content.take(200)}...")
                  case Left(error) =>
                    println(s"   ❌ Final response failed: ${error.formatted}")
                }
              }
            } else {
              println(s"⚠️  No tool calls in response (model responded directly)")
              println(s"   Response: ${completion.content.take(200)}")
            }
          case Left(error) =>
            println(s"❌ Tool calling FAILED: ${error.formatted}")
        }
    }
}
