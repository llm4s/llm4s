# Orchestration Removed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete `org.llm4s.agent.orchestration` (`PlanRunner`, `DAG`, `TypedAgent`, `Policies`, `OrchestrationError`, `MDCContext`, `CancellationToken`) and core's `PlanId`. Make a blocking `Agent.run`/`recover`/`resume` cancel its turn when the caller is interrupted. Add a `multi-agent-graph` cookbook recipe showing the `GraphBuilder` replacement.

**Architecture:** This is a removal, not a rebuild: `GraphBuilder`/`GraphRuntime` already provide typed nodes, joins, parallel supersteps, retry and cancellation. A small fix in `Agent` makes interrupting a graph task cancel the agent turn it is waiting on. A scripted-client recipe demonstrates and tests fan-out, deterministic update order, a static-join barrier, step boundaries and cancellation.

**Tech Stack:** Scala 3.7.1, JDK 21, sbt, ScalaTest, upickle. Modules `modules/agent`, `modules/core`, `modules/samples`.

**Spec:** `docs/superpowers/specs/2026-10-08-orchestration-removed-design.md`. Read it before starting; this plan sequences it.

**Worktree:** `/Users/rory/workspace/llm4s-1330`, branch `feat/1330-orchestration-removed`, cut from `origin/main`. Run every command from there.

## Global Constraints

- **Commits:** every commit uses `git commit -s` (DCO), and its message ends with a blank line and then `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- **Before each commit:** run `sbt scalafmtAll`, and make sure the touched modules' tests pass.
- **No shims:** no `@deprecated` members and no type aliases for removed types (pre-1.0). Delete outright.
- **Banned keywords:** no `try`/`catch`/`finally`. To catch an interrupt, use `scala.util.control.Exception.catching(classOf[InterruptedException]).either(...)`. `Try` and `NonFatal` do **not** catch `InterruptedException`.
- **Banned syntax:** no infix method calls (`a.map(f)`, not `a map f`), and no `sys.env` or `System.getenv`.
- **Interrupt flag:** an interrupted call returns `Left(CancelledError)` and leaves the thread's interrupt flag set. Never clear it on a path that returns to the caller.
- **Package and dependency scope:** keep package names. `llm4s-core` changes only by losing `PlanId`.
- **Historical docs stay as they are:** older CHANGELOG entries, `docs/design/phase-1.3-handoff-mechanism.md`, `docs/design/agent-framework-gap-analysis-deepagents-2026.md`, and the migration-guide sections older than "Stage 1 migration".

## Review Focus

- **An interrupt that arrives just as the turn finishes:** `Agent.run` calls `cancel()` on a run that already completed. Expected: no error, and `run` still returns `Left(CancelledError)` with the flag set; a completed thread stays completed. `cancel()` is a no-op after `Finishing` (pinned by `AgentRunSpec` "a cancel after the turn ended is a no-op"; Task 1 relies on it).
- **An interrupted `recover`:** it must cancel the recovered turn exactly as `run` does, and leave the thread recoverable again. Pinned in Task 1 (second test).
- **One specialist fails, not cancelled:** for example, the model returns `Left`. Expected: the graph run ends `RunResult.Failed` with that error, the editor never runs, and the recipe's `run` returns `Left`. Pinned in Task 3 (fourth test).
- **The specialist that finishes first is not the first in task order:** the `views` order must still be task order. Pinned in Task 3 (first test).
- **Removed types in Scaladoc or docs links:** `docs/doc` must not fail on a dangling `[[PlanRunner]]` link. Task 2 greps for every removed name, and Task 5 runs `docs/doc`.

---

### Task 1: A blocking `Agent` call cancels its turn when its caller is interrupted

**Files:**
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/Agent.scala`: the `run(threadId, query, config, history)`, `recover` and `resume` bodies, plus a private helper and Scaladoc
- Modify: `modules/agent/src/main/scala/org/llm4s/agent/AgentRun.scala:69`: Scaladoc of `await`
- Create: `modules/agent/src/test/scala/org/llm4s/agent/AgentRunCancellationSpec.scala`

**Interfaces:**
- Consumes: `AgentRun.await(): Result[AgentResult]`, `AgentRun.cancel(): Unit`, `org.llm4s.error.CancelledError`
- Produces: `Agent.run`, `continueConversation`, `runMultiTurn`, `recover` and `resume` return `Left(CancelledError)` on interrupt, having cancelled their turn. Public signatures are unchanged. Task 3's cancellation test relies on this.

- [ ] **Step 1: Write the failing test**

Create `modules/agent/src/test/scala/org/llm4s/agent/AgentRunCancellationSpec.scala`:

```scala
package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{ GraphError, ThreadId }
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CountDownLatch, LinkedBlockingQueue, TimeUnit }
import scala.util.control.Exception.catching

class AgentRunCancellationSpec extends AnyFlatSpec with Matchers with Eventually {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds))

  /**
   * A model whose calls numbered in `blockOn` (1-based) block until interrupted, and whose other
   * calls answer "done". `entered` counts blocked calls that started; `interrupted` counts those
   * that saw their interrupt.
   */
  final private class BlockingClient(blockOn: Set[Int]) extends LLMClient {
    private val calls   = new AtomicInteger(0)
    val entered         = new java.util.concurrent.Semaphore(0)
    val interrupted     = new java.util.concurrent.Semaphore(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      if (blockOn.contains(calls.incrementAndGet())) {
        entered.release()
        catching(classOf[InterruptedException]).either(Thread.sleep(60_000)) match {
          case Left(e) =>
            interrupted.release()
            Thread.currentThread().interrupt()
            Left(CancelledError("model call", Some(e)))
          case Right(_) => Left(ValidationError("blocking", "was never interrupted"))
        }
      } else Right(CompletionFixture.simple("done"))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 8192
    override def getReserveCompletion(): Int = 1024
  }

  /** Runs `call` on a new thread, interrupts that thread once `entered` is released, and returns what `call` returned and whether its thread was still interrupted. */
  private def interruptedCall(entered: java.util.concurrent.Semaphore)(call: => Result[AgentResult]): (Result[AgentResult], Boolean) = {
    val outcome = new LinkedBlockingQueue[(Result[AgentResult], Boolean)]()
    val caller  = Thread.ofVirtual().start(() => outcome.offer(call -> Thread.currentThread().isInterrupted): Unit)
    entered.tryAcquire(10, TimeUnit.SECONDS) shouldBe true
    caller.interrupt()
    Option(outcome.poll(10, TimeUnit.SECONDS)).getOrElse(fail("the interrupted call did not return"))
  }

  "Agent.run" should "cancel its turn when the calling thread is interrupted, leaving the thread to recover" in {
    val client = new BlockingClient(blockOn = Set(1))
    val agent  = plain(client)
    val thread = ThreadId("interrupted-run")

    val (result, stillInterrupted) = interruptedCall(client.entered)(agent.run(thread, "q"))

    result.left.toOption.get shouldBe a[CancelledError]
    stillInterrupted shouldBe true
    // the turn itself was cancelled: its blocked model call saw the interrupt...
    client.interrupted.tryAcquire(10, TimeUnit.SECONDS) shouldBe true
    // ...and it ended: the thread is incomplete, not busy with a turn still running
    eventually(cause(agent.run(thread, "again").error) shouldBe a[GraphError.IncompleteRun])
    agent.recover(thread).value.answer shouldBe Some("done")
  }

  "Agent.recover" should "cancel the recovered turn when the calling thread is interrupted" in {
    val client = new BlockingClient(blockOn = Set(1, 2))
    val agent  = plain(client)
    val thread = ThreadId("interrupted-recover")

    interruptedCall(client.entered)(agent.run(thread, "q"))._1.left.toOption.get shouldBe a[CancelledError]
    eventually(cause(agent.run(thread, "again").error) shouldBe a[GraphError.IncompleteRun])

    val (recovered, stillInterrupted) = interruptedCall(client.entered)(agent.recover(thread))

    recovered.left.toOption.get shouldBe a[CancelledError]
    stillInterrupted shouldBe true
    client.interrupted.tryAcquire(2, 10, TimeUnit.SECONDS) shouldBe true
    eventually(cause(agent.run(thread, "again").error) shouldBe a[GraphError.IncompleteRun])
    agent.recover(thread).value.answer shouldBe Some("done")
  }
}
```

- [ ] **Step 2: Run the test and check it fails**

Run: `sbt "agent/testOnly org.llm4s.agent.AgentRunCancellationSpec"`

Expected: both tests FAIL. `client.interrupted.tryAcquire` returns `false` after 10s, because the inner model call keeps sleeping, and `eventually` sees `ThreadBusy`.

If `CompletionFixture.simple`, `plain`, `cause` or `.value`/`.error` are named differently, read `modules/agent/src/test/scala/org/llm4s/agent/TestFixtures.scala` (`object AgentFixture`, `object CompletionFixture`) and adjust the imports, not the behaviour.

- [ ] **Step 3: Implement the fix**

In `Agent.scala`, add `import org.llm4s.error.CancelledError` beside the existing `org.llm4s.error.ValidationError` import, as `import org.llm4s.error.{ CancelledError, ValidationError }`. Then change the three blocking bodies:

```scala
  def run(threadId: ThreadId, query: String, config: RunConfig, history: Seq[Message]): Result[AgentResult] =
    start(threadId, query, config, history).flatMap(awaitOrCancel)
```

```scala
  def recover(threadId: ThreadId, config: RunConfig = RunConfig()): Result[AgentResult] =
    startRecover(threadId, config).flatMap(awaitOrCancel)
```

```scala
  ): Result[AgentResult] =
    startResume(threadId, answers, config).flatMap(awaitOrCancel)
```

Add this private helper next to the other private methods (before `recoverWith`):

```scala
  /**
   * Awaits `run`. An interrupted wait cancels the turn as well, so a blocking call - `run` inside a
   * graph node whose run is cancelled - never leaves its turn running; the interrupt flag stays set.
   */
  private def awaitOrCancel(run: AgentRun): Result[AgentResult] =
    run.await() match
      case cancelled @ Left(_: CancelledError) =>
        run.cancel()
        cancelled
      case other => other
```

Add this paragraph to the end of the Scaladoc of `run(threadId, query, config, history)`, and a one-line equivalent to `recover` and `resume`:

```scala
   * Interrupting the calling thread cancels the turn: `run` returns `Left(CancelledError)` with the
   * interrupt flag still set, and the thread is left for [[recover]]. To keep a turn running past an
   * interrupt, use [[start]] and await the [[AgentRun]].
```

In `AgentRun.scala`, around line 69, the `await` Scaladoc says "`Left(CancelledError)` instead, with the interrupt flag still set, and the turn keeps running: a". Keep that sentence, and add after it, in the same comment:

```scala
   * ([[Agent.run]], [[Agent.recover]] and [[Agent.resume]] cancel it.)
```

- [ ] **Step 4: Run the tests and check they pass**

Run: `sbt "agent/testOnly org.llm4s.agent.AgentRunCancellationSpec org.llm4s.agent.AgentRunSpec org.llm4s.agent.AgentRecoverySpec org.llm4s.agent.AgentSuspensionSpec"`

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
sbt scalafmtAll
git add modules/agent/src/main/scala/org/llm4s/agent/Agent.scala modules/agent/src/main/scala/org/llm4s/agent/AgentRun.scala modules/agent/src/test/scala/org/llm4s/agent/AgentRunCancellationSpec.scala
git commit -s -m "fix(agent): an interrupted Agent.run, recover or resume cancels its turn (#1330)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Delete the orchestration package and `PlanId`

**Files:**
- Delete: `modules/agent/src/main/scala/org/llm4s/agent/orchestration/` (all 7 files)
- Delete: `modules/agent/src/test/scala/org/llm4s/agent/orchestration/` (all 10 files)
- Modify: `modules/core/src/main/scala/org/llm4s/types/package.scala`: remove `PlanId` (around lines 75-81)
- Modify: `modules/core/src/test/scala/org/llm4s/types/TypesSpec.scala`: remove the `PlanId` tests (around line 155)
- Modify: `project/PomDescriptions.scala:22`
- Modify: `docs/guide/error-handling.md:136-138`, the `:176` row and the `:184` row
- Modify: `docs/reference/review-guidelines.md:181`

**Interfaces:**
- Produces: no `org.llm4s.agent.orchestration` package and no `org.llm4s.types.PlanId`. Tasks 4 and 5 rely on these names being gone.

- [ ] **Step 1: Delete the package and its specs**

```bash
git rm -r -q modules/agent/src/main/scala/org/llm4s/agent/orchestration modules/agent/src/test/scala/org/llm4s/agent/orchestration
```

- [ ] **Step 2: Remove `PlanId`**

In `modules/core/src/main/scala/org/llm4s/types/package.scala`, delete the `final case class PlanId(...)` and its `object PlanId { ... }`, including their Scaladoc. In `TypesSpec.scala`, delete the `// PlanId Tests` comment and every test that uses `PlanId`. Then check that nothing still names it:

Run: `git grep -nE '\bPlanId\b' -- modules`

Expected: no output.

- [ ] **Step 3: Find every other reference in code and current docs**

Run: `git grep -nE 'agent\.orchestration|PlanRunner|CancellationToken|TypedAgent|OrchestrationError|MDCContext|Policies\.with' -- modules project build.sbt .scalafix.conf docs/guide docs/reference/review-guidelines.md docs/getting-started docs/examples README.md`

Expected: only `docs/guide/error-handling.md` and `docs/reference/review-guidelines.md`. Any hit in `modules`, `project` or `build.sbt` must be removed too: a Scaladoc `[[...]]` link to a deleted type fails `docs/doc`.

- [ ] **Step 4: Update the POM description and the docs**

In `project/PomDescriptions.scala`, replace:
```
"Agent runtime for LLM4S: agents, guardrails, handoffs, orchestration, streaming and an assistant."
```
with:
```
"Agent runtime for LLM4S: agents, guardrails, handoffs, a typed graph runtime, streaming and an assistant."
```
Then run `git grep -n 'handoffs, orchestration'`. If a test or doc repeats the old string (for example a POM description spec), update it the same way.

In `docs/guide/error-handling.md`:
- Replace the sentence "Orchestration fails with `OrchestrationError.NodeExecutionError` or `PlanExecutionError`, and a graph's tool loop with `GraphError.ToolFailed`; both are described below." with: "A graph's tool loop fails with `GraphError.ToolFailed`, described below."
- Delete the table row that begins `| \`llm4s-agent\` | \`org.llm4s.agent.orchestration\` |`.
- Delete the table row that begins `| \`llm4s-agent\` (\`org.llm4s.agent.orchestration\`) |`.
- Read the surrounding prose and make sure it still makes sense (for example, a count of families). Fix anything that no longer matches.

In `docs/reference/review-guidelines.md`, change the `NoKeywordTry` row's exempt column from `` `core.safety`, `agent.orchestration` `` to `` `core.safety` ``, keeping the column alignment.

- [ ] **Step 5: Compile and test what changed**

Run: `sbt "core/testOnly org.llm4s.types.TypesSpec" agent/test "agent/scalafix --check" "core/scalafix --check"`

Expected: all PASS. `agent/test` loses the 10 deleted specs and nothing else.

- [ ] **Step 6: Commit**

```bash
sbt scalafmtAll
git add -A modules project docs/guide/error-handling.md docs/reference/review-guidelines.md
git commit -s -m "feat(agent)!: remove PlanRunner, DAG, TypedAgent, Policies and CancellationToken (#1330)

The orchestration package passed Map[String, Any] between nodes and cast
each node to TypedAgent[Any, Any]; GraphBuilder covers it with typed
handles. Core's PlanId, used only by PlanRunner, goes with it.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Cookbook recipe `multi-agent-graph`

**Files:**
- Create: `modules/samples/src/main/scala/org/llm4s/samples/cookbook/MultiAgentGraphRecipe.scala`
- Modify: `modules/samples/src/main/scala/org/llm4s/samples/cookbook/Recipe.scala`: `Cookbook.apps`
- Create: `modules/samples/src/test/scala/org/llm4s/samples/cookbook/MultiAgentGraphRecipeSpec.scala`
- Modify: `docs/examples/cookbook.md`: intro count, a new section 6, and the "More recipes" count

**Interfaces:**
- Consumes:
  - Task 1's `Agent.run` cancellation.
  - `GraphBuilder(id, version)`, `.node[I](id, writes)(GraphNode[I])`, `.staticJoin(id, sources, target)`, `.compile(entry)(ThreadState => Result[O])`.
  - `StateKey.replace[A](id, initial)` and `StateKey.appending[A](id)`.
  - `Command.empty.update(key, value).goto(ref)` and `NodeResult.Continue`/`NodeResult.fromResult`.
  - `GraphRuntime.inMemory()`, `.start(threadId, graph, input): Result[RunHandle[O]]`, `.subscribe(threadId)(StreamEvent => Unit): Result[Subscription]`.
  - `RunHandle.await(): Result[RunResult[O]]` and `.cancel()`.
  - `RunResult.Completed(state, output, supersteps)`, `RunResult.Failed(state, error)`, `RunResult.Suspended(...)`.
  - `StreamEvent.Durable(record: EventRecord)`, where `record.event: RunEvent` and `record.nodeId: Option[String]`.
  - `ScriptedClient((Conversation, Int) => Result[AssistantMessage])` with `.calls`.
  - `AgentResults.requireCompleted(result): Result[String]`.
- Produces:
  - `object MultiAgentGraphRecipe extends RecipeApp`, with `val question: String`, `val OptimistPrompt`, `val SkepticPrompt` and `val EditorPrompt: String`.
  - `val ReviewThread: ThreadId`.
  - `def graph(client: LLMClient): Result[CompiledGraph[String, Review]]`.
  - `def start(runtime: GraphRuntime, client: LLMClient, question: String): Result[RunHandle[Review]]`.
  - `def run(client: LLMClient, question: String): Result[Review]`.
  - `def runTraced(client: LLMClient, question: String): Result[(Review, Vector[String])]`.
  - `def script: ScriptedClient` and `def demo(client: LLMClient): Result[String]`.
  - `final case class View(specialist: String, text: String)` and `final case class Review(answer: String, views: Vector[View])`, top-level in the package.

- [ ] **Step 1: Write the failing spec**

Create `modules/samples/src/test/scala/org/llm4s/samples/cookbook/MultiAgentGraphRecipeSpec.scala`:

```scala
package org.llm4s.samples.cookbook

import org.llm4s.agent.graph.{ GraphError, GraphRuntime, RunResult }
import org.llm4s.error.{ CancelledError, ExecutionError }
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, MessageRole, SystemMessage }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.util.control.Exception.catching

/** Runs the multi-agent graph recipe against scripted clients, with no network and no key. */
class MultiAgentGraphRecipeSpec extends AnyFlatSpec with Matchers with EitherValues {
  import MultiAgentGraphRecipe.{ EditorPrompt, OptimistPrompt, SkepticPrompt, question }

  private def systemPrompt(conversation: Conversation): String =
    conversation.messages.collectFirst { case s: SystemMessage => s.content }.getOrElse("")

  private def editorCalls(client: ScriptedClient): Vector[Conversation] =
    client.calls.map(_._1).filter(c => systemPrompt(c) == EditorPrompt)

  "MultiAgentGraphRecipe.run" should "collect the views in task order, whichever specialist answers first" in {
    // the optimist is first in task order but answers last: it waits for the skeptic's reply
    val skepticAnswered = new CountDownLatch(1)
    val client = new ScriptedClient((conversation, _) =>
      systemPrompt(conversation) match {
        case OptimistPrompt =>
          skepticAnswered.await(5, TimeUnit.SECONDS): Unit
          Right(AssistantMessage("for it"))
        case SkepticPrompt =>
          skepticAnswered.countDown()
          Right(AssistantMessage("against it"))
        case _ => Right(AssistantMessage("adopt it, carefully"))
      }
    )

    val review = MultiAgentGraphRecipe.run(client, question).value

    review.views shouldBe Vector(View("optimist", "for it"), View("skeptic", "against it"))
    review.answer shouldBe "adopt it, carefully"
  }

  it should "run the editor once, after both specialists, with both views" in {
    val client = MultiAgentGraphRecipe.script

    MultiAgentGraphRecipe.run(client, question).value

    val editor = editorCalls(client)
    editor should have size 1
    val brief = editor.head.messages.filter(_.role == MessageRole.User).last.content
    brief should include(question)
    brief should include("optimist:")
    brief should include("skeptic:")
    // the editor is the last call: both specialist calls came before it
    systemPrompt(client.calls.last._1) shouldBe EditorPrompt
  }

  "MultiAgentGraphRecipe.runTraced" should "commit one checkpoint per superstep: brief, both specialists, editor" in {
    val (_, events) = MultiAgentGraphRecipe.runTraced(MultiAgentGraphRecipe.script, question).value

    val steps = events.dropWhile(_ == "RunStarted")
    steps.take(2) shouldBe Vector("TaskCompleted@brief", "CheckpointCommitted")
    // the specialists share a superstep, so they complete in either order before its one commit
    steps.slice(2, 4).toSet shouldBe Set("TaskCompleted@optimist", "TaskCompleted@skeptic")
    steps.drop(4) shouldBe Vector("CheckpointCommitted", "TaskCompleted@editor", "CheckpointCommitted", "RunCompleted")
  }

  "A specialist that fails" should "fail the run before the editor is asked" in {
    val client = new ScriptedClient((conversation, _) =>
      systemPrompt(conversation) match {
        case SkepticPrompt => Left(ExecutionError("the skeptic is unavailable", "complete"))
        case _             => Right(AssistantMessage("for it"))
      }
    )

    MultiAgentGraphRecipe.run(client, question).isLeft shouldBe true
    editorCalls(client) shouldBe empty
  }

  "Cancelling the graph run" should "cancel the specialist agent it is waiting on" in {
    val entered     = new CountDownLatch(1)
    val interrupted = new CountDownLatch(1)
    val client = new ScriptedClient((conversation, _) =>
      systemPrompt(conversation) match {
        case SkepticPrompt =>
          entered.countDown()
          catching(classOf[InterruptedException]).either(Thread.sleep(60_000)) match {
            case Left(e) =>
              interrupted.countDown()
              Thread.currentThread().interrupt()
              Left(CancelledError("model call", Some(e)))
            case Right(_) => Right(AssistantMessage("never interrupted"))
          }
        case _ => Right(AssistantMessage("for it"))
      }
    )

    val handle = MultiAgentGraphRecipe.start(GraphRuntime.inMemory(), client, question).value
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    handle.cancel()

    handle.await().value match {
      case RunResult.Failed(_, _: GraphError.Cancelled) => succeed
      case other                                        => fail(s"expected a cancelled run, got $other")
    }
    // the skeptic agent's own turn was cancelled too, not left sleeping
    interrupted.await(10, TimeUnit.SECONDS) shouldBe true
  }

  "The demo" should "print the views and the editor's answer" in {
    val text = MultiAgentGraphRecipe.demo(MultiAgentGraphRecipe.script).value
    text should include("optimist")
    text should include("skeptic")
    text should include("Editor:")
  }
}
```

- [ ] **Step 2: Run the spec and check it fails**

Run: `sbt "samples/testOnly org.llm4s.samples.cookbook.MultiAgentGraphRecipeSpec"`

Expected: compilation FAILS because `MultiAgentGraphRecipe`, `View` and `Review` are not found.

- [ ] **Step 3: Write the recipe**

Create `modules/samples/src/main/scala/org/llm4s/samples/cookbook/MultiAgentGraphRecipe.scala`:

```scala
package org.llm4s.samples.cookbook

import org.llm4s.agent.Agent
import org.llm4s.agent.graph._
import org.llm4s.error.ExecutionError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole, SystemMessage }
import org.llm4s.samples.util.AgentResults
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, TimeUnit }
import scala.jdk.CollectionConverters._

/** One specialist's view of the question. */
final case class View(specialist: String, text: String) derives ReadWriter

/** The editor's answer and the views it weighed, in task order. */
final case class Review(answer: String, views: Vector[View])

/**
 * Recipe: several agents in one graph.
 *
 * Two specialist agents answer the same question in parallel, and an editor agent combines their views
 * once both have answered. The graph runtime runs the specialists in one superstep, applies their
 * updates in task order whichever finishes first, and holds the editor behind a static join until
 * both have committed. Cancelling the run cancels the agents it is waiting on. Run it with
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.cookbook.MultiAgentGraphRecipe"          # scripted client, no API key
 *   sbt "samples/runMain org.llm4s.samples.cookbook.MultiAgentGraphRecipe --live"   # your configured provider
 * }}}
 */
object MultiAgentGraphRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "multi-agent-graph",
    title = "Several agents in one graph",
    summary = "Ask two specialist agents in parallel, then let an editor agent combine their views once both have answered.",
    mainClass = "org.llm4s.samples.cookbook.MultiAgentGraphRecipe"
  )

  val question: String = "Should our team adopt Scala 3 for its next service?"

  val OptimistPrompt: String = "You are an optimist. Give the strongest case for the idea, in two sentences."
  val SkepticPrompt: String  = "You are a skeptic. Give the strongest case against the idea, in two sentences."
  val EditorPrompt: String   = "You are an editor. Weigh the views you are given and recommend one course of action."

  /** The thread the recipe runs on. */
  val ReviewThread: ThreadId = ThreadId("review-1")

  // snippet:start
  private val questionKey = StateKey.replace[String]("question", "")
  private val views       = StateKey.appending[View]("views")
  private val answer      = StateKey.replace[String]("answer", "")

  /** Runs `agent` on `query`; a turn that does not complete fails the node. */
  private def ask(agent: Agent, query: String): Result[String] =
    agent.run(query).flatMap(AgentResults.requireCompleted)

  def graph(client: LLMClient): Result[CompiledGraph[String, Review]] =
    for {
      optimist <- Agent.builder("optimist", client).withSystemPrompt(OptimistPrompt).build()
      skeptic  <- Agent.builder("skeptic", client).withSystemPrompt(SkepticPrompt).build()
      editor   <- Agent.builder("editor", client).withSystemPrompt(EditorPrompt).build()
      b = GraphBuilder("multi-agent-review", "v1")
      // each specialist reads the question and appends its view; both run in the same superstep
      specialist = (name: String, agent: Agent) =>
        b.node[Unit](name, writes = Set(views)) { (_, state, _) =>
          NodeResult.fromResult(for {
            q    <- state.get(questionKey)
            text <- ask(agent, q)
          } yield Command.empty.update(views, View(name, text)))
        }
      optimistNode = specialist("optimist", optimist)
      skepticNode  = specialist("skeptic", skeptic)
      editorNode = b.node[Unit]("editor", writes = Set(answer)) { (_, state, _) =>
        NodeResult.fromResult(for {
          q    <- state.get(questionKey)
          vs   <- state.get(views)
          text <- ask(editor, (s"Question: $q" +: vs.map(v => s"${v.specialist}: ${v.text}")).mkString("\n"))
        } yield Command.empty.update(answer, text))
      }
      brief = b.node[String]("brief", writes = Set(questionKey)) { (q, _, _) =>
        NodeResult.Continue(Command.empty.update(questionKey, q).goto(optimistNode).goto(skepticNode))
      }
      // the editor runs once both specialists have committed
      _ = b.staticJoin("views", Set(optimistNode, skepticNode), editorNode)
      compiled <- b.compile(brief)(state => state.get(answer).flatMap(a => state.get(views).map(Review(a, _))))
    } yield compiled

  def start(runtime: GraphRuntime, client: LLMClient, question: String): Result[RunHandle[Review]] =
    graph(client).flatMap(g => runtime.start(ReviewThread, g, question))
  // snippet:end

  def run(client: LLMClient, question: String): Result[Review] =
    start(GraphRuntime.inMemory(), client, question).flatMap(_.await()).flatMap(completed)

  /**
   * [[run]], also returning the run's durable events as `Kind` or `Kind@node`, in order. The events are
   * delivered on the subscription's own thread, so this waits for the run's last event before returning.
   */
  def runTraced(client: LLMClient, question: String): Result[(Review, Vector[String])] = {
    val runtime = GraphRuntime.inMemory()
    val events  = new ConcurrentLinkedQueue[String]()
    val ended   = new CountDownLatch(1)
    for {
      subscription <- runtime.subscribe(ReviewThread) {
        case StreamEvent.Durable(record) =>
          events.add(record.event.productPrefix + record.nodeId.fold("")(n => s"@$n")): Unit
          record.event match {
            case RunEvent.RunCompleted | RunEvent.RunCancelled | RunEvent.RunTimedOut | _: RunEvent.RunFailed |
                _: RunEvent.RunSuspended =>
              ended.countDown()
            case _ => ()
          }
        case _ => ()
      }
      ran    <- start(runtime, client, question).flatMap(_.await())
      _ = ended.await(5, TimeUnit.SECONDS): Unit
      _ = subscription.cancel()
      review <- completed(ran)
    } yield (review, events.asScala.toVector)
  }

  private def completed(result: RunResult[Review]): Result[Review] = result match {
    case RunResult.Completed(_, review, _) => Right(review)
    case RunResult.Failed(_, error)        => Left(error)
    case _: RunResult.Suspended            => Left(ExecutionError("the review suspended", "graph.run"))
  }

  /** Each agent answers from its system prompt; the editor repeats the views it was given. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val prompt = conversation.messages.collectFirst { case s: SystemMessage => s.content }.getOrElse("")
    val asked  = conversation.messages.filter(_.role == MessageRole.User).lastOption.map(_.content).getOrElse("")
    Right(prompt match {
      case OptimistPrompt => AssistantMessage("Scala 3 gives the team safer, clearer code from day one.")
      case SkepticPrompt  => AssistantMessage("Hiring and library support for Scala 3 still lag behind.")
      case _ =>
        AssistantMessage(s"Adopt it for one service first. I weighed ${asked.linesIterator.size - 1} views.")
    })
  })

  def demo(client: LLMClient): Result[String] =
    runTraced(client, question).map { case (review, events) =>
      (Vector("Events:") ++ events.map("  " + _) ++
        review.views.map(v => s"${v.specialist}: ${v.text}") :+
        s"Editor: ${review.answer}").mkString("\n")
    }
}
```

Notes for the implementer:
- Keep only imports that compile warning-free; the build may have `-Wunused`.
- **The for-comprehension builds the graph with `=` bindings because `GraphBuilder` is mutable.** If the compiler rejects `specialist = (name, agent) => b.node[Unit](...) { ... }` (SAM conversion of the lambda into `GraphNode[Unit]` inside a function literal), give the lambda an explicit type: `{ (_: Unit, state: ThreadState, _: RunContext) => ... }`. Do this the way `JoinSpec` passes lambdas to `b.node`.
- If a `RunEvent` terminal case named above does not exist (for example `RunTimedOut`), match the cases that `modules/agent/src/main/scala/org/llm4s/agent/graph/RunEvent.scala` defines. The goal is to count down on every event that ends a run.
- If `runTraced`'s event order in Step 4 differs from the spec's expectation, read `GraphRuntimeSpec` "run a new thread to completion" (it asserts `TaskCompleted@plan, CheckpointCommitted, ..., CheckpointCommitted, RunCompleted`). Fix the recipe, not the expectation, unless the runtime's documented sequence really differs. In that case update the spec test and say why in the commit message.

- [ ] **Step 4: Register the recipe and run the spec**

In `Recipe.scala`, change `Cookbook.apps` to:

```scala
  val apps: Seq[RecipeApp] =
    Seq(ToolCallingRecipe, StructuredOutputRecipe, GuardrailsRecipe, DocumentQaRecipe, MemoryRecipe, MultiAgentGraphRecipe)
```

Run: `sbt "samples/testOnly org.llm4s.samples.cookbook.MultiAgentGraphRecipeSpec"`

Expected: all 6 tests PASS.

- [ ] **Step 5: Add the cookbook page section**

In `docs/examples/cookbook.md`:
- In the intro, change "Five complete recipes" to "Six complete recipes".
- Under "More recipes", change "These five are a start" to "These six are a start", and "copy one of the five files" to "copy one of the six files".
- Insert this section before `## More recipes`, after the memory recipe's "Watch out" paragraph. The scala block must be exactly the text between `// snippet:start` and `// snippet:end` in the source with its indentation removed (`CookbookDocsSpec` compares them):

````markdown
<!-- recipe: multi-agent-graph -->
## 6. Several agents in one graph

Ask two specialist agents the same question in parallel, then let an editor agent combine their views once both have answered. The graph runtime runs both specialists in one superstep, applies their updates in task order whichever finishes first, and a static join holds the editor until both have committed. This replaces `PlanRunner` and `TypedAgent`, removed in [#1330](https://github.com/llm4s/llm4s/issues/1330).

Run it:

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.MultiAgentGraphRecipe"
sbt "samples/runMain org.llm4s.samples.cookbook.MultiAgentGraphRecipe --live"
```

The core of the recipe:

```scala
<the snippet, copied from the source>
```

The whole file, with the scripted client: [`MultiAgentGraphRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/MultiAgentGraphRecipe.scala).

What the spec checks:

- the views come back in task order even when the first specialist answers last
- the editor is asked once, after both specialists, with both views
- each superstep ends in one checkpoint: the brief, both specialists together, then the editor
- a specialist that fails fails the run before the editor is asked
- cancelling the run cancels the specialist agent it is waiting on

**Watch out:** A node that calls `agent.run` blocks its task until the agent's turn ends, so cancelling the graph run cancels that turn. A node that calls `agent.start` and returns without awaiting it leaves the turn running: cancel it yourself.
````

Replace `<the snippet, copied from the source>` with the real snippet text. Do not leave the placeholder in.

- [ ] **Step 6: Run the cookbook specs**

Run: `sbt "samples/testOnly org.llm4s.samples.cookbook.*"`

Expected: all PASS, including `CookbookDocsSpec` (page markers, snippet equality, registry, and running every recipe).

- [ ] **Step 7: Commit**

```bash
sbt scalafmtAll
git add modules/samples docs/examples/cookbook.md
git commit -s -m "feat(samples): multi-agent graph cookbook recipe (#1330)

Two specialist agents in one superstep, a static join to an editor agent,
deterministic update order, step boundaries and cancellation; the
replacement for PlanRunner, and #1326's parallel update sample.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

If `sbt scalafmtAll` reformats the snippet region, rerun Step 6 and re-copy the snippet into the page if they differ.

---

### Task 4: Migration guide, CHANGELOG and design record

**Files:**
- Modify: `docs/reference/migration.md`: a new subsection at the end of "## Stage 1 migration: agent runtime", just before `## Agent middleware`, plus the intro sentence
- Modify: `CHANGELOG.md`: the `[Unreleased]` `### Added`, `### Changed` and `### Removed` sections
- Modify: `docs/design/typed-agent-runtime-design.md`: §4.4 (line ~472), the §4.9 rows (lines ~662-663), §4.14 (line ~906), and a new §4.15 after §4.14
- Modify: `docs/design/agent-framework-roadmap.md`: lines ~243-244, the ~498-504 table rows, and the ~1931-1934 tree

**Interfaces:**
- Consumes: the names removed in Task 2, and the recipe and `Agent.run` behaviour from Tasks 1 and 3.

- [ ] **Step 1: Migration subsection**

In `docs/reference/migration.md`, in the first paragraph of "Stage 1 migration", replace "The migration slices that follow (#1329, events and tracing; #1330, orchestration) extend this note." with "#1329 (events and tracing) and #1330 (orchestration, below) extend this note."

Then insert before `## Agent middleware`:

````markdown
### Orchestration removed (#1330)

`org.llm4s.agent.orchestration` is deleted: `PlanRunner`, `Plan`, `Node`, `Edge`, `TypedAgent`, `Policies`, `OrchestrationError` and `CancellationToken`, with `org.llm4s.types.PlanId` from `llm4s-core`. `PlanRunner` passed `Map[String, Any]` between nodes and cast each node to `TypedAgent[Any, Any]`. A typed graph does the same job with checked handles, checkpoints and recovery. The [multi-agent graph recipe](../examples/cookbook.html#6-several-agents-in-one-graph) is a worked replacement.

| Removed | Use instead |
|---|---|
| `TypedAgent[I, O]`, `TypedAgent.fromFunction` and the other factories | a `GraphNode[I]` given to `GraphBuilder.node`; call an `Agent` inside the node for an LLM step |
| `Node`, `Edge`, `Plan`, `Plan.builder` | `GraphBuilder.node` / `edge` / `staticJoin` / `dynamicJoin`, then `compile(entry)(output)` |
| `PlanRunner.execute(plan, inputs, token)` | `GraphRuntime.start(threadId, graph, input).flatMap(_.await())` |
| `PlanRunner(maxConcurrentNodes)` | `RunConfig` with `RunBudgets(maxConcurrency = n)` |
| `Policies.withRetry` | `retry = RetryPolicy(...)` on `GraphBuilder.node` / `implement` |
| `Policies.withTimeout` | `RunBudgets.withTimeout` (the whole run); a node bounds its own calls |
| `Policies.withFallback` | ordinary `Result` code in the node (`primary.orElse(fallback)`) |
| `OrchestrationError` | `GraphError` |
| `CancellationToken` | `RunHandle.cancel()` / `AgentRun.cancel()`, or interrupting the calling thread |
| `PlanId` | `RunId` |

~~~scala
// before
val plan   = Plan.builder.addNode(research).addNode(summary).addEdge(Edge("e", research, summary)).build
val result = PlanRunner().execute(plan, Map("research" -> question), token)   // Future[Result[Map[String, Any]]]

// after
val b        = GraphBuilder("research", "v1")
val findings = StateKey.replace[String]("findings", "")
val summary  = b.node[Unit]("summary") { (_, state, _) => /* read findings, run an agent */ ??? }
val research = b.node[String]("research", writes = Set(findings)) { (q, _, _) =>
  NodeResult.fromResult(researcher.run(q).flatMap(AgentResults.requireCompleted).map(f =>
    Command.empty.update(findings, f).goto(summary)))
}
val handle = b.compile(research)(_.get(findings)).flatMap(GraphRuntime.inMemory().start(ThreadId("t-1"), _, question))
handle.foreach(_.cancel())   // instead of token.cancel()
~~~

`Agent.run`, `continueConversation`, `runMultiTurn`, `recover` and `resume` now cancel their turn when the calling thread is interrupted, so cancelling a graph run also cancels the agent turns its nodes are waiting on. Before, the turn kept running after `run` returned `Left(CancelledError)`. A caller that wants the turn to outlive an interrupt uses `start`, `startRecover` or `startResume`, and awaits the `AgentRun` itself.
````

`AgentResults` is a samples helper. If the snippet should not depend on it, write `.flatMap(_.answer.toRight(...))` instead. Keep the snippet short, and mark it as illustrative with `???` as shown.

- [ ] **Step 2: CHANGELOG**

Under `## [Unreleased]`:

In `### Removed`, add as the first bullet:
```markdown
- **Orchestration: `PlanRunner`, `DAG`, `TypedAgent`, `Policies`, `OrchestrationError` and `CancellationToken`**
  ([#1330](https://github.com/llm4s/llm4s/issues/1330)): `org.llm4s.agent.orchestration` is deleted, with
  `org.llm4s.types.PlanId` from `llm4s-core`. Build the same flows with `GraphBuilder` and run them on
  `GraphRuntime`; cancel with `RunHandle.cancel()`. See the migration guide's "Orchestration removed (#1330)" and the
  `multi-agent-graph` cookbook recipe.
```

In `### Changed`, add as the first bullet:
```markdown
- **An interrupted `Agent.run`, `recover` or `resume` cancels its turn** ([#1330](https://github.com/llm4s/llm4s/issues/1330)):
  the call returns `Left(CancelledError)` with the interrupt flag set, as before, and now also cancels the turn it
  was waiting on instead of leaving it running. Cancelling a graph run therefore cancels the agent turns its nodes
  are waiting on. Use `start`/`startRecover`/`startResume` and await the `AgentRun` to keep a turn past an interrupt.
```

In `### Added`, add as the first bullet:
```markdown
- **Cookbook recipe: several agents in one graph** ([#1330](https://github.com/llm4s/llm4s/issues/1330)):
  `MultiAgentGraphRecipe` runs two specialist agents in one superstep and an editor agent behind a static join,
  and its spec checks update order, the barrier, step boundaries and cancellation with no API key.
```

- [ ] **Step 3: Design record**

In `docs/design/typed-agent-runtime-design.md`:
- **§4.4:** replace "- `CancellationToken` remains for `PlanRunner` until it is rebuilt." with "- `CancellationToken` was deleted with `PlanRunner` (#1330, §4.15)."
- **§4.9 first row:** replace "`PlanRunner` rebuilt or removed (#1330)" with "`PlanRunner` rebuilt or removed (**closed by #1330**: removed, §4.15)".
- **§4.9 second row:** replace `| Delete \`CancellationToken\` with the \`PlanRunner\` rebuild |` with `| ~~Delete \`CancellationToken\` with the \`PlanRunner\` rebuild~~ **closed by #1330** (§4.15) |`.
- **§4.14:** replace "- `PlanRunner`, `DAG`, `TypedAgent` and `CancellationToken` are #1330." with "- `PlanRunner`, `DAG`, `TypedAgent` and `CancellationToken`: removed by #1330 (§4.15)."
- **New section** after §4.14's last line, before the next `##`/`###` heading:

```markdown
### 4.15 Stage 1 slice 4: orchestration removed ([#1330](https://github.com/llm4s/llm4s/issues/1330))

`org.llm4s.agent.orchestration` is deleted rather than rebuilt: `PlanRunner`, `Plan`/`Node`/`Edge`, `TypedAgent`, `Policies`, `OrchestrationError`, `MDCContext` and `CancellationToken`, with core's `PlanId`. `PlanRunner.execute` took and returned `Map[String, Any]` and cast every node to `TypedAgent[Any, Any]`; that boundary was its contract, and §7 and §8 say to remove it rather than preserve it. `GraphBuilder` already gives typed nodes, edges, static and dynamic joins, parallel supersteps, per-node retry and cache, `RunBudgets.maxConcurrency` and `timeout`, and cancellation through `RunHandle`. A typed DSL over it would be a second public way to build the same graphs, and would overlap Stage 4's typed delegation. Nothing outside the package used it.

- **A blocking agent call cancels its turn when interrupted.** `Agent.run` (and with it `continueConversation` and `runMultiTurn`), `recover` and `resume` await their `AgentRun`. When the wait is interrupted they cancel the turn and return `Left(CancelledError)` with the flag still set. An agent called inside a graph node is therefore cancelled with the graph run. This is the synchronous case of "cancelling a run cancels the child runs it started" (§4.9); a node that `start`s a turn without awaiting it, and child runs on another runtime, remain Stage 3. `start`/`AgentRun.await` are unchanged.
- **`multi-agent-graph` cookbook recipe.** Two specialist agents in one superstep append to one key, and a static join releases an editor agent. Its spec checks deterministic update order with the first task finishing last, the barrier, one checkpoint per superstep, failure before the join, and cancellation reaching the specialists' agent turns. It is the parallel update sample of Stage 1's exit criteria (§6).

The specs are `AgentRunCancellationSpec` and `MultiAgentGraphRecipeSpec`.
```

In `docs/design/agent-framework-roadmap.md`:
- Replace "- ✅ Concurrency control (maxConcurrentNodes)" with "- ✅ Concurrency control (`RunBudgets.maxConcurrency`; `PlanRunner` removed in #1330)".
- Replace "- ✅ Cancellation support (CancellationToken)" with "- ✅ Cancellation support (`RunHandle.cancel`, thread interruption; `CancellationToken` removed in #1330)".
- In the comparison table, change the cells `✅ DAG-based with \`PlanRunner\`` to `✅ Typed graph runtime (\`GraphBuilder\`)`, `✅ \`maxConcurrentNodes\`` to `✅ \`RunBudgets.maxConcurrency\``, and `✅ \`CancellationToken\`` to `✅ \`RunHandle.cancel\``.
- Leave the ~1931 directory tree as is. It is a historical proposal of new files.

- [ ] **Step 4: Check no current doc still presents the removed API as live**

Run: `git grep -nE 'PlanRunner|CancellationToken|TypedAgent|OrchestrationError|agent\.orchestration|PlanId' -- docs README.md ':!docs/superpowers' ':!docs/design/phase-1.3-handoff-mechanism.md' ':!docs/design/agent-framework-gap-analysis-deepagents-2026.md'`

Expected:
- **`migration.md`:** hits only in the new subsection, the older pass sections, and line ~674 (the pass-8 package table, historical).
- **Design docs:** hits only where Step 3 put them, plus §6/§8/§9 mentions of Stage 4's future `TypedAgent[I, O]`, which stay.
- **`CHANGELOG.md`** is outside the grep; its old entries stay.

Anything else that presents the types as current needs fixing.

- [ ] **Step 5: Commit**

```bash
git add docs/reference/migration.md CHANGELOG.md docs/design/typed-agent-runtime-design.md docs/design/agent-framework-roadmap.md
git commit -s -m "docs(agent): migration, CHANGELOG and design record for removing orchestration (#1330)

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Whole-build verification

**Files:** none, unless a check fails, in which case fix it in the file it names.

- [ ] **Step 1: Format and lint**

Run: `sbt scalafmtCheckAll "agent/scalafix --check" "core/scalafix --check" "samples/scalafix --check"`

Expected: success.

- [ ] **Step 2: Tests of every touched module, and of modules that test against `llm4s-agent`**

Run: `sbt core/test agent/test samples/test javaApi/test llm4sEffect/test llm4sZio/test`

Expected: all PASS. If a project id differs, check `sbt projects` and use the ids for `modules/java-api`, `modules/llm4s-effect` and `modules/llm4s-zio`.

- [ ] **Step 3: Build checks that catch removed public types**

Run: `sbt stabilityTierCheck frozenDependencyCheck docs/doc`

Expected: success. `docs/doc` fails on a Scaladoc link to a deleted type; fix it at its source. If `stabilityTierCheck` or the documented-tier guard (`.github` workflow from #1422) lists `org.llm4s.agent.orchestration` types or `PlanId`, remove those entries.

Run: `git grep -nE 'orchestration\.|PlanId' -- .github docs/reference project`

Expected: no hit naming the deleted package or `PlanId`.

- [ ] **Step 4: Commit any fixes**

Commit only if Steps 1-3 needed changes, as a `fix:`/`docs:` commit for #1330 with `-s` and the Co-Authored-By line.
