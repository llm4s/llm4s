package org.llm4s.llmconnect.auth.vault

import org.llm4s.error.{ AuthenticationError, NotFoundError }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.asKey
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.*

/**
 * [[VaultApiKeyProvider]] against a real Vault (#2479): the KV v2 engine `vault server -dev` mounts at `secret/`,
 * written through Vault's HTTP API and read back through the provider, including a rotation.
 *
 * Set `VAULT_TEST_ADDR` and `VAULT_TEST_TOKEN` to enable it; without them every test is cancelled:
 * {{{
 * docker run -p 8200:8200 -e VAULT_DEV_ROOT_TOKEN_ID=llm4s-it-root-token hashicorp/vault:1.18
 * export VAULT_TEST_ADDR=http://localhost:8200 VAULT_TEST_TOKEN=llm4s-it-root-token
 * sbt "it/testOnly org.llm4s.llmconnect.auth.vault.VaultApiKeyProviderIntegrationSpec"
 * }}}
 */
@Docker
class VaultApiKeyProviderIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val vault = for {
    address <- sys.env.get("VAULT_TEST_ADDR").filter(_.nonEmpty)
    token   <- sys.env.get("VAULT_TEST_TOKEN").filter(_.nonEmpty)
  } yield (address.stripSuffix("/"), token)

  private val http   = Llm4sHttpClient.create()
  private val prefix = s"llm4s-it/${System.nanoTime()}"

  override def afterAll(): Unit = http.close()

  private def withVault(test: (String, String) => Unit): Unit = {
    Tier.require(vault.isDefined, "VAULT_TEST_ADDR and VAULT_TEST_TOKEN not set")
    vault.foreach { case (address, token) => test(address, token) }
  }

  /** Writes a new version of the KV v2 secret at `secret/<path>`. */
  private def write(address: String, token: String, path: String, data: (String, String)*): Unit = {
    val body = ujson.write(ujson.Obj("data" -> ujson.Obj.from(data.map { case (k, v) => k -> ujson.Str(v) })))
    val response = http
      .post(s"$address/v1/secret/data/$path", Map("X-Vault-Token" -> token), body)
      .fold(e => fail(e.formatted), identity)
    withClue(response.body)(response.statusCode shouldBe 200)
  }

  "VaultApiKeyProvider" should {

    "read the API key Vault holds and, after the refresh interval, the rotated one" in withVault { (address, token) =>
      val path = s"$prefix/openai"
      write(address, token, path, "apiKey" -> "sk-it-first-key", "note" -> "other field")
      val settings = VaultSettings(address, Secret(token), path, "apiKey").withRefreshInterval(500.millis)
      val keys     = VaultApiKeyProvider(settings).fold(e => fail(e.formatted), identity)

      keys.current().map(_.asKey) shouldBe Right("sk-it-first-key")
      write(address, token, path, "apiKey" -> "sk-it-rotated-key")
      // Still the cached key inside the interval.
      keys.current().map(_.asKey) shouldBe Right("sk-it-first-key")

      Thread.sleep(700)
      keys.current().map(_.asKey) shouldBe Right("sk-it-rotated-key")
      keys.close()
    }

    "answer a token Vault refuses with an AuthenticationError that does not hold it" in withVault { (address, token) =>
      val path = s"$prefix/forbidden"
      write(address, token, path, "apiKey" -> "sk-it-forbidden-key")
      val badToken = "hvs.llm4s-it-not-a-token"
      val keys = VaultApiKeyProvider(VaultSettings(address, Secret(badToken), path, "apiKey"))
        .fold(e => fail(e.formatted), identity)

      val error = keys.current().left.toOption.getOrElse(fail("a refused token read a key"))
      error shouldBe a[AuthenticationError]
      (error.formatted should not).include(badToken)
      (error.formatted should not).include("sk-it-forbidden-key")
      keys.close()
    }

    "answer a path with no secret with a NotFoundError" in withVault { (address, token) =>
      val keys = VaultApiKeyProvider(VaultSettings(address, Secret(token), s"$prefix/missing", "apiKey"))
        .fold(e => fail(e.formatted), identity)

      keys.current().left.toOption.getOrElse(fail("read a key that does not exist")) shouldBe a[NotFoundError]
      keys.close()
    }
  }
}
