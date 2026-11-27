package org.llm4s.vectorstore.redis

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.vectorstore.VectorStore
import org.llm4s.vectorstore.testkit.VectorStoreContract
import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite

import java.net.URI
import scala.util.Try

/** A `RedisVectorStore` over a fresh index on the server `envVar` names, removed by `clear()` after its case. */
private object RedisContractStores {

  def open(envVar: String): VectorStore = {
    val url = sys.env.get(envVar).filter(_.nonEmpty)
    Tier.require(url.isDefined, s"$envVar not set")
    val uri = new URI(url.get)
    val config = RedisConfig(
      host = uri.getHost,
      port = if (uri.getPort > 0) uri.getPort else 6379,
      index = s"llm4s-contract-${System.nanoTime()}"
    )
    RedisVectorStore(config).fold(e => Assertions.fail(e.formatted), identity)
  }

  /** Redis's known failures. Valkey answers a zero vector's distance with a number, so it has none. */
  val knownFailures: Map[String, String] = Map(
    "a stored zero vector is refused or never fails a search" ->
      "llm4s/llm4s#3006: a zero vector's distance is nan, which the reply parser rejects"
  )

  def release(store: VectorStore): Unit = {
    Try(store.clear())
    store.close()
  }
}

/**
 * `RedisVectorStore` against Redis with the Redis Query Engine passes [[VectorStoreContract]] (#3000), but for the
 * known failures below.
 *
 * To run (see [[RedisVectorStoreSpec]] for the container):
 * {{{
 * export REDIS_TEST_URL=redis://localhost:6379
 * sbt "it/testOnly org.llm4s.vectorstore.redis.RedisVectorStoreContractSpec"
 * }}}
 */
@Docker
class RedisVectorStoreContractSpec extends AnyFunSuite with VectorStoreContract {
  protected def newStore(): VectorStore                     = RedisContractStores.open("REDIS_TEST_URL")
  protected def releaseStore(store: VectorStore): Unit      = RedisContractStores.release(store)
  override protected def knownFailures: Map[String, String] = RedisContractStores.knownFailures
}

/**
 * `RedisVectorStore` against Valkey with `valkey-search` passes [[VectorStoreContract]] (#3000), but for the known
 * failures below. The `valkey-search` in Valkey 9.0 answers only vector queries, without `SORTBY` or wildcards, so
 * the cases that need a query without a vector, and every `Contains` filter, are left out.
 *
 * To run (see [[ValkeyVectorStoreSpec]] for the container):
 * {{{
 * export VALKEY_TEST_URL=redis://localhost:6380
 * sbt "it/testOnly org.llm4s.vectorstore.redis.ValkeyVectorStoreContractSpec"
 * }}}
 */
@Docker
class ValkeyVectorStoreContractSpec extends AnyFunSuite with VectorStoreContract {
  protected def newStore(): VectorStore                = RedisContractStores.open("VALKEY_TEST_URL")
  protected def releaseStore(store: VectorStore): Unit = RedisContractStores.release(store)
  override protected def supportsContains: Boolean     = false
  override protected def unsupported: Map[String, String] = Seq(
    "deleteByFilter removes the matching records and says how many",
    "the filter matrix selects the same records in list",
    "the filter matrix selects the same records in count",
    "the filter matrix selects the same records in deleteByFilter",
    "list returns records in insertion order",
    "list pages hold every record once, without repeats or skips",
    "count counts every record, or those a filter selects"
  ).map(_ -> "valkey-search 9.0 answers only vector queries: no count, list or deleteByFilter over an index").toMap
}
