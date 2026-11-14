package org.llm4s.sttp

import org.llm4s.http.Llm4sHttpClient
import org.scalatest.flatspec.AnyFlatSpec
import sttp.client4.{ DefaultSyncBackend, SyncBackend }

/** The transport contract against [[SttpLlm4sHttpClient]] over sttp's default synchronous backend. */
class SttpTransportContractSpec extends AnyFlatSpec with HttpTransportContract {
  private lazy val backend: SyncBackend = DefaultSyncBackend()

  override protected def transportName: String        = "SttpLlm4sHttpClient"
  override protected def newClient(): Llm4sHttpClient = new SttpLlm4sHttpClient(backend)

  override def afterAll(): Unit = {
    super.afterAll()
    backend.close()
  }
}
