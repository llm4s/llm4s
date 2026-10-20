package org.llm4s.samples.harness

import org.llm4s.agent.{ Agent, AgentId, AgentResult, AgentStatus }
import org.llm4s.agent.delegation.ChildInterrupt
import org.llm4s.agent.events.{ AgentEvents, SubagentEvents }
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.sqlite.SqliteCheckpointer
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.samples.harness.StoppingCheckpointer.Stop
import org.llm4s.types.Result

import java.nio.file.{ Files, Path }
import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.Using

/**
 * Stage 4's exit example (design §4.49): deep research by parallel specialists, surviving its process stopping.
 *
 * The lead ([[ParallelDeepResearch]]) runs `HarnessProfile.deepAgents` with two typed subagents: a `researcher`,
 * granted the lead's `fetch_page`, and a `critic` with no tools. It gives each question to a researcher, all in one
 * message, so they run in parallel, at most two at once; the example prints the merged stream - the lead's text, and
 * each child's start, text and end - as it is written.
 *
 * The first "process" stops for good once the first child's report is committed to the lead, while the other children
 * are still running: its store goes away, as after `kill -9`. The second opens the same SQLite file and, once the dead
 * process's claims have lapsed, `recover`s the lead: the finished child is not run again, and the running ones continue
 * from their checkpoints. A fetch from a site outside the allowlist asks you, from inside the child, through the lead.
 * The pages are canned, so only the model needs a network.
 *
 * {{{
 * # the default provider of modules/samples/src/main/resources/application.conf (ollama-local), or e.g.
 * export LLM4S_PROVIDER=openai-main   # a section of application.local.conf; OPENAI_API_KEY set
 * sbt "samples/runMain org.llm4s.samples.harness.ParallelDeepResearchExample"
 * }}}
 *
 * `ParallelDeepResearchAcceptanceSpec` runs the same agent on a scripted model, and asserts each step.
 */
object ParallelDeepResearchExample {

  private val thread = ThreadId("parallel-research")
  private val config = RunConfig().withTenantId(TenantId("research-team"))
  // short, so the example does not wait long for the stopped process's claims to lapse
  private val claims = ClaimPolicy(ttl = 3.seconds, renewEvery = 1.second)

  def main(args: Array[String]): Unit = {
    val dir = Files.createTempDirectory("llm4s-parallel-research")
    println(s"Files: $dir\n")
    val outcome = for {
      providerConfig  <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerConfig)
      lead   <- AgentId.of(ParallelDeepResearch.AgentName)
      db = dir.resolve("research.db")
      stopped <- processOne(client, lead, db)
      _       <- if (stopped) processTwo(client, db) else Right(())
    } yield ()
    outcome.left.foreach(e => println(s"Failed: ${e.formatted}"))
  }

  /** Runs the research until the first child's report is committed to the lead, then stops; whether it stopped. */
  private def processOne(client: LLMClient, lead: AgentId, db: Path): Result[Boolean] = {
    println("--- process 1: research, until the first child's report is committed ---")
    SqliteCheckpointer.open(db).flatMap { sqlite =>
      Using.resource(sqlite) { s =>
        val store =
          new StoppingCheckpointer(s, c => Option.when(StoppingCheckpointer.ranTool(c, lead))(Stop.AfterWriting))
        for {
          agent <- ParallelDeepResearch.agent(client, GraphRuntime(store, claims = claims))
          run   <- agent.stream(thread, ParallelDeepResearch.Query, config, nested = true)(printing)
        } yield run.await() match {
          case Left(e) =>
            println(s"\n--- process 1 stopped (${e.message}) ---\n")
            store.stopped
          case Right(r) =>
            println(s"\n--- the lead ended without a child's report: ${r.status} ---")
            false
        }
      }
    }
  }

  /** Opens the same file in a fresh runtime, recovers the lead, answers what it asks, and prints the children. */
  private def processTwo(client: LLMClient, db: Path): Result[Unit] = {
    println("--- process 2: the same file, a fresh runtime ---")
    SqliteCheckpointer.open(db).flatMap { sqlite =>
      Using.resource(sqlite) { s =>
        for {
          agent  <- ParallelDeepResearch.agent(client, GraphRuntime(s, claims = claims))
          result <- recoverWhenFree(agent, System.nanoTime() + 30.seconds.toNanos).flatMap(answering(agent, _))
          kids   <- agent.children(thread, config)
        } yield {
          println(s"\n\nRecovered: ${result.status}")
          result.decodeOutput[DeepResearch.Brief].foreach(b => println(s"Brief: $b"))
          kids.foreach(c => println(s"child ${c.origin.agent.value} ${c.threadId.value}: ${c.status}"))
        }
      }
    }
  }

  /** Resumes the lead while it is suspended on a child's question, with your answer to each. */
  @annotation.tailrec
  private def answering(agent: Agent, result: AgentResult): Result[AgentResult] =
    result.status match {
      case s: AgentStatus.Suspended if s.delegated.nonEmpty =>
        val answers = s.delegated.map { (id, request) =>
          request.request match {
            case ChildInterrupt.Approval(_, approval) =>
              // the person decides: Ask is a review, never an allow
              print(s"\n${request.child.agent.value} asks to run: ${approval.call.arguments.render()}\nApprove? [y/N] ")
              Option(StdIn.readLine()).map(_.trim.toLowerCase) match {
                case Some("y") | Some("yes") => result.approve(id)
                case _                       => result.reject(id, "the reviewer did not approve this fetch")
              }
            case other => result.reject(id, s"the example answers only approvals, not $other")
          }
        }.toMap
        agent.streamResume(thread, answers, config, nested = true)(printing).flatMap(_.await()) match {
          case Right(next) => answering(agent, next)
          case left        => left
        }
      case _ => Right(result)
    }

  /** Prints the lead's text, and each child's start, text and end, in the order the merged stream delivers them. */
  private val printing: EventHandlers[Unit] = EventHandlers
    .on(AgentEvents.TextDelta)(d => print(d.text))
    .on(SubagentEvents.TaskStarted)(t => println(s"\n[${t.child.agent.value} started] ${t.child.threadId.value}"))
    .on(SubagentEvents.TaskEnded)(t => println(s"\n[${t.child.agent.value} ${t.status}] in ${t.duration.toMillis} ms"))
    .onChild(AgentEvents.TextDelta)((child, d) => print(s"<${child.agent.value}>${d.text}"))

  /**
   * `streamRecover` (no interrupt is pending, so not `resume`), retried while the stopped process's claim is still
   * live, until `deadline`.
   */
  @annotation.tailrec
  private def recoverWhenFree(agent: Agent, deadline: Long): Result[AgentResult] =
    agent.streamRecover(thread, config, nested = true)(printing).flatMap(_.await()) match {
      case Left(_: GraphError.ThreadBusy) if System.nanoTime() < deadline =>
        Thread.sleep(500)
        recoverWhenFree(agent, deadline)
      case other => other
    }
}
