package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory
import upickle.default._

/**
 * Demonstrates tool calling and streaming responses via AWS Bedrock.
 *
 * This example shows the full tool-calling flow:
 * 1. Send a query with tools defined — the LLM responds with tool calls
 * 2. Execute the tools locally and collect results
 * 3. Send the results back and stream the LLM's final answer
 *
 * To run this example:
 * {{{
 * # Export AWS credentials
 * export AWS_ACCESS_KEY_ID=$(aws configure get aws_access_key_id --profile twdc-bedrock-central)
 * export AWS_SECRET_ACCESS_KEY=$(aws configure get aws_secret_access_key --profile twdc-bedrock-central)
 * export AWS_SESSION_TOKEN=$(aws configure get aws_session_token --profile twdc-bedrock-central)
 *
 * sbt "samples/runMain org.llm4s.samples.basic.StreamingExample"
 * }}}
 */
object StreamingExample {
  private val logger = LoggerFactory.getLogger(getClass)

  case class CalcResult(expression: String, result: Double)
  implicit val calcResultRW: ReadWriter[CalcResult] = macroRW

  def main(args: Array[String]): Unit = {
    val result = for {
      providerCfg     <- Llm4sConfig.provider("bedrock-anthropic-main")
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)

      // Define a calculator tool
      calcTool <- ToolBuilder[Map[String, Any], CalcResult](
        "calculate",
        "Evaluates a simple arithmetic expression with two numbers. Supports +, -, *, /.",
        Schema
          .`object`[Map[String, Any]]("Calculator parameters")
          .withProperty(Schema.property("a", Schema.number("First number")))
          .withProperty(Schema.property("b", Schema.number("Second number")))
          .withProperty(
            Schema.property("operator", Schema.string("Operator: +, -, *, /").withEnum(Seq("+", "-", "*", "/")))
          )
      ).withHandler { params =>
        for {
          a  <- params.getDouble("a")
          b  <- params.getDouble("b")
          op <- params.getString("operator")
        } yield {
          val res = op match {
            case "+" => a + b
            case "-" => a - b
            case "*" => a * b
            case "/" => if (b != 0) a / b else Double.NaN
            case _   => Double.NaN
          }
          CalcResult(s"$a $op $b", res)
        }
      }.buildSafe()

      // Build the weather tool
      weatherTool <- WeatherTool.toolSafe

      toolRegistry = new ToolRegistry(Seq(calcTool, weatherTool))

      query = "What's the weather in Tokyo? Also, what is 15 * 7?"

      _ = {
        logger.info("=== Tool Calling + Streaming (Bedrock Anthropic) ===")
        logger.info("Query: {}", query)
        logger.info("")
      }

      // Step 1: Send query with tools — LLM will respond with tool calls
      conversation1 = Conversation(Seq(
        SystemMessage("You are a helpful assistant. Use tools when needed."),
        UserMessage(query)
      ))
      options = CompletionOptions(tools = toolRegistry.tools)

      _ = logger.info("--- Step 1: LLM decides which tools to call ---")
      completion1 <- client.complete(conversation1, options)

      _ = {
        if (completion1.toolCalls.nonEmpty) {
          completion1.toolCalls.foreach { tc =>
            logger.info("  Tool call: {}({})", tc.name, tc.arguments.render())
          }
        } else {
          logger.info("  No tool calls (LLM responded directly)")
        }
      }

      // Step 2: Execute tools locally
      _ = logger.info("")
      _ = logger.info("--- Step 2: Execute tools locally ---")
      toolResults = completion1.toolCalls.map { tc =>
        val toolResult = toolRegistry.tools
          .find(_.name == tc.name)
          .map(_.execute(tc.arguments))
          .getOrElse(Left(ToolCallError.HandlerError(tc.name, "Tool not found")))

        val resultStr = toolResult match {
          case Right(json) => json.render()
          case Left(err)   => s"""{"error": "${err.toString}"}"""
        }
        logger.info("  {} -> {}", tc.name, resultStr)
        ToolMessage(resultStr, tc.id)
      }

      // Step 3: Send tool results back and stream the final answer
      _ = logger.info("")
      _ = logger.info("--- Step 3: Stream final response with tool results ---")
      conversation2 = Conversation(
        conversation1.messages
          ++ Seq(completion1.message)
          ++ toolResults
      )

      streamCompletion <- client.streamComplete(
        conversation2,
        CompletionOptions(),
        onChunk = { chunk =>
          chunk.content.foreach(print)
          chunk.finishReason.foreach(_ => println())
        }
      )

      _ = {
        logger.info("")
        logger.info("=== Complete ===")
        streamCompletion.usage.foreach { usage =>
          logger.info(
            "Tokens used: {} ({} prompt + {} completion)",
            usage.totalTokens,
            usage.promptTokens,
            usage.completionTokens
          )
        }
      }
    } yield ()

    result.fold(err => logger.error("Error: {}", err.formatted), identity)
  }
}
