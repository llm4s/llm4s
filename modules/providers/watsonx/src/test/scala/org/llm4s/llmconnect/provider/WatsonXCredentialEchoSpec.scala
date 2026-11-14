package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.WatsonXConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.testkit.CredentialEchoChecks
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** A key the IAM endpoint echoes back in its 401 body never reaches the `LLMError` (#1932). */
final class WatsonXCredentialEchoSpec extends AnyFunSuite with Matchers:

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val echoKey = "echo-key-0123456789abcdef"

  test("an echoed key stays out of the error") {
    KnownSecrets.isolated {
      CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
        val config = WatsonXConfig(
          apiKey = Secret(echoKey),
          projectId = "project-1",
          spaceId = None,
          model = "ibm/granite-13b-instruct-v2",
          baseUrl = baseUrl,
          apiVersion = "2026-09-25",
          iamUrl = s"$baseUrl/identity/token",
          contextWindow = 8192,
          reserveCompletion = 1024
        )
        new WatsonXClient(config).complete(Conversation(Seq(UserMessage("Hi"))), CompletionOptions())
      }
    }
  }
