package org.llm4s.samples.streaming

import org.llm4s.agent.Agent
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin.BuiltinTools
import org.slf4j.LoggerFactory

/**
 * Tool activity as events: `ToolCallStarted` (live, with the arguments), `ToolResult` (live, with the
 * content) and `ToolExecuted` (durable, outcome and duration only).
 *
 * To run: sbt "samples/runMain org.llm4s.samples.streaming.StreamingWithToolsExample"
 */
object StreamingWithToolsExample:
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val result = for
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)
      tools  <- BuiltinTools.coreSafe
      agent <- Agent
        .builder("assistant", client)
        .withTools(new ToolRegistry(tools))
        .withSystemPrompt("Use the tools to answer.")
        .withStreaming()
        .build()
      run <- agent.stream(ThreadId("streaming-tools-sample"), "What is 17 * 23, and what time is it in UTC?") {
        case AgentEvents.ToolCallStarted(t) => println(s"\n[call ${t.tool} ${ujson.write(t.arguments)}]")
        case AgentEvents.ToolResult(r)      => println(s"[result ${r.content.take(200)}]")
        case AgentEvents.ToolExecuted(t) =>
          println(s"[${t.tool}: ${t.outcome} in ${t.duration.toMillis} ms]")
        case AgentEvents.TextDelta(d) => print(d.text)
        case _                        => ()
      }
      done <- run.await()
    yield done
    result.fold(e => logger.error("Failed: {}", e.formatted), r => logger.info("Status: {}", r.status))
