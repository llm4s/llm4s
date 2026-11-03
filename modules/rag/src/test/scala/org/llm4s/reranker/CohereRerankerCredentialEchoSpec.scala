package org.llm4s.reranker

import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.testkit.CredentialEchoChecks
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A key Cohere echoes back in its 401 body never reaches the `LLMError` (#1932). */
final class CohereRerankerCredentialEchoSpec extends AnyFlatSpec with Matchers {

  private val echoKey = "echo-key-0123456789abcdef"

  "CohereReranker" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      new CohereReranker(RerankProviderConfig(baseUrl, Secret(echoKey), "rerank-english-v3.0"))
        .rerank(RerankRequest(query = "scala", documents = Seq("about java", "about scala"), topK = Some(1)))
    }
  }
}
