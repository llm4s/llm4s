package org.llm4s.llmconnect.auth.aws

import org.llm4s.error.NotFoundError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.asKey
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.duration.*

/**
 * [[AwsSecretsManagerApiKeyProvider]] against moto's Secrets Manager (#2479): secrets created and rotated through the
 * `CreateSecret` and `PutSecretValue` actions, signed as the provider signs, and read back through the provider.
 * moto accepts any credentials, so a refused one is covered by the module's own suite.
 *
 * Set `AWS_SECRETS_MANAGER_TEST_ENDPOINT` to enable it; without it every test is cancelled:
 * {{{
 * docker run -p 5000:5000 motoserver/moto:5.2.5
 * export AWS_SECRETS_MANAGER_TEST_ENDPOINT=http://localhost:5000
 * sbt "it/testOnly org.llm4s.llmconnect.auth.aws.AwsSecretsManagerApiKeyProviderIntegrationSpec"
 * }}}
 */
@Docker
class AwsSecretsManagerApiKeyProviderIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val endpoint    = sys.env.get("AWS_SECRETS_MANAGER_TEST_ENDPOINT").filter(_.nonEmpty).map(_.stripSuffix("/"))
  private val region      = "us-east-1"
  private val credentials = AwsCredentials("AKIAIOSFODNN7EXAMPLE", Secret("llm4s-it-secret-access-key"))
  private val http        = Llm4sHttpClient.create()
  private val prefix      = s"llm4s-it/${System.nanoTime()}"

  override def afterAll(): Unit = http.close()

  private def withMoto(test: String => Unit): Unit = {
    Tier.require(endpoint.isDefined, "AWS_SECRETS_MANAGER_TEST_ENDPOINT not set")
    endpoint.foreach(test)
  }

  /** Calls the Secrets Manager action `action` with `body`, signed with Signature Version 4. */
  private def call(endpoint: String, action: String, body: ujson.Obj): Unit = {
    val uri     = URI.create(s"$endpoint/")
    val payload = ujson.write(body).getBytes(StandardCharsets.UTF_8)
    val headers =
      Map("Content-Type" -> "application/x-amz-json-1.1", "X-Amz-Target" -> s"secretsmanager.$action")
    val signed   = SigV4.sign("POST", uri, headers, payload, credentials, region, "secretsmanager", Instant.now())
    val response = http.postBytes(uri.toString, headers ++ signed, payload).fold(e => fail(e.formatted), identity)
    withClue(response.body)(response.statusCode shouldBe 200)
  }

  private def settings(endpoint: String, id: String): AwsSecretsManagerSettings =
    AwsSecretsManagerSettings(region, credentials, id).withEndpoint(endpoint).withCleartext(true)

  "AwsSecretsManagerApiKeyProvider" should {

    "read the API key Secrets Manager holds and, after the refresh interval, the rotated one" in withMoto { endpoint =>
      val id = s"$prefix/openai"
      call(endpoint, "CreateSecret", ujson.Obj("Name" -> id, "SecretString" -> "sk-it-first-key"))
      val keys = AwsSecretsManagerApiKeyProvider(settings(endpoint, id).withRefreshInterval(500.millis))
        .fold(e => fail(e.formatted), identity)

      keys.current().map(_.asKey) shouldBe Right("sk-it-first-key")
      call(endpoint, "PutSecretValue", ujson.Obj("SecretId" -> id, "SecretString" -> "sk-it-rotated-key"))
      // Still the cached key inside the interval.
      keys.current().map(_.asKey) shouldBe Right("sk-it-first-key")

      Thread.sleep(700)
      keys.current().map(_.asKey) shouldBe Right("sk-it-rotated-key")
      keys.close()
    }

    "read one field of a key/value secret" in withMoto { endpoint =>
      val id = s"$prefix/anthropic"
      call(
        endpoint,
        "CreateSecret",
        ujson.Obj("Name" -> id, "SecretString" -> """{"apiKey":"sk-ant-it-field-key","owner":"llm4s"}""")
      )
      val keys = AwsSecretsManagerApiKeyProvider(settings(endpoint, id).withField("apiKey"))
        .fold(e => fail(e.formatted), identity)

      keys.current().map(_.asKey) shouldBe Right("sk-ant-it-field-key")
      keys.close()
    }

    "answer a secret that does not exist with a NotFoundError" in withMoto { endpoint =>
      val keys = AwsSecretsManagerApiKeyProvider(settings(endpoint, s"$prefix/missing"))
        .fold(e => fail(e.formatted), identity)

      keys.current().left.toOption.getOrElse(fail("read a key that does not exist")) shouldBe a[NotFoundError]
      keys.close()
    }
  }
}
