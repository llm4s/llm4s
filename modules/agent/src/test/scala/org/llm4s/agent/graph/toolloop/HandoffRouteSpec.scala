package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.agent.graph.tool.*
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class HandoffRouteSpec extends AnyFlatSpec with Matchers with EitherValues {
  import ToolLoopFixtures.*

  private val triage  = AgentId.unsafe("triage")
  private val physics = AgentId.unsafe("physics")
  private val thread  = ThreadId("handoff-1")

  /** A model that plays `turns` in order, then fails; records every message list and tool set it was sent. */
  final private class ScriptedModel(turns: (Vector[Message] => AssistantMessage)*) extends ModelStep {
    val seen      = new CopyOnWriteArrayList[Vector[Message]]()
    val toolNames = new CopyOnWriteArrayList[Vector[String]]()
    def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion] = {
      val turn = seen.size
      seen.add(messages)
      toolNames.add(tools.tools.map(_.spec.name))
      turns
        .lift(turn)
        .map(play => Right(completion(play(messages))))
        .getOrElse(Left(ValidationError("model", s"no turn $turn")))
    }
    def calls: Int = seen.size
  }

  private def tool(name: String, count: AtomicInteger = new AtomicInteger()): AgentTool[Echo] =
    AgentTool(
      AgentToolSpec[Echo](
        name,
        s"The $name tool",
        Schema.`object`[Echo](name).withRequiredField("text", Schema.string("t"))
      )
    ) { (args, _) =>
      count.incrementAndGet()
      ToolOutcome.Success(ujson.Str(args.text))
    }

  private val triageTools  = ToolSet.of(tool("route")).value
  private val physicsTools = ToolSet.of(tool("calculate")).value

  private def handoff(id: String, to: AgentId): Vector[Message] => AssistantMessage =
    _ => AssistantMessage(None, Seq(ToolCall(id, HandoffTools.toolName(to), ujson.Obj("reason" -> "physics"))))

  private def call(id: String, name: String): Vector[Message] => AssistantMessage =
    _ => AssistantMessage(None, Seq(ToolCall(id, name, ujson.Obj("text" -> id))))

  private def answer(text: String): Vector[Message] => AssistantMessage = _ => AssistantMessage(text)

  private def toPhysics(preserve: Boolean = true) =
    Vector(LoopHandoff(physics, Some("physics questions"), preserveContext = preserve))

  private def loop(agents: LoopAgent*): ToolLoop = ToolLoop.build("handoffs", "1", triage, agents.toVector).value

  private def messagesOf(state: ThreadState): Vector[Message] = state.get(Messages.key).value.map(_.message)

  "A handoff" should "route to the target, which answers with its own prompt and tools" in {
    val triageModel  = ScriptedModel(handoff("h1", physics))
    val physicsModel = ScriptedModel(answer("E = mc^2"))
    val graph = loop(
      LoopAgent(triage, triageModel, triageTools).withSystemPrompt(Some("Route.")).withHandoffs(toPhysics()),
      LoopAgent(physics, physicsModel, physicsTools).withSystemPrompt(Some("Do physics."))
    )

    val (state, output) = runInMemory(graph.graph, AgentInput("what is energy?")).completed
    output shouldBe TurnOutput(TurnOutcome.Completed, physics)
    state.get(LoopKeys.activeAgent).value shouldBe Some(physics)
    triageModel.toolNames.get(0) should contain theSameElementsAs Vector("route", "handoff_to_physics")
    physicsModel.toolNames.get(0) shouldBe Vector("calculate")
    physicsModel.seen.get(0).head shouldBe SystemMessage("Do physics.")
    physicsModel.seen.get(0).collect { case s: SystemMessage => s } shouldBe Vector(SystemMessage("Do physics."))
    physicsModel.seen.get(0).tail shouldBe messagesOf(state).init
    messagesOf(state) should contain(ToolMessage("Transferred to physics", "h1"))
    Message.validateConversation(messagesOf(state).toList).value shouldBe (())
    state.get(LoopKeys.turn).value shouldBe TurnState(2, Some(TurnOutcome.Completed), Some(triage))
  }

  it should "start the next turn with the target, without calling the source" in {
    val triageModel  = ScriptedModel(handoff("h1", physics))
    val physicsModel = ScriptedModel(answer("first"), answer("second"))
    val graph = loop(
      LoopAgent(triage, triageModel, triageTools).withHandoffs(toPhysics()),
      LoopAgent(physics, physicsModel, physicsTools)
    )
    val runtime = GraphRuntime(InMemoryCheckpointer())

    runtime.start(thread, graph.graph, AgentInput("one"), RunConfig()).awaited.value.completed
    val (state, output) = runtime.start(thread, graph.graph, AgentInput("two"), RunConfig()).awaited.value.completed
    output shouldBe TurnOutput(TurnOutcome.Completed, physics)
    triageModel.calls shouldBe 1
    physicsModel.calls shouldBe 2
    messagesOf(state).last shouldBe AssistantMessage("second")
  }

  it should "let the target hand back to the source within one turn" in {
    val triageModel  = ScriptedModel(handoff("h1", physics), answer("back with triage"))
    val physicsModel = ScriptedModel(handoff("h2", triage))
    val graph = loop(
      LoopAgent(triage, triageModel, triageTools).withHandoffs(toPhysics()),
      LoopAgent(physics, physicsModel, physicsTools).withHandoffs(Vector(LoopHandoff(triage, None, true)))
    )

    val (state, output) = runInMemory(graph.graph, AgentInput("go")).completed
    output shouldBe TurnOutput(TurnOutcome.Completed, triage)
    triageModel.calls shouldBe 2
    physicsModel.calls shouldBe 1
    messagesOf(state) should contain(ToolMessage("Transferred to triage", "h2"))
    Message.validateConversation(messagesOf(state).toList).value shouldBe (())
  }

  it should "refuse a handoff mixed with other calls: every call errors, nothing runs, the same agent is asked again" in {
    val count = new AtomicInteger()
    val mixed: Vector[Message] => AssistantMessage = _ =>
      AssistantMessage(
        None,
        Seq(
          ToolCall("r1", "route", ujson.Obj("text" -> "x")),
          ToolCall("h1", "handoff_to_physics", ujson.Obj("reason" -> "y"))
        )
      )
    val triageModel  = ScriptedModel(mixed, answer("ok, no handoff"))
    val physicsModel = ScriptedModel()
    val graph = loop(
      LoopAgent(triage, triageModel, ToolSet.of(tool("route", count)).value).withHandoffs(toPhysics()),
      LoopAgent(physics, physicsModel, physicsTools)
    )

    val (state, output) = runInMemory(graph.graph, AgentInput("go")).completed
    output shouldBe TurnOutput(TurnOutcome.Completed, triage)
    count.get shouldBe 0
    physicsModel.calls shouldBe 0
    val error =
      ujson
        .Obj("error" -> "A handoff must be the only tool call in a message; no call in this message was run")
        .render()
    triageModel.seen.get(1).takeRight(2) shouldBe Vector(ToolMessage(error, "r1"), ToolMessage(error, "h1"))
    Message.validateConversation(messagesOf(state).toList).value shouldBe (())
    state.get(LoopKeys.turn).value.steps shouldBe 2
  }

  it should "refuse two handoffs in one message" in {
    val both: Vector[Message] => AssistantMessage = _ =>
      AssistantMessage(
        None,
        Seq(
          ToolCall("h1", "handoff_to_physics", ujson.Obj("reason" -> "y")),
          ToolCall("h2", "handoff_to_physics", ujson.Obj("reason" -> "z"))
        )
      )
    val triageModel  = ScriptedModel(both, answer("fine"))
    val physicsModel = ScriptedModel()
    val graph = loop(
      LoopAgent(triage, triageModel, triageTools).withHandoffs(toPhysics()),
      LoopAgent(physics, physicsModel, physicsTools)
    )

    val (state, output) = runInMemory(graph.graph, AgentInput("go")).completed
    output shouldBe TurnOutput(TurnOutcome.Completed, triage)
    physicsModel.calls shouldBe 0
    messagesOf(state).collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("h1", "h2")
  }

  it should "send the last question, then the transfer onwards, to a target without preserved context" in {
    val triageModel  = ScriptedModel(call("r1", "route"), handoff("h1", physics))
    val physicsModel = ScriptedModel(answer("fresh"), answer("still fresh"))
    val graph = loop(
      LoopAgent(triage, triageModel, triageTools).withHandoffs(toPhysics(preserve = false)),
      LoopAgent(physics, physicsModel, physicsTools).withSystemPrompt(Some("Do physics."))
    )
    val runtime = GraphRuntime(InMemoryCheckpointer())

    val (state, _) = runtime.start(thread, graph.graph, AgentInput("go"), RunConfig()).awaited.value.completed
    val transfer   = AssistantMessage(None, Seq(ToolCall("h1", "handoff_to_physics", ujson.Obj("reason" -> "physics"))))
    physicsModel.seen.get(0) shouldBe Vector(
      SystemMessage("Do physics."),
      UserMessage("go"),
      transfer,
      ToolMessage("Transferred to physics", "h1")
    )
    // the history itself is untouched
    messagesOf(state).head shouldBe UserMessage("go")
    messagesOf(state).size shouldBe 6
    state.get(LoopKeys.transfer).value.map(t => (t.source, t.target)) shouldBe Some((triage, physics))

    runtime.start(thread, graph.graph, AgentInput("again"), RunConfig()).awaited.value.completed
    physicsModel.seen.get(1) shouldBe Vector(
      SystemMessage("Do physics."),
      UserMessage("go"),
      transfer,
      ToolMessage("Transferred to physics", "h1"),
      AssistantMessage("fresh"),
      UserMessage("again")
    )
  }

  it should "restrict the view by the recorded transfer, whatever transfers the imported history holds" in {
    val triageModel  = ScriptedModel(handoff("h1", physics))
    val physicsModel = ScriptedModel(answer("fresh"))
    val graph = loop(
      LoopAgent(triage, triageModel, triageTools).withHandoffs(toPhysics(preserve = false)),
      LoopAgent(physics, physicsModel, physicsTools)
    )
    val imported = Vector(
      UserMessage("earlier"),
      AssistantMessage(None, Seq(ToolCall("h0", "handoff_to_physics", ujson.Obj("reason" -> "old")))),
      ToolMessage("Transferred to physics", "h0"),
      AssistantMessage("old answer")
    )

    runInMemory(graph.graph, AgentInput("go", imported)).completed
    physicsModel.seen.get(0) shouldBe Vector(
      UserMessage("go"),
      AssistantMessage(None, Seq(ToolCall("h1", "handoff_to_physics", ujson.Obj("reason" -> "physics")))),
      ToolMessage("Transferred to physics", "h1")
    )
  }

  it should "fail, rather than send the full history, for a non-root agent with no recorded transfer" in {
    val physicsModel = ScriptedModel(answer("first"), answer("never"))
    val agents = Vector(
      LoopAgent(triage, ScriptedModel(), triageTools).withHandoffs(toPhysics(preserve = false)),
      LoopAgent(physics, physicsModel, physicsTools)
    )
    // the same graph id and version, rooted at physics and then at triage
    val asPhysics = ToolLoop.build("handoffs", "1", physics, agents).value
    val asTriage  = ToolLoop.build("handoffs", "1", triage, agents).value
    val runtime   = GraphRuntime(InMemoryCheckpointer())

    runtime.start(thread, asPhysics.graph, AgentInput("one"), RunConfig()).awaited.value.completed
    val (_, error) = runtime.start(thread, asTriage.graph, AgentInput("two"), RunConfig()).awaited.value.failed
    error.message should include("no transfer to it is recorded")
    physicsModel.calls shouldBe 1
  }

  it should "count each agent's model call against the turn's shared step limit" in {
    val triageModel  = ScriptedModel(handoff("h1", physics))
    val physicsModel = ScriptedModel(call("c1", "calculate"), answer("never"))
    val graph = loop(
      LoopAgent(triage, triageModel, triageTools).withHandoffs(toPhysics()).withMaxSteps(2),
      LoopAgent(physics, physicsModel, physicsTools).withMaxSteps(2)
    )

    val (state, output) = runInMemory(graph.graph, AgentInput("go")).completed
    output shouldBe TurnOutput(TurnOutcome.StepLimitReached, physics)
    physicsModel.calls shouldBe 1
    state.get(LoopKeys.turn).value shouldBe TurnState(2, Some(TurnOutcome.StepLimitReached), Some(triage))
  }

  it should "apply the current agent's limit to the turn's shared step count" in {
    val triageModel  = ScriptedModel(call("r1", "route"), handoff("h1", physics))
    val physicsModel = ScriptedModel(answer("never"))
    val graph = loop(
      LoopAgent(triage, triageModel, triageTools).withHandoffs(toPhysics()).withMaxSteps(10),
      LoopAgent(physics, physicsModel, physicsTools).withMaxSteps(2)
    )

    val (state, output) = runInMemory(graph.graph, AgentInput("go")).completed
    output shouldBe TurnOutput(TurnOutcome.StepLimitReached, physics)
    physicsModel.calls shouldBe 0
    state.get(LoopKeys.turn).value shouldBe TurnState(2, Some(TurnOutcome.StepLimitReached), Some(triage))
  }

  it should "refuse two handoffs to different targets in one message" in {
    val chemistry = AgentId.unsafe("chemistry")
    val both: Vector[Message] => AssistantMessage = _ =>
      AssistantMessage(
        None,
        Seq(
          ToolCall("h1", "handoff_to_physics", ujson.Obj("reason" -> "y")),
          ToolCall("h2", "handoff_to_chemistry", ujson.Obj("reason" -> "z"))
        )
      )
    val triageModel    = ScriptedModel(both, answer("fine"))
    val physicsModel   = ScriptedModel()
    val chemistryModel = ScriptedModel()
    val graph = loop(
      LoopAgent(triage, triageModel, triageTools)
        .withHandoffs(toPhysics() :+ LoopHandoff(chemistry, None, preserveContext = true)),
      LoopAgent(physics, physicsModel, physicsTools),
      LoopAgent(chemistry, chemistryModel)
    )

    val (state, output) = runInMemory(graph.graph, AgentInput("go")).completed
    output shouldBe TurnOutput(TurnOutcome.Completed, triage)
    physicsModel.calls shouldBe 0
    chemistryModel.calls shouldBe 0
    val error =
      ujson
        .Obj("error" -> "A handoff must be the only tool call in a message; no call in this message was run")
        .render()
    messagesOf(state).collect { case t: ToolMessage => t } shouldBe Vector(
      ToolMessage(error, "h1"),
      ToolMessage(error, "h2")
    )
    state.get(LoopKeys.transfer).value shouldBe None
  }

  it should "never run a handoff stand-in through the call-tool path" in {
    // physics has no handoff to triage: its call to triage's handoff tool is an unknown tool
    val physicsModel = ScriptedModel(handoff("h2", triage), answer("ok"))
    val graph = ToolLoop
      .build(
        "handoffs",
        "1",
        physics,
        Vector(
          LoopAgent(triage, ScriptedModel(), triageTools).withHandoffs(toPhysics()),
          LoopAgent(physics, physicsModel, physicsTools)
        )
      )
      .value

    val (state, output) = runInMemory(graph.graph, AgentInput("go")).completed
    output shouldBe TurnOutput(TurnOutcome.Completed, physics)
    messagesOf(state).collect { case t: ToolMessage => t.content } shouldBe Vector(
      ujson.Obj("error" -> "Unknown tool 'handoff_to_triage'").render()
    )
  }

  "HandoffTools" should "offer a stand-in with a required reason and the handoff's description" in {
    val Vector(withReason, without) =
      HandoffTools.tools(Vector(LoopHandoff(physics, Some("Physics."), true), LoopHandoff(triage, None, false)))
    withReason.spec.name shouldBe "handoff_to_physics"
    withReason.spec.description shouldBe "Hand off this query to a specialist agent. Physics."
    without.spec.description shouldBe "Hand off this query to a specialist agent."
    withReason.spec.argumentSchema("required") shouldBe ujson.Arr("reason")
  }

  "ToolLoop.build" should "refuse a handoff to an agent outside the family" in {
    val stranger = AgentId.unsafe("stranger")
    ToolLoop
      .build(
        "handoffs",
        "1",
        triage,
        Vector(LoopAgent(triage, ScriptedModel()).withHandoffs(Vector(LoopHandoff(stranger, None, true))))
      )
      .left
      .value
      .message should include("'stranger'")
  }

  it should "refuse a handoff tool name clashing with one of the agent's tools" in {
    val clash = ToolSet.of(tool("handoff_to_physics")).value
    ToolLoop
      .build(
        "handoffs",
        "1",
        triage,
        Vector(
          LoopAgent(triage, ScriptedModel(), clash).withHandoffs(toPhysics()),
          LoopAgent(physics, ScriptedModel())
        )
      )
      .left
      .value
      .message should include("handoff_to_physics")
  }

  it should "refuse a handoff from an agent to itself" in {
    ToolLoop
      .build(
        "handoffs",
        "1",
        triage,
        Vector(LoopAgent(triage, ScriptedModel()).withHandoffs(Vector(LoopHandoff(triage, None, true))))
      )
      .left
      .value
      .message should include("agent 'triage' hands off to itself")
  }

  it should "refuse two handoffs to the same target from one agent" in {
    ToolLoop
      .build(
        "handoffs",
        "1",
        triage,
        Vector(
          LoopAgent(triage, ScriptedModel()).withHandoffs(toPhysics() ++ toPhysics(preserve = false)),
          LoopAgent(physics, ScriptedModel())
        )
      )
      .left
      .value
      .message should include("more than one handoff to 'physics'")
  }
}
