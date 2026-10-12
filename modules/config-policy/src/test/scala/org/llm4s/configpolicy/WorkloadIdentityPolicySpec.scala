package org.llm4s.configpolicy

import org.llm4s.config.ApiKeySource
import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The `ownApiKey` rule asks that a section not inherit the vendor's shared key; workload identity uses none. */
class WorkloadIdentityPolicySpec extends AnyWordSpec with Matchers {

  "ConfigPolicyEngine.checkApiKeySources" should {
    "accept a section that authenticates with workload identity" in {
      val sources = Map[ProviderName, ApiKeySource](
        ProviderName("dbx") -> ApiKeySource.WorkloadIdentity("llm4s.providers.dbx.auth")
      )
      ConfigPolicyEngine.checkApiKeySources(
        sources,
        ConfigPolicy.prodSafeDefaults,
        CatalogEnvironment.Prod
      ) shouldBe empty
    }

    "still flag a section that inherits the shared key next to one that uses workload identity" in {
      val sources = Map[ProviderName, ApiKeySource](
        ProviderName("dbx")    -> ApiKeySource.WorkloadIdentity("llm4s.providers.dbx.auth"),
        ProviderName("shared") -> ApiKeySource.Credentials("llm4s.credentials.openai.apiKey")
      )
      ConfigPolicyEngine
        .checkApiKeySources(sources, ConfigPolicy.prodSafeDefaults, CatalogEnvironment.Prod)
        .map(_.message) should have size 1
    }
  }
}
