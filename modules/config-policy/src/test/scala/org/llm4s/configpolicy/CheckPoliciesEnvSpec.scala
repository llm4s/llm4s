package org.llm4s.configpolicy

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CheckPoliciesEnvSpec extends AnyWordSpec with Matchers {

  "CheckPolicies.resolveEnv" should {

    "resolve dev" in {
      CheckPolicies.resolveEnv("dev") shouldBe Right(
        (CatalogEnvironment.Dev, ConfigPolicy.devSandbox)
      )
    }

    "resolve dev-sandbox to the dev environment" in {
      CheckPolicies.resolveEnv("dev-sandbox") shouldBe Right(
        (CatalogEnvironment.Dev, ConfigPolicy.devSandbox)
      )
    }

    "resolve staging" in {
      CheckPolicies.resolveEnv("staging") shouldBe Right(
        (CatalogEnvironment.Staging, ConfigPolicy.prodSafeDefaults)
      )
    }

    "resolve prod" in {
      CheckPolicies.resolveEnv("prod") shouldBe Right(
        (CatalogEnvironment.Prod, ConfigPolicy.prodSafeDefaults)
      )
    }

    "resolve prod-safe" in {
      CheckPolicies.resolveEnv("prod-safe") shouldBe Right(
        (CatalogEnvironment.Prod, ConfigPolicy.prodSafeDefaults)
      )
    }

    "resolve permissive" in {
      CheckPolicies.resolveEnv("permissive") shouldBe Right(
        (CatalogEnvironment.Prod, ConfigPolicy.permissive)
      )
    }

    "resolve none" in {
      CheckPolicies.resolveEnv("none") shouldBe Right(
        (CatalogEnvironment.Prod, ConfigPolicy.permissive)
      )
    }

    "match accepted names case-insensitively" in {
      CheckPolicies.resolveEnv("DEV-SANDBOX") shouldBe Right(
        (CatalogEnvironment.Dev, ConfigPolicy.devSandbox)
      )
    }

    "refuse unknown environment names with a message listing accepted values" in {
      CheckPolicies.resolveEnv("prdo") shouldBe Left(
        "Unknown --env 'prdo'. Use one of: dev, dev-sandbox, staging, prod, prod-safe, permissive, none"
      )
      CheckPolicies.resolveEnv("devel") shouldBe Left(
        "Unknown --env 'devel'. Use one of: dev, dev-sandbox, staging, prod, prod-safe, permissive, none"
      )
    }
  }
}
