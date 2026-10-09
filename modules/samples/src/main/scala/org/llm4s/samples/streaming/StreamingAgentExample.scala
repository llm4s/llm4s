package org.llm4s.samples.streaming

import org.llm4s.agent.Agent
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.slf4j.LoggerFactory

/**
 * An agent whose answer streams as it is generated: `withStreaming()` makes each model call stream,
 * and `agent.stream` hands every event of the turn to a listener - text deltas live, model and tool
 * events as they commit.
 *
 * To run: sbt "samples/runMain org.llm4s.samples.streaming.StreamingAgentExample"
 */
object StreamingAgentExample:
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val result = for
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)
      agent  <- Agent.builder("assistant", client).withSystemPrompt("You are concise.").withStreaming().build()
      run <- agent.stream(ThreadId("streaming-sample"), "Explain monads in three sentences.") {
        case AgentEvents.ModelCallStarted(s) if s.attempt > 1 => print("\n[retrying]\n")
        case AgentEvents.TextDelta(d)                         => print(d.text)
        case AgentEvents.ModelCallCompleted(m) =>
          println(s"\n\n[${m.model}: ${m.usage.map(u => s"${u.totalTokens} tokens").getOrElse("no usage")}]")
        case _ => ()
      }
      done <- run.await()
    yield done
    result.fold(e => logger.error("Failed: {}", e.formatted), r => logger.info("Status: {}", r.status))
