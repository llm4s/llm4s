package org.llm4s.knowledgegraph.neo4j

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.knowledgegraph.storage.{ GraphStore, GraphStoreContract }
import org.llm4s.security.Secret
import org.scalatest.BeforeAndAfterAll

/**
 * [[GraphStoreContract]] against a live Neo4j (#2970): the same suite the in-memory and JSON stores pass. Needs Neo4j
 * on `NEO4J_URI` (default `bolt://localhost:7687`); the tests cancel when none is reachable.
 */
@Docker
class Neo4jGraphStoreContractSpec extends GraphStoreContract with BeforeAndAfterAll {
  import Neo4jGraphStoreContractSpec._

  override def beforeAll(): Unit =
    opened.set(Neo4jGraphStore(neo4jUri, neo4jUser, Secret(neo4jPass)).toOption.filter { s =>
      val reachable = s.stats().isRight
      if (!reachable) s.close()
      reachable
    })

  override def afterAll(): Unit = opened.getAndSet(None).foreach(_.close())

  override protected def storeName: String = "Neo4jGraphStore"

  override protected def withStore(body: GraphStore => Unit): Unit = {
    val store = opened.get()
    Tier.require(store.isDefined, s"No Neo4j reachable at $neo4jUri")
    store.foreach { s =>
      s.executeWrite("MATCH (n:LLM4S) DETACH DELETE n").fold(e => fail(s"Failed to clear graph: $e"), identity)
      body(s)
    }
  }
}

/** The suite's state lives here, not in the class: the contract registers its tests while the class is initialised. */
object Neo4jGraphStoreContractSpec {
  private val neo4jUri  = sys.env.getOrElse("NEO4J_URI", "bolt://localhost:7687")
  private val neo4jUser = sys.env.getOrElse("NEO4J_USER", "neo4j")
  private val neo4jPass = sys.env.getOrElse("NEO4J_PASSWORD", "neo4j")
  private val opened    = new java.util.concurrent.atomic.AtomicReference[Option[Neo4jGraphStore]](None)
}
