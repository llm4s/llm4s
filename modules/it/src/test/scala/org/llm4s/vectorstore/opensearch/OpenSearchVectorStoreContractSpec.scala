package org.llm4s.vectorstore.opensearch

import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.security.Secret
import org.llm4s.vectorstore.VectorStore
import org.llm4s.vectorstore.testkit.VectorStoreContract
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Try

/**
 * `OpenSearchVectorStore` (Lucene engine, cosine space) against a real OpenSearch passes [[VectorStoreContract]]
 * (#3000), but for the known failures below. Every case has an index of its own, removed by `clear()` afterwards.
 *
 * To run (see `OpenSearchVectorStoreSpec` for the container):
 * {{{
 * export OPENSEARCH_TEST_URL=http://localhost:9201
 * sbt "it/testOnly org.llm4s.vectorstore.opensearch.OpenSearchVectorStoreContractSpec"
 * }}}
 */
@Docker
class OpenSearchVectorStoreContractSpec extends AnyFunSuite with VectorStoreContract {

  protected def newStore(): VectorStore = {
    val url = sys.env.get("OPENSEARCH_TEST_URL").filter(_.nonEmpty)
    Tier.require(url.isDefined, "OPENSEARCH_TEST_URL not set")
    val auth = sys.env
      .get("OPENSEARCH_TEST_USERNAME")
      .filter(_.nonEmpty)
      .fold[OpenSearchAuth](OpenSearchAuth.NoAuth)(
        OpenSearchAuth.Basic(_, Secret(sys.env.getOrElse("OPENSEARCH_TEST_PASSWORD", "")))
      )
    OpenSearchVectorStore(
      OpenSearchConfig(baseUrl = url.get, index = s"llm4s-contract-${System.nanoTime()}", auth = auth)
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
    "upsertBatch takes a batch larger than one request" ->
      "llm4s/llm4s#3025: the whole batch goes in one _bulk request (HTTP 413)"
  )
}
