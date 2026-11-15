package org.llm4s.sttp

import org.llm4s.http.Llm4sHttpClient
import org.scalatest.flatspec.AnyFlatSpec

/** The transport contract against the default client, `Llm4sHttpClient.create()`: the behaviour the adapter matches. */
class JdkTransportContractSpec extends AnyFlatSpec with HttpTransportContract {
  override protected def transportName: String        = "Llm4sHttpClient.create()"
  override protected def newClient(): Llm4sHttpClient = Llm4sHttpClient.create()
}
