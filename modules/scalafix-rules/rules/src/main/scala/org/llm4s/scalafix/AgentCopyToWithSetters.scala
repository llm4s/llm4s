package org.llm4s.scalafix

/**
 * `.copy(field = value)` on the `llm4s-agent` types made growth-safe since 0.6.0 becomes `.withField(value)` (#2778).
 *
 * `llm4s-agent` joined the stability tier check, so its `@Stable` case classes that may gain a field have a private
 * constructor and `copy`: `config.copy(maxTokens = Some(8000))` on a `ContextWindowConfig` becomes
 * `config.withMaxTokens(8000)`. A positional `copy` and a `new X(...)` are reported, with a link to the migration
 * note.
 */
class AgentCopyToWithSetters extends CopyToSetters("AgentCopyToWithSetters") {

  override def description: String =
    "Rewrites .copy(field = value) on the llm4s-agent types made growth-safe since llm4s 0.6.0 to .withField(value) " +
      "setters"

  override protected def types: Set[String]                      = AgentCopyToWithSetters.types
  override protected def release: String                         = "0.6.0"
  override protected def reportNew: Boolean                      = true
  override protected def privateCopyGuide(owner: String): String = AgentCopyToWithSetters.guide
}

object AgentCopyToWithSetters {

  val guide: String = Migration.guide10("llm4s-agent-types-are-tiered-and-growth-safe-2778")

  /**
   * The types, as 0.6.0 named them. Every field of each type's 0.6.0 constructor has a `with` setter of the same name
   * on main, taking the value and, for an `Option` field, the `Option` too.
   */
  val types: Set[String] = Set(
    "ContextWindowConfig",
    "Handoff",
    "PruningStrategy.AdaptiveWindowing",
    "delegation/ChildRef",
    "events/CallUsage",
    "events/GuardrailBlock",
    "events/HandedOff",
    "events/ModelCallCompleted",
    "events/ModelCallStarted",
    "events/TextDelta",
    "events/ThinkingDelta",
    "events/ToolCallResult",
    "events/ToolCallStarted",
    "events/ToolExecuted",
    "graph/ChildThread",
    "graph/Command",
    "graph/EventDraft",
    "graph/EventRecord",
    "graph/GraphError.CheckpointWriteFailed",
    "graph/GraphError.ThreadBusy",
    "graph/GraphShape",
    "graph/GraphSnapshot",
    "graph/GraphSnapshot.Activation",
    "graph/GraphSnapshot.ParkedContinuation",
    "graph/GraphSnapshot.PendingTask",
    "graph/Observer",
    "graph/PendingInterrupt",
    "graph/PendingWrite",
    "graph/ThreadOwner",
    "graph/toolloop/AgentInput",
    "graph/toolloop/LoopHandoff",
    "graph/toolloop/ToolTask",
    "graph/toolloop/Transfer",
    "graph/toolloop/TurnOutput",
    "graph/toolloop/TurnState",
    "guardrails/builtin/InjectionPattern"
  ).map(name => s"org/llm4s/agent/$name")
}
