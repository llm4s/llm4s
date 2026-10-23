package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.ProviderTimeouts
import org.llm4s.llmconnect.provider.OpenAICompatibleClient.{ Credential, Settings }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/** `OpenAICompatibleClient.Settings` is growth-safe (#2379): built by `apply`, changed by `with*` setters. */
class OpenAICompatibleSettingsSpec extends AnyFlatSpec with Matchers {

  private val base = Settings("p", "P", "m", "https://h/v1", Credential.Anonymous, 8192, 1024)

  "Settings.apply" should "default the timeouts" in {
    base.timeouts shouldBe ProviderTimeouts.default
  }

  "Settings setters" should "each change one field" in {
    val timeouts = ProviderTimeouts(request = Some(10.seconds))
    val changed = base
      .withProviderName("q")
      .withDisplayName("Q")
      .withModel("n")
      .withBaseUrl("https://g/v1")
      .withCredential(Credential.Static("k"))
      .withContextWindow(4096)
      .withReserveCompletion(512)
      .withTimeouts(timeouts)
    changed shouldBe Settings("q", "Q", "n", "https://g/v1", Credential.Static("k"), 4096, 512, timeouts)
  }

  "Settings.toString" should "not reveal a static key" in {
    (base.withCredential(Credential.Static("secret-key")).toString should not).include("secret-key")
  }
}
