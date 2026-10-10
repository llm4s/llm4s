---
layout: page
title: Several agents in one graph
parent: Cookbook
grand_parent: Examples
nav_order: 13
---

# Several agents in one graph
{: .no_toc }

Ask two specialist agents in parallel, then let an editor agent combine their views once both have answered.
{: .fs-6 .fw-300 }

## The problem

Some questions are better answered by several agents with different instructions, and an editor who weighs
their views. The graph runtime runs both specialists in one superstep, applies their updates in task order
whichever finishes first, and a static join holds the editor until both have committed. This replaces `PlanRunner`
and `TypedAgent`, removed in [#1330](https://github.com/llm4s/llm4s/issues/1330).

## The program

The whole program, [`MultiAgentGraphRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/MultiAgentGraphRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

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
 * Two specialist agents answer the same question in parallel, and an editor agent combines their views once both
 * have answered. The graph runtime runs the specialists in one superstep, applies their updates in task order
 * whichever finishes first, and holds the editor behind a static join until both have committed. Cancelling the run
 * cancels the agents it is waiting on. Run it with
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.cookbook.MultiAgentGraphRecipe"          # scripted client, no API key
 *   sbt "samples/runMain org.llm4s.samples.cookbook.MultiAgentGraphRecipe --live"   # your configured provider
 * }}}
 */
object MultiAgentGraphRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "multi-agent-graph",
    title = "Several agents in one graph",
    summary =
      "Ask two specialist agents in parallel, then let an editor agent combine their views once both have answered.",
    mainClass = "org.llm4s.samples.cookbook.MultiAgentGraphRecipe"
  )

  val question: String = "Should our team adopt Scala 3 for its next service?"

  val OptimistPrompt: String = "You are an optimist. Give the strongest case for the idea, in two sentences."
  val SkepticPrompt: String  = "You are a skeptic. Give the strongest case against the idea, in two sentences."
  val EditorPrompt: String   = "You are an editor. Weigh the views you are given and recommend one course of action."

  /** The thread the recipe runs on. */
  val ReviewThread: ThreadId = ThreadId("review-1")

  private val questionKey = StateKey.replace[String]("question", "")
  private val views       = StateKey.appending[View]("views")
  private val answer      = StateKey.replace[String]("answer", "")

  /**
   * Runs `agent` on `query` on a thread of its own, then forgets that thread: the graph's thread is the record. A turn
   * that does not complete fails the node; a failed or cancelled one-shot turn is forgotten by `run` itself.
   */
  private def ask(agent: Agent, query: String): Result[String] =
    agent.run(query).flatMap(turn => agent.forget(turn.threadId).flatMap(_ => AgentResults.requireCompleted(turn)))

  def graph(client: LLMClient): Result[CompiledGraph[String, Review]] =
    for {
      optimist <- Agent.builder("optimist", client).withSystemPrompt(OptimistPrompt).build()
      skeptic  <- Agent.builder("skeptic", client).withSystemPrompt(SkepticPrompt).build()
      editor   <- Agent.builder("editor", client).withSystemPrompt(EditorPrompt).build()
      compiled <- {
        val b = GraphBuilder("multi-agent-review", "v1")
        // each specialist reads the question and appends its view; both run in the same superstep
        def specialist(name: String, agent: Agent): NodeRef[Unit] =
          b.node[Unit](name, writes = Set(views)) { (_, state, _) =>
            NodeResult.fromResult(for {
              q    <- state.get(questionKey)
              text <- ask(agent, q)
            } yield Command.empty.update(views, View(name, text)))
          }
        val optimistNode = specialist("optimist", optimist)
        val skepticNode  = specialist("skeptic", skeptic)
        val editorNode = b.node[Unit]("editor", writes = Set(answer)) { (_, state, _) =>
          NodeResult.fromResult(for {
            q    <- state.get(questionKey)
            vs   <- state.get(views)
            text <- ask(editor, (s"Question: $q" +: vs.map(v => s"${v.specialist}: ${v.text}")).mkString("\n"))
          } yield Command.empty.update(answer, text))
        }
        val brief = b.node[String]("brief", writes = Set(questionKey)) { (q, _, _) =>
          NodeResult.Continue(Command.empty.update(questionKey, q).goto(optimistNode).goto(skepticNode))
        }
        // the editor runs once both specialists have committed
        b.staticJoin("views", Set(optimistNode, skepticNode), editorNode): Unit
        b.compile(brief)(state => state.get(answer).flatMap(a => state.get(views).map(Review(a, _))))
      }
    } yield compiled

  def start(runtime: GraphRuntime, client: LLMClient, question: String): Result[RunHandle[Review]] =
    graph(client).flatMap(g => runtime.start(ReviewThread, g, question))

  def run(client: LLMClient, question: String): Result[Review] =
    start(GraphRuntime.inMemory(), client, question).flatMap(_.await()).flatMap(completed)

  /**
   * [[run]], also returning the run's durable events as `Kind` or `Kind@node`, in order. The events are delivered on
   * the subscription's own thread, so this waits for the run's last event before returning.
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
      ran <- start(runtime, client, question).flatMap(_.await())
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

  /** Each agent answers from its system prompt; the editor says how many views it was given. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val prompt = conversation.messages.collectFirst { case s: SystemMessage => s.content }.getOrElse("")
    val asked  = conversation.messages.filter(_.role == MessageRole.User).lastOption.map(_.content).getOrElse("")
    Right(prompt match {
      case OptimistPrompt => AssistantMessage("Scala 3 gives the team safer, clearer code from day one.")
      case SkepticPrompt  => AssistantMessage("Hiring and library support for Scala 3 still lag behind.")
      case _ => AssistantMessage(s"Adopt it for one service first. I weighed ${asked.linesIterator.size - 1} views.")
    })
  })

  def demo(client: LLMClient): Result[String] =
    runTraced(client, question).map { case (review, events) =>
      ((Vector("Events:") ++ events.map("  " + _) ++ review.views.map(v => s"${v.specialist}: ${v.text}")) :+
        s"Editor: ${review.answer}").mkString("\n")
    }
}
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.MultiAgentGraphRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.MultiAgentGraphRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Run with `--live`, or pass your provider's client to `graph`. The agents can use different clients: a cheaper
model for the specialists and a stronger one for the editor. See
[multi-agent orchestration](../../guide/patterns/multi-agent-orchestration).

## Pitfalls

- A node that calls `agent.run` blocks its task until the agent's turn ends, so cancelling the graph run cancels
  that turn. A node that calls `agent.start` and returns without awaiting it leaves the turn running: cancel it
  yourself.
- A specialist that fails fails the run before the editor is asked.
- Parallel specialists mean parallel model calls: mind your provider's rate limit.

[Back to the cookbook](../cookbook)
