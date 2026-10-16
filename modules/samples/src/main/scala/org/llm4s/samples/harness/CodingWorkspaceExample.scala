package org.llm4s.samples.harness

import org.llm4s.agent.{ AgentResult, AgentStatus }
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.sqlite.SqliteCheckpointer
import org.llm4s.agent.harness.HarnessEvents
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.types.Result
import org.llm4s.workspace.ContainerisedWorkspace

import java.nio.file.{ Files, Path }
import scala.util.Using

/**
 * Stage 3's second exit example (design §4.38): a coding agent in a workspace container, whose command waits for
 * approval across a restart.
 *
 * The agent ([[CodingWorkspace]]) edits the workspace's files under rules that allow writes under `/src/` and ask
 * before `execute`. The first "process" runs until the agent asks to run a command: the run suspends, and the process
 * ends. The second opens the same SQLite file in a fresh runtime, approves the command, and `resume`s the run, which
 * runs the command once and finishes. The edited files are then in the workspace directory. The example streams the
 * plan, the tools and the answer as they happen.
 *
 * Needs Docker and the workspace-runner image (`sbt workspaceRunner/Docker/publishLocal`):
 * {{{
 * sbt "samples/runMain org.llm4s.samples.harness.CodingWorkspaceExample llm4s/workspace-runner:<tag> [port]"
 * }}}
 *
 * `CodingWorkspaceAcceptanceSpec` runs the same agent on a scripted model over an in-memory workspace, and
 * `CodingWorkspaceIntegrationSpec` (`sbt testWorkspace`) against the container.
 */
object CodingWorkspaceExample {

  private val thread = ThreadId("fix-greeting")
  private val config = RunConfig().withTenantId(TenantId("platform-team"))

  def main(args: Array[String]): Unit = {
    val outcome = for {
      image <- args.headOption.toRight(ValidationError("args", "usage: CodingWorkspaceExample <image> [port]"))
      port = args.lift(1).flatMap(_.toIntOption).getOrElse(18080)
      providerConfig  <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerConfig)
      _      <- inContainer(client, image, port)
    } yield ()
    outcome.left.foreach(e => println(s"Failed: ${e.formatted}"))
  }

  private def inContainer(client: LLMClient, image: String, port: Int): Result[Unit] = {
    val dir  = Files.createTempDirectory("llm4s-coding-workspace")
    val root = Files.createDirectories(dir.resolve("workspace"))
    CodingWorkspace.seedFiles.foreach { (path, text) =>
      val file = root.resolve(path.stripPrefix("/"))
      Files.createDirectories(file.getParent)
      Files.writeString(file, text)
    }
    val workspace = new ContainerisedWorkspace(root.toString, image, port)
    if (!workspace.startContainer()) Left(ValidationError("workspace", s"could not start a container from $image"))
    else
      // the container is stopped whatever happens
      Using.resource(new AutoCloseable { def close(): Unit = workspace.stopContainer(): Unit }) { _ =>
        for {
          asked <- processOne(client, workspace, dir)
          _     <- asked.fold[Result[Unit]](Right(()))(processTwo(client, workspace, dir, _))
        } yield {
          println(s"\nWorkspace: $root")
          Vector(CodingWorkspace.SourcePath, "/src/GreeterSpec.scala").foreach { path =>
            val file = root.resolve(path.stripPrefix("/"))
            if (Files.exists(file)) println(s"\n== $path ==\n${Files.readString(file)}")
          }
        }
      }
  }

  /** Runs until the agent asks to run a command; the suspended turn, if it asked. */
  private def processOne(
    client: LLMClient,
    workspace: ContainerisedWorkspace,
    dir: Path
  ): Result[Option[AgentResult]] = {
    println("--- process 1: work until a command needs approval ---")
    within(dir) { (store, memories) =>
      for {
        coder  <- CodingWorkspace.agent(client, GraphRuntime(store), workspace, memories.store)
        result <- coder.agent.stream(thread, CodingWorkspace.Query, config)(printing).flatMap(_.await())
      } yield result.status match {
        case AgentStatus.Suspended(approvals, _, _, _) =>
          approvals.foreach((_, request) => println(s"\n[approval] ${request.reason}"))
          println("--- process 1 ends with the turn suspended ---\n")
          Some(result)
        case other =>
          println(s"\n--- the turn ended without asking: $other ---")
          None
      }
    }
  }

  /** Opens the same file in a fresh runtime, approves every pending command, and resumes the turn. */
  private def processTwo(
    client: LLMClient,
    workspace: ContainerisedWorkspace,
    dir: Path,
    asked: AgentResult
  ): Result[Unit] = {
    println("--- process 2: approve, and resume in a fresh runtime ---")
    val answers = asked.status match {
      case AgentStatus.Suspended(approvals, _, _, _) => approvals.map((id, _) => asked.approve(id)).toMap
      case _                                         => Map.empty[InterruptId, ujson.Value]
    }
    within(dir) { (store, memories) =>
      for {
        coder  <- CodingWorkspace.agent(client, GraphRuntime(store), workspace, memories.store)
        result <- coder.agent.streamResume(thread, answers, config)(printing).flatMap(_.await())
      } yield println(s"\n\nResumed: ${result.status}")
    }
  }

  /** `body` over a store on the example's SQLite file and its memory files, both closed afterwards. */
  private def within[A](dir: Path)(body: (SqliteCheckpointer, MemoryFiles) => Result[A]): Result[A] =
    SqliteCheckpointer.open(dir.resolve("coding.db")).flatMap { sqlite =>
      Using.resource(sqlite)(s => Using.resource(new MemoryFiles(dir))(memories => body(s, memories)))
    }

  /** Prints the plan as it changes, each tool as it runs, and the answer as it is written. */
  private val printing: EventHandlers[Unit] = EventHandlers
    .on(HarnessEvents.TodosUpdated)(t => println(s"\n[plan] ${t.todos.map(_.status.wire).mkString(", ")}"))
    .on(AgentEvents.ToolExecuted)(t => println(s"[tool] ${t.tool}"))
    .on(AgentEvents.TextDelta)(d => print(d.text))
}
