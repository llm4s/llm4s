package org.llm4s.agent.graph.postgres

import org.llm4s.agent.harness.{ FileBackend, StoreFileBackend, StoreLimits }
import org.llm4s.agent.testkit.FileBackendContract
import org.llm4s.it.tags.Docker
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec

/**
 * `/memories/` files over `PostgresCheckpointer.longTermStore` (#2453): `StoreFileBackend` passes the file contract
 * on it as it does on the in-memory store, every operation a tool call of a real agent run - a tree per tenant, two
 * writes of one path in one batch both applied, and a batch over the namespace's byte limit refusing exactly the
 * write that overflows.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]] for the connection.
 */
@Docker
class PostgresStoreFileBackendContractSpec extends AnyFlatSpec with BeforeAndAfterAll with FileBackendContract {

  import PostgresStoreFileBackendContractSpec.stores

  protected def newBackend(): FileBackend =
    StoreFileBackend(stores.fresh(StoreLimits(maxBytes = 200)), Vector("memories"))
      .fold(e => throw new IllegalStateException(e.message), identity)

  override protected def tenantScoped: Boolean = true

  // each item is its key, plus {"content":"..."}: 94 bytes; two fit, the third is over
  override protected def boundedBatch: Option[Vector[(String, String)]] =
    Some(Vector("/a" -> "x" * 78, "/b" -> "x" * 78, "/c" -> "x" * 78))

  override def afterAll(): Unit = {
    stores.close()
    super.afterAll()
  }
}

/** The suite's stores, kept out of the class: the contract's cases are registered while the suite is constructed. */
private object PostgresStoreFileBackendContractSpec {
  val stores: PgLongTermStores = new PgLongTermStores
}
