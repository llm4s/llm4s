package org.llm4s.vectorstore

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.security.Secret
import org.llm4s.vectorstore.testkit.VectorStoreContract
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Try

/**
 * `QdrantVectorStore` against a real Qdrant passes [[VectorStoreContract]] (#3000), but for the known failures below.
 * Every case has a collection of its own, removed by `clear()` afterwards.
 *
 * To run:
 * {{{
 * docker run -p 6333:6333 qdrant/qdrant
 * export QDRANT_TEST_URL=http://localhost:6333
 * sbt "it/testOnly org.llm4s.vectorstore.QdrantVectorStoreContractSpec"
 * }}}
 */
@Docker
class QdrantVectorStoreContractSpec extends AnyFunSuite with VectorStoreContract {

  protected def newStore(): VectorStore = {
    val url = sys.env.get("QDRANT_TEST_URL").filter(_.nonEmpty)
    Tier.require(url.isDefined, "QDRANT_TEST_URL not set")
    QdrantVectorStore(
      url.get,
      s"contract_${System.nanoTime()}",
      sys.env.get("QDRANT_TEST_API_KEY").filter(_.nonEmpty).map(Secret(_))
    ).fold(e => fail(e.formatted), identity)
  }

  protected def releaseStore(store: VectorStore): Unit = {
    Try(store.clear())
    store.close()
  }

  override protected def knownFailures: Map[String, String] = Map(
    "an empty store answers every read with nothing" -> "llm4s/llm4s#3004: a read before the collection exists is Left(Not found)",
    "every delete succeeds on an empty store" -> "llm4s/llm4s#3004: a delete with no collection is Left(Not found)",
    "after clear the store is empty, every delete succeeds and writes are accepted" ->
      "llm4s/llm4s#3004: a delete after clear() is Left(Not found)",
    "deleteByPrefix removes the ids with that literal prefix and says how many" ->
      "llm4s/llm4s#3004: next_page_offset null on the last page is read as a string",
    "the filter matrix selects the same records in list" -> "llm4s/llm4s#3004: HasKey uses is_null and matches nothing",
    "the filter matrix selects the same records in count" -> "llm4s/llm4s#3004: HasKey uses is_null and matches nothing",
    "the filter matrix selects the same records in search" -> "llm4s/llm4s#3004: HasKey uses is_null and matches nothing",
    "the filter matrix selects the same records in deleteByFilter" ->
      "llm4s/llm4s#3004: HasKey uses is_null and matches nothing",
    "filters select by keys holding a dot, a space or a colon"   -> "llm4s/llm4s#3167: a key with '.' is a nested path",
    "Equals matches metadata values exactly, whatever they hold" -> "llm4s/llm4s#3167: a key with '.' is a nested path",
    "search scores are (1 + cos) / 2: identical 1, orthogonal 0.5, opposite 0" ->
      "llm4s/llm4s#3006: Qdrant scores are cos clamped at 0",
    "list returns records in insertion order" -> "llm4s/llm4s#3025: list is in point-id order",
    "list pages hold every record once, without repeats or skips" ->
      "llm4s/llm4s#3004: the row offset is sent as a point id, so every page is the first",
    "upsertBatch takes a batch larger than one request" -> "llm4s/llm4s#3025: the whole batch goes in one request"
  )
}
