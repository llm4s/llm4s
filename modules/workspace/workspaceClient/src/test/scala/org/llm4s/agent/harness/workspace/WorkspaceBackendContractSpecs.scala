package org.llm4s.agent.harness.workspace

import org.llm4s.agent.graph.{ ReplaySupport, RunContext }
import org.llm4s.agent.harness.*
import org.llm4s.agent.testkit.{ FileBackendContract, ReplayContract }
import org.llm4s.shared.ExecuteCommandResponse
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** The workspace backends pass C3's file contract and C4's replay contract (design §4.33), over a fake workspace. */
object WorkspaceFixtures:
  def files(workspace: FakeWorkspace, id: String = "ws"): WorkspaceFileBackend =
    WorkspaceFileBackend(id, workspace).fold(e => throw new IllegalStateException(e.message), identity)
  def p(s: String): VPath = VPath(s).fold(e => throw new IllegalArgumentException(e.message), identity)

class WorkspaceFileBackendContractSpec extends AnyFlatSpec with FileBackendContract:
  protected def newBackend(): FileBackend = WorkspaceFixtures.files(new FakeWorkspace())

class WorkspaceFileToolsSpec extends AnyFlatSpec with org.llm4s.agent.testkit.FileToolsContract:
  protected def newBackend(): FileBackend = WorkspaceFixtures.files(new FakeWorkspace())

class ResolvedWorkspaceFileBackendContractSpec extends AnyFlatSpec with FileBackendContract:
  // one workspace per tenant, picked by the run's tenant (rule 8)
  protected def newBackend(): FileBackend =
    val byTenant = new java.util.concurrent.ConcurrentHashMap[Option[String], FileBackend]()
    FileBackend.resolved("workspaces") { ctx =>
      Right(
        byTenant.computeIfAbsent(
          ctx.run.config.tenantId.map(_.value),
          t => WorkspaceFixtures.files(new FakeWorkspace(), t.getOrElse("none"))
        )
      )
    }
  override protected def tenantScoped: Boolean = true

class WorkspaceFileBackendReplaySpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject =
    val workspace = new FakeWorkspace()
    FileBackendContract.EditSubject(
      "a workspace file backend",
      WorkspaceFixtures.files(workspace),
      ReplaySupport.Verifiable,
      (path, text) => workspace.seed(path.value, text),
      path => Right(workspace.text(path.value))
    )

class WorkspaceSandboxBackendReplaySpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject = new ReplayContract.Subject:
    private val ran = Vector.fill(2)(new AtomicInteger())
    private val workspace = new FakeWorkspace(exec = (command, _, _, _) =>
      val count = ran(command.last.asDigit).incrementAndGet()
      ExecuteCommandResponse("local", s"run $count", "", 0, isOutputTruncated = false, duration = 1.milli)
    )
    private val sandbox =
      WorkspaceSandboxBackend("ws", workspace).fold(e => throw new IllegalStateException(e.message), identity)
    def name: String           = "a workspace sandbox backend"
    def support: ReplaySupport = ReplaySupport.Unverifiable
    def perform(context: RunContext, slot: Int): Result[ujson.Value] =
      sandbox
        .execute(s"step $slot", WorkspaceFixtures.p("/"), 1.second, FileContext.withoutState(context))
        .map(r => ujson.Str(r.stdout))
    def observe(slot: Int): Result[String] = Right(ran(slot).get.toString)
    def initial(slot: Int): String         = "0"
    def once(slot: Int): String            = "1"
    def interfere(slot: Int): Unit         = ran(slot).addAndGet(10): Unit
