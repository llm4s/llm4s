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
  // snippet:end

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
