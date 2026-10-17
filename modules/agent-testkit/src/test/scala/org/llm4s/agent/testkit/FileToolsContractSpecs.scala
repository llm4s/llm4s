package org.llm4s.agent.testkit

import org.llm4s.agent.harness.*
import org.scalatest.flatspec.AnyFlatSpec

/** The file tools over each shipped backend (§4.32), with the bounds `HarnessBackendContractSpecs` uses. */
class StateFileToolsSpec extends AnyFlatSpec with FileToolsContract:
  protected def newBackend(): FileBackend = StateFileBackend(maxBytes = 200)
  override protected def boundedBatch: Option[Vector[(String, String)]] =
    Some(Vector("/a" -> "x" * 78, "/b" -> "x" * 78, "/c" -> "x" * 78))

class CompositeFileToolsSpec extends AnyFlatSpec with FileToolsContract:
  protected def newBackend(): FileBackend =
    CompositeBackend(
      StateFileBackend(),
      HarnessBackends.p("/memories") -> HarnessBackends.storeFiles(InMemoryLongTermStore())
    )
      .fold(e => throw new IllegalStateException(e.message), identity)

class StoreFileToolsSpec extends AnyFlatSpec with FileToolsContract:
  protected def newBackend(): FileBackend =
    HarnessBackends.storeFiles(InMemoryLongTermStore(StoreLimits(maxBytes = 200)))
  override protected def tenantScoped: Boolean = true
  override protected def boundedBatch: Option[Vector[(String, String)]] =
    Some(Vector("/a" -> "x" * 78, "/b" -> "x" * 78, "/c" -> "x" * 78))

class ResolvedFileToolsSpec extends AnyFlatSpec with FileToolsContract:
  protected def newBackend(): FileBackend =
    val byTenant = new java.util.concurrent.ConcurrentHashMap[Option[String], FileBackend]()
    FileBackend.resolved("by-tenant") { ctx =>
      Right(
        byTenant.computeIfAbsent(
          ctx.run.config.tenantId.map(_.value),
          _ => HarnessBackends.storeFiles(InMemoryLongTermStore())
        )
      )
    }
  override protected def tenantScoped: Boolean = true

class ResolvedStateFileToolsSpec extends AnyFlatSpec with FileToolsContract:
  protected def newBackend(): FileBackend =
    val state = StateFileBackend(maxBytes = 200)
    FileBackend.resolved("state", writes = state.writes)(_ => Right(state))
  override protected def boundedBatch: Option[Vector[(String, String)]] =
    Some(Vector("/a" -> "x" * 78, "/b" -> "x" * 78, "/c" -> "x" * 78))
