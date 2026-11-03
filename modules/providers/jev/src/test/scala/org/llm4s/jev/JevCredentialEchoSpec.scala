package org.llm4s.jev

import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.testkit.CredentialEchoChecks
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A key Jev echoes back in its 401 body never reaches the `LLMError` (#1932). */
final class JevCredentialEchoSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val echoKey = "echo-key-0123456789abcdef"

  "JevClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val request = JevRequest("Help!", Map("is_urgent" -> JevQuestion.noul("Does this convey urgency?")))
      JevClient(JevConfig(Secret(echoKey), baseUrl = baseUrl)).value.evaluate(request)
    }
  }
}
