package org.llm4s.samples.basic

import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.toolapi.tools.WeatherTool

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
  def main(args: Array[String]): Unit = {
    // Get API key from environment
    val apiKey = sys.env.getOrElse(
      "GOOGLE_API_KEY", {
        println("Error: GOOGLE_API_KEY environment variable not set")
        sys.exit(1)
        ""
      }
    )

    val config = GeminiConfig.fromValues(
      modelName = "gemini-2.5-flash",
      apiKey = apiKey,
      baseUrl = "https://generativelanguage.googleapis.com/v1beta"
    )

    println("=" * 60)
    println("Gemini Provider Test Suite")
    println("=" * 60)

    // Test 1: Simple completion
    println("\n" + "=" * 60)
    println("TEST 1: Simple Completion")
    println("=" * 60)
    testSimpleCompletion(config)

    // Test 2: Streaming
    println("\n" + "=" * 60)
    println("TEST 2: Streaming")
    println("=" * 60)
    testStreaming(config)

    // Test 3: Tool calling
    println("\n" + "=" * 60)
    println("TEST 3: Tool Calling")
    println("=" * 60)
    testToolCalling(config)

    println("\n" + "=" * 60)
    println("All tests complete!")
    println("=" * 60)
  }

  private def testSimpleCompletion(config: GeminiConfig): Unit = {
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
        println(s"✅ Simple completion SUCCESS")
        println(s"   Model: ${completion.model}")
        println(s"   Response: ${completion.content.take(100)}")
        completion.usage.foreach { u =>
          println(s"   Tokens: ${u.totalTokens} (${u.promptTokens} prompt + ${u.completionTokens} completion)")
        }
      case Left(error) =>
        println(s"❌ Simple completion FAILED: ${error.formatted}")
    }
  }

  private def testStreaming(config: GeminiConfig): Unit = {
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
          chunk.content.foreach { c =>
            fullContent.append(c)
            print(c) // Show streaming in real-time
          }
        }
      )
    } yield completion

    println() // New line after streaming output

    result match {
      case Right(completion) =>
        println(s"✅ Streaming SUCCESS")
        println(s"   Chunks received: $chunkCount")
        println(s"   Total content length: ${fullContent.length}")
        println(s"   Content matches completion: ${completion.content == fullContent.toString()}")
      case Left(error) =>
        println(s"❌ Streaming FAILED: ${error.formatted}")
    }
  }

  private def testToolCalling(config: GeminiConfig): Unit =
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

        val result = for {
          client     <- LLMConnect.getClient(config)
          completion <- client.complete(conversation, options)
        } yield (client, completion)

        result match {
          case Right((client, completion)) =>
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
