package org.llm4s.samples.handoff

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry

/**
 * Context Preservation Example
 *
 * Demonstrates how conversation context is preserved across handoffs.
 * The specialist agent receives the full conversation history.
 */
object ContextPreservationExample extends App {
  println("=" * 80)
  println("Context Preservation Handoff Example")
  println("=" * 80)

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)

    generalAgent    = new Agent(client)
    specialistAgent = new Agent(client)

    // Multi-turn conversation with context
    _ = println("\nTurn 1: 'I'm working on a quantum computing project'")

    state1 <- generalAgent.run(
      query = "I'm working on a quantum computing project",
      tools = ToolRegistry.empty,
      handoffs = Seq.empty,
      debug = false
    )

    _ = println(s"Response: ${state1.conversation.messages.last.content}")
    _ = println("\nTurn 2: 'Can you explain quantum entanglement in detail?'")
    _ = println("(This should trigger a handoff to the specialist)")

    state2 <- generalAgent.continueConversation(
      previousState = state1,
      newUserMessage = "Can you explain quantum entanglement in detail?",
      debug = false
    )

    // For this example, we'll manually demonstrate handoff with context
    // In a real scenario, the general agent would decide to hand off
    _ = println("\nManually handing off to specialist with full context...")

    handoff = Handoff(
      targetAgent = specialistAgent,
      transferReason = Some("Quantum physics expertise required"),
      preserveContext = true, // Transfer full conversation history
      transferSystemMessage = false
    )

    // Build handoff state manually for demonstration
    handoffState = buildDemoHandoffState(state2, handoff)

    finalState <- specialistAgent.run(
      handoffState,
      maxSteps = Some(10),
      traceLogPath = None,
      debug = false
    )

  } yield (state2, finalState)

  result match {
    case Right((state2, finalState)) =>
      println("\n" + "=" * 80)
      println("✅ Context preservation demonstration complete")
      println("=" * 80)
      println(s"Original conversation messages: ${state2.conversation.messages.length}")
      println(s"Specialist received messages: ${finalState.conversation.messages.length}")
      println(s"\nSpecialist's response:")
      println(finalState.conversation.messages.last.content)

      println(s"\n📝 Full conversation flow:")
      state2.conversation.messages.zipWithIndex.foreach { case (msg, idx) =>
        println(s"  ${idx + 1}. [${msg.role}] ${msg.content.take(80)}...")
      }

    case Left(error) =>
      println("\n" + "=" * 80)
      println("❌ Error occurred")
      println("=" * 80)
      println(s"Error: ${error.formatted}")
  }

  // Helper method to demonstrate handoff state building
  def buildDemoHandoffState(
    sourceState: org.llm4s.agent.AgentState,
    handoff: Handoff
  ): org.llm4s.agent.AgentState = {
    import org.llm4s.agent.AgentState
    import org.llm4s.agent.AgentStatus
    import org.llm4s.llmconnect.model.Conversation

    val transferredMessages = if (handoff.preserveContext) {
      sourceState.conversation.messages
    } else {
      import org.llm4s.llmconnect.model.MessageRole
      sourceState.conversation.messages
        .findLast(_.role == MessageRole.User)
        .toVector
    }

    AgentState(
      conversation = Conversation(transferredMessages),
      tools = ToolRegistry.empty,
      initialQuery = sourceState.initialQuery,
      status = AgentStatus.InProgress,
      logs = Vector("[handoff] Received handoff with full context"),
      systemMessage = if (handoff.transferSystemMessage) sourceState.systemMessage else None,
      availableHandoffs = Seq.empty
    )
  }
}
