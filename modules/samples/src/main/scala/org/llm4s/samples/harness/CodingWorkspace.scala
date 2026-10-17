package org.llm4s.samples.harness

import org.llm4s.agent.Agent
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.middleware.{ GuardrailMiddleware, PermissionRule }
import org.llm4s.agent.graph.tool.Operation
import org.llm4s.agent.guardrails.builtin.SecretLeakGuardrail
import org.llm4s.agent.harness._
import org.llm4s.agent.harness.memory.SemanticMemoryMiddleware
import org.llm4s.agent.harness.workspace.{ WorkspaceFileBackend, WorkspaceSandboxBackend }
import org.llm4s.agent.memory.{ MemoryStore, SimpleMemoryManager }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.shared.WorkspaceAgentInterface
import org.llm4s.types.Result

/**
 * The coding agent of Stage 3's second exit example (design §4.38): the harness over a workspace.
 *
 *   - '''Files''': the workspace's (`WorkspaceFileBackend`, F2), with a scratch mount at `/notes` in thread state
 *     (`StateFileBackend`), which a checkpoint holds and a Block restores. The workspace's own `AGENTS.md` is the
 *     agent's standing instructions.
 *   - '''Execution''': `execute` over the workspace's sandbox (`WorkspaceSandboxBackend`). A command the agent asks to
 *     run waits for a person's approval: the run suspends, and continues when it is resumed approved.
 *   - '''Tool policy''': reads anywhere, writes only under `/src/` and `/notes/`, `execute` only once approved.
 *   - '''Plan, context''': todos; offload of a command's long output; summarisation of the older conversation.
 *   - '''Memory''': each finished turn is recorded in the tenant's semantic memory, outside the guardrail; an answer
 *     that leaks a credential is blocked.
 *   - '''Streaming''': text deltas.
 *
 * Workspace changes are effects outside thread state: a Block cannot undo a file the blocked turn wrote in the
 * workspace (rule 5), which is why scratch work goes to `/notes`. `CodingWorkspaceExample` runs it in a container;
 * `CodingWorkspaceAcceptanceSpec` drives it with a scripted model over an in-memory workspace.
 */
object CodingWorkspace {

  /** The agent's id. */
  val AgentName: String = "coder"

  /** The workspace's id in lock keys. */
  val WorkspaceId: String = "greeter"

  /** The scratch mount, in thread state. */
  val NotesMount: String = "/notes"

  /** The file the example fixes. */
  val SourcePath: String = "/src/Greeter.scala"

  /** The source as it starts: the greeting is misspelt. */
  val BrokenSource: String =
    """object Greeter {
      |  def greeting(name: String): String = s"Helo, $name"
      |}
      |""".stripMargin

  /** The standing instructions the workspace keeps. */
  val Instructions: String =
    """Check a change to src/Greeter.scala with the command: grep -c Hello src/Greeter.scala
      |Keep scratch notes under /notes/. Never put a credential in a file or an answer.
      |""".stripMargin

  /** The query the example asks. */
  val Query: String = "Fix the misspelt greeting in /src/Greeter.scala, add a test for it, and run the check."

  /** The files a fresh workspace holds, by path. */
  def seedFiles: Map[String, String] = Map(SourcePath -> BrokenSource, "/AGENTS.md" -> Instructions)

  /** The permission rules: anything they do not allow is denied, and `execute` is asked about. */
  val rules: Vector[PermissionRule] = Vector(
    PermissionRule.allow(Operation.Read, "/**"),
    PermissionRule.allow(Operation.Write, "/src/**"),
    PermissionRule.allow(Operation.Write, NotesMount + "/**"),
    PermissionRule.ask(Operation.Execute, "**"),
    PermissionRule.allow(Operation.Invoke, "write_todos")
  )

  /** The agent and its notes backend, so a caller can read the notes back from a checkpoint. */
  final case class Coder(agent: Agent, notes: StateFileBackend) {

    /** The notes, by path under [[NotesMount]], as the latest checkpoint of `thread` in `store` holds them. */
    def notesIn(store: Checkpointer, thread: ThreadId): Result[Map[String, String]] =
      store
        .latest(thread)
        .flatMap(_.toRight(ValidationError("thread", s"'${thread.value}' has no checkpoint")))
        .flatMap(latest => agent.loop.graph.restore(latest.checkpoint.snapshot))
        .map(_.state.get(notes.files).getOrElse(Map.empty).map((p, e) => NotesMount + p.value -> e.content))
  }

  /** The harness profile over `workspace`, with `notes` mounted at [[NotesMount]]. */
  def profile(workspace: WorkspaceAgentInterface, notes: StateFileBackend): Result[HarnessProfile] =
    for {
      files   <- WorkspaceFileBackend(WorkspaceId, workspace)
      sandbox <- WorkspaceSandboxBackend(WorkspaceId, workspace)
      mount   <- VPath(NotesMount)
      backend <- CompositeBackend(files, mount -> notes)
    } yield HarnessProfile()
      .withBackend(backend)
      .withSandbox(sandbox)
      .withTodos(TodoConfig())
      .withInstructions(InstructionsConfig())
      // small, so the example shows both: a long command output is offloaded, and a turn of a few steps is
      // summarised
      .withOffload(OffloadConfig(thresholdTokens = 1000))
      .withSummarization(SummarizationConfig(trigger = SummaryTrigger.Messages(12), keepRecent = 4))
      .withPermissions(rules)

  /**
   * The coding agent: `client` is the model, `runtime` the store it checkpoints to, `workspace` the workspace it
   * works in, and `memories` the tenant's memory store (`MemoryFiles.store`).
   */
  def agent(
    client: LLMClient,
    runtime: GraphRuntime,
    workspace: WorkspaceAgentInterface,
    memories: Option[TenantId] => Result[MemoryStore]
  ): Result[Coder] = {
    val notes  = StateFileBackend()
    val memory = new SemanticMemoryMiddleware(memories, (store, _) => SimpleMemoryManager.withStore(store), client)
    for {
      profile <- profile(workspace, notes)
      agent <- Agent
        .builder(AgentName, client)
        .withHarness(profile)
        // memory outside the guardrail, so a blocked answer is never recorded (build() checks it)
        .withMiddleware(memory, new GuardrailMiddleware(Nil, Seq(SecretLeakGuardrail())))
        .withStreaming()
        .withMaxSteps(20)
        .withRuntime(runtime)
        .build()
    } yield Coder(agent, notes)
  }
}
