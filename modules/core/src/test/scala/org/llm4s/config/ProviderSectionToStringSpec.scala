package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A provider section in a log line or an error message shows no credential (#2804). */
class ProviderSectionToStringSpec extends AnyFlatSpec with Matchers {

  "RawNamedProviderSection.toString" should "redact the key, header values, extras and the auth block" in {
    val text = RawNamedProviderSection(
      provider = Some("openai"),
      model = Some("gpt-4o-mini"),
      baseUrl = Some("https://alice:url-password@gateway.example/v1"),
      apiKey = Some(ApiKey("sk-section-key")),
      headers = Some(Map("x-api-key" -> "header-secret")),
      extras = Map("organization" -> "org-secret"),
      auth = Some(Map("identityToken" -> "literal-identity-token", "audience" -> "api://llm"))
    ).toString

    for (secret <- Seq("sk-section-key", "header-secret", "org-secret", "literal-identity-token", "url-password"))
      withClue(secret)((text should not).include(secret))
    // names stay: they are what a user needs to debug a section
    for (name <- Seq("openai", "gpt-4o-mini", "gateway.example", "x-api-key", "organization", "identityToken"))
      withClue(name)(text should include(name))
  }

  "NamedProviderConfig.toString" should "leave out the userinfo of its base URL" in {
    val text = NamedProviderConfig(
      provider = ProviderId("openai"),
      model = ModelName("gpt-4o-mini"),
      baseUrl = Some(BaseUrl("https://alice:url-password@gateway.example/v1")),
      apiKey = None
    ).toString

    (text should not).include("url-password")
    (text should not).include("alice")
    text should include("gateway.example/v1")
  }
}
