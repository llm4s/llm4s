package org.llm4s.samples.harness

import org.llm4s.agent.{ AgentId, AgentResult }
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.sqlite.SqliteCheckpointer
import org.llm4s.agent.harness.HarnessEvents
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.samples.harness.StoppingCheckpointer.Stop
import org.llm4s.types.Result

import java.nio.file.{ Files, Path }
import scala.concurrent.duration._
import scala.util.Using

/**
 * Stage 3's first exit example (design §4.38): a deep-research agent that survives its process stopping.
 *
 * The agent ([[DeepResearch]]) runs `HarnessProfile.deepAgents` on state files, with a skill and an `AGENTS.md` the
 * application ships, permission rules, offload of the long page it fetches, summarisation, semantic memory, a typed
 * answer, and the SQLite checkpointer. The example streams the plan and the answer as they are written.
 *
 * The first "process" stops for good once the agent's first tool result is committed - its store goes away, as after
 * `kill -9`, so it can neither commit nor release its claim. The second opens the same SQLite file and, once the dead
 * process's claim has lapsed, `recover`s the run: what was committed is not done again, and the report the agent
 * writes is read back from the file. The page is canned, so only the model needs a network.
 *
 * {{{
 * # the default provider of modules/samples/src/main/resources/application.conf (ollama-local), or e.g.
 * export LLM4S_PROVIDER=openai-main   # a section of application.local.conf; OPENAI_API_KEY set
 * sbt "samples/runMain org.llm4s.samples.harness.DeepResearchExample"
 * }}}
 *
 * `DeepResearchAcceptanceSpec` runs the same agent on a scripted model, and asserts each step.
 */
object DeepResearchExample {

  private val thread = ThreadId("durable-agents")
  private val config = RunConfig().withTenantId(TenantId("research-team"))
  // short, so the example does not wait long for the stopped process's claim to lapse
  private val claims = ClaimPolicy(ttl = 3.seconds, renewEvery = 1.second)

  def main(args: Array[String]): Unit = {
    val dir = Files.createTempDirectory("llm4s-deep-research")
    println(s"Files: $dir\n")
    val outcome = for {
      providerConfig  <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerConfig)
      agent  <- AgentId.of(DeepResearch.AgentName)
      _      <- research(client, agent, dir)
    } yield ()
    outcome.left.foreach(e => println(s"Failed: ${e.formatted}"))
  }

  private def research(client: LLMClient, agent: AgentId, dir: Path): Result[Unit] = {
    val db = dir.resolve("research.db")
    for {
      stopped <- processOne(client, agent, dir, db)
      _       <- if (stopped) processTwo(client, dir, db) else Right(())
    } yield ()
  }

  /** Runs the research until the first tool result is committed, then stops; whether it stopped. */
  private def processOne(client: LLMClient, agent: AgentId, dir: Path, db: Path): Result[Boolean] = {
    println("--- process 1: research, until the first tool result is committed ---")
    SqliteCheckpointer.open(db).flatMap { sqlite =>
      Using.resource(sqlite) { s =>
        Using.resource(new MemoryFiles(dir)) { memories =>
          val store =
            new StoppingCheckpointer(s, c => Option.when(StoppingCheckpointer.ranTool(c, agent))(Stop.AfterWriting))
          for {
            research <- DeepResearch.agent(client, GraphRuntime(store, claims = claims), memories.store)
            run      <- research.agent.stream(thread, DeepResearch.Query, config)(printing)
          } yield run.await() match {
            case Left(e) =>
              println(s"\n--- process 1 stopped (${e.message}) ---\n")
              store.stopped
            case Right(r) =>
              println(s"\n--- the model answered without calling a tool: ${r.status} ---")
              false
          }
        }
      }
    }
  }

  /** Opens the same file in a fresh runtime, recovers the run, and prints what it left. */
  private def processTwo(client: LLMClient, dir: Path, db: Path): Result[Unit] = {
    println("--- process 2: the same file, a fresh runtime ---")
    SqliteCheckpointer.open(db).flatMap { sqlite =>
      Using.resource(sqlite) { s =>
        Using.resource(new MemoryFiles(dir)) { memories =>
          for {
            research <- DeepResearch.agent(client, GraphRuntime(s, claims = claims), memories.store)
            result   <- recoverWhenFree(research, System.nanoTime() + 30.seconds.toNanos)
            files    <- research.filesIn(s, thread)
          } yield {
            println(s"\n\nRecovered: ${result.status}")
            result.decodeOutput[DeepResearch.Brief].foreach(b => println(s"Brief: $b"))
            files.toVector.sortBy(_._1).foreach((path, text) => println(s"\n== $path ==\n$text"))
          }
        }
      }
    }
  }

  /** Prints the plan as it changes and the answer as it is written. */
  private val printing: EventHandlers[Unit] = EventHandlers
    .on(HarnessEvents.TodosUpdated)(t => println(s"\n[plan] ${t.todos.map(_.status.wire).mkString(", ")}"))
    .on(AgentEvents.ToolExecuted)(t => println(s"[tool] ${t.tool}"))
    .on(AgentEvents.TextDelta)(d => print(d.text))

  /**
   * `streamRecover` (no interrupt is pending, so not `resume`), retried while the stopped process's claim is still
   * live, until `deadline`.
   */
  @annotation.tailrec
  private def recoverWhenFree(research: DeepResearch.Research, deadline: Long): Result[AgentResult] =
    research.agent.streamRecover(thread, config)(printing).flatMap(_.await()) match {
      case Left(_: GraphError.ThreadBusy) if System.nanoTime() < deadline =>
        Thread.sleep(500)
        recoverWhenFree(research, deadline)
      case other => other
    }
}
