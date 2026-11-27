package org.llm4s.vectorstore.elasticsearch

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.security.Secret
import org.llm4s.vectorstore.VectorStore
import org.llm4s.vectorstore.testkit.VectorStoreContract
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Try

/**
 * `ElasticsearchVectorStore` against a real Elasticsearch passes [[VectorStoreContract]] (#3000), but for the known
 * failures below. Every case has an index of its own, removed by `clear()` afterwards.
 *
 * To run (see [[ElasticsearchVectorStoreSpec]] for the container):
 * {{{
 * export ELASTICSEARCH_TEST_URL=http://localhost:9200
 * sbt "it/testOnly org.llm4s.vectorstore.elasticsearch.ElasticsearchVectorStoreContractSpec"
 * }}}
 */
@Docker
class ElasticsearchVectorStoreContractSpec extends AnyFunSuite with VectorStoreContract {

  protected def newStore(): VectorStore = {
    val url = sys.env.get("ELASTICSEARCH_TEST_URL").filter(_.nonEmpty)
    Tier.require(url.isDefined, "ELASTICSEARCH_TEST_URL not set")
    val auth = sys.env
      .get("ELASTICSEARCH_TEST_API_KEY")
      .filter(_.nonEmpty)
      .fold[ElasticsearchAuth](ElasticsearchAuth.NoAuth)(k => ElasticsearchAuth.ApiKey(Secret(k)))
    ElasticsearchVectorStore(
      ElasticsearchConfig(baseUrl = url.get, index = s"llm4s-contract-${System.nanoTime()}", auth = auth)
    )
      .fold(e => fail(e.formatted), identity)
  }

  protected def releaseStore(store: VectorStore): Unit = {
    Try(store.clear())
    store.close()
  }

  override protected def knownFailures: Map[String, String] = Map(
    "filters select by keys holding a dot, a space or a colon" ->
      "llm4s/llm4s#3167: a key with '.' maps metadata as an object, and a plain key beside it is refused",
    "search scores are (1 + cos) / 2: identical 1, orthogonal 0.5, opposite 0" ->
      "llm4s/llm4s#3006: an opposite vector scores 0.5, not 0",
    "upsertBatch takes a batch larger than one request" ->
      "llm4s/llm4s#3025: the whole batch goes in one _bulk request (HTTP 413)"
  )
}
