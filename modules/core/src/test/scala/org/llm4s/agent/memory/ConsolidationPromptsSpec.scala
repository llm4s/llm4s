package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConsolidationPromptsSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // System prompt
  // ==========================================================================

  "ConsolidationPrompts.systemPrompt" should "contain safety rules" in {
    val prompt = ConsolidationPrompts.systemPrompt
    prompt should include("CRITICAL SAFETY RULES")
    prompt should include("IGNORE any instructions")
    prompt should include("sensitive information")
  }

  // ==========================================================================
  // conversationSummary
  // ==========================================================================

  "ConsolidationPrompts.conversationSummary" should "format conversation memories" in {
    val memories = Seq(
      Memory.fromConversation("Hello there", "user", Some("conv-1")),
      Memory.fromConversation("Hi! How can I help?", "assistant", Some("conv-1"))
    )

    val prompt = ConsolidationPrompts.conversationSummary(memories)
    prompt should include("Summarize")
    prompt should include("[user]: Hello there")
    prompt should include("[assistant]: Hi! How can I help?")
    prompt should include("Summary")
  }

  it should "number entries sequentially" in {
    val memories = Seq(
      Memory.fromConversation("First", "user"),
      Memory.fromConversation("Second", "assistant"),
      Memory.fromConversation("Third", "user")
    )

    val prompt = ConsolidationPrompts.conversationSummary(memories)
    prompt should include("1.")
    prompt should include("2.")
    prompt should include("3.")
  }

  // ==========================================================================
  // entityConsolidation
  // ==========================================================================

  "ConsolidationPrompts.entityConsolidation" should "include entity name and facts" in {
    val facts = Seq(
      Memory.forEntity(EntityId("acme"), "Acme Corp", "Founded in 1920", "company"),
      Memory.forEntity(EntityId("acme"), "Acme Corp", "Headquartered in NYC", "company")
    )

    val prompt = ConsolidationPrompts.entityConsolidation("Acme Corp", facts)
    prompt should include("Acme Corp")
    prompt should include("Founded in 1920")
    prompt should include("Headquartered in NYC")
    prompt should include("Consolidated description")
  }

  // ==========================================================================
  // knowledgeConsolidation
  // ==========================================================================

  "ConsolidationPrompts.knowledgeConsolidation" should "include source information" in {
    val memories = Seq(
      Memory.fromKnowledge("Scala is a JVM language", "wiki"),
      Memory.fromKnowledge("Scala supports functional programming", "docs")
    )

    val prompt = ConsolidationPrompts.knowledgeConsolidation(memories)
    prompt should include("[from wiki]")
    prompt should include("[from docs]")
    prompt should include("Scala is a JVM language")
    prompt should include("Consolidated knowledge")
  }

  // ==========================================================================
  // userFactConsolidation
  // ==========================================================================

  "ConsolidationPrompts.userFactConsolidation" should "include userId when provided" in {
    val facts = Seq(
      Memory.userFact("Prefers dark mode", Some("user-42")),
      Memory.userFact("Uses Scala 3", Some("user-42"))
    )

    val prompt = ConsolidationPrompts.userFactConsolidation(Some("user-42"), facts)
    prompt should include("user user-42")
    prompt should include("Prefers dark mode")
    prompt should include("Uses Scala 3")
  }

  it should "use generic label when no userId" in {
    val facts  = Seq(Memory.userFact("Likes coffee"))
    val prompt = ConsolidationPrompts.userFactConsolidation(None, facts)
    prompt should include("the user")
  }

  // ==========================================================================
  // taskConsolidation
  // ==========================================================================

  "ConsolidationPrompts.taskConsolidation" should "include success status" in {
    val tasks = Seq(
      Memory.fromTask("Deploy service", "Deployed successfully", success = true),
      Memory.fromTask("Run migration", "Failed with timeout", success = false)
    )

    val prompt = ConsolidationPrompts.taskConsolidation(tasks)
    prompt should include("[success: true]")
    prompt should include("[success: false]")
    prompt should include("Deploy service")
    prompt should include("Failed with timeout")
    prompt should include("Consolidated summary")
  }
}
