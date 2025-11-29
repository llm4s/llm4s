package org.llm4s.samples.streaming

import org.llm4s.agent.Agent
import org.llm4s.agent.streaming.AgentEvent._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry

/**
 * Example demonstrating how to collect and analyze streaming events.
 *
 * This example shows how to:
 * - Use `runCollectingEvents` to gather all events
 * - Analyze events after execution
 * - Extract metrics from event data
 *
 * Run with: sbt "samples/runMain org.llm4s.samples.streaming.EventCollectionExample"
 *
 * Note: Requires LLM_MODEL and appropriate API key environment variables.
 */
object EventCollectionExample extends App {

  println("=" * 60)
  println("Event Collection Example")
  println("=" * 60)
  println()

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    // Run and collect all events
    stateAndEvents <- agent.runCollectingEvents(
      query = "Write a haiku about programming in Scala.",
      tools = ToolRegistry.empty,
      maxSteps = Some(3)
    )
  } yield stateAndEvents

  result match {
    case Right((state, events)) =>
      // Analyze the collected events
      println("Collected Events Summary")
      println("-" * 40)
      println(s"Total events: ${events.size}")
      println()

      // Count by type
      val textDeltas   = events.collect { case e: TextDelta => e }
      val textComplete = events.collect { case e: TextComplete => e }
      val steps        = events.collect { case e: StepStarted => e }
      val toolCalls    = events.collect { case e: ToolCallStarted => e }
      val completions  = events.collect { case e: AgentCompleted => e }

      println("Event breakdown:")
      println(s"  - TextDelta events: ${textDeltas.size}")
      println(s"  - TextComplete events: ${textComplete.size}")
      println(s"  - StepStarted events: ${steps.size}")
      println(s"  - ToolCallStarted events: ${toolCalls.size}")
      println(s"  - AgentCompleted events: ${completions.size}")
      println()

      // Calculate total streamed characters
      val totalChars = textDeltas.map(_.delta.length).sum
      println(s"Total characters streamed: $totalChars")

      // Show timing if available
      completions.headOption.foreach { completion =>
        println(s"Total duration: ${completion.durationMs}ms")
        println(s"Total steps: ${completion.totalSteps}")
      }
      println()

      // Show event timeline
      println("Event Timeline:")
      println("-" * 40)
      events.zipWithIndex.foreach { case (event, idx) =>
        val eventType = event.getClass.getSimpleName
        val summary = event match {
          case TextDelta(delta, _)                     => s"'${delta.take(20)}${if (delta.length > 20) "..." else ""}'"
          case TextComplete(full, _)                   => s"${full.length} chars total"
          case AgentStarted(query, _, _)               => s"'${query.take(30)}'"
          case StepStarted(n, _)                       => s"step $n"
          case StepCompleted(n, hasTc, _)              => s"step $n (toolCalls: $hasTc)"
          case AgentCompleted(_, steps, ms, _)         => s"$steps steps, ${ms}ms"
          case ToolCallStarted(_, name, _, _)          => s"calling $name"
          case ToolCallCompleted(_, name, _, _, ms, _) => s"$name completed in ${ms}ms"
          case _                                       => ""
        }
        println(f"  $idx%3d. $eventType%-25s $summary")
      }
      println()

      // Show final response
      println("Final Response:")
      println("-" * 40)
      state.conversation.messages.lastOption.foreach(msg => println(msg.content))

    case Left(error) =>
      println(s"Error: ${error.message}")
      System.exit(1)
  }
}
