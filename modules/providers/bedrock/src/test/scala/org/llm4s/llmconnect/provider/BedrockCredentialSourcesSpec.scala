package org.llm4s.llmconnect.provider

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.auth.credentials.internal.WebIdentityCredentialsUtils

import scala.util.Try

/**
 * The AWS SDK loads web-identity (EKS IRSA), `role_arn` and SSO credentials through the `sts`, `sso` and
 * `ssooidc` modules reflectively, and skips a source whose module is missing: on EKS the default chain then falls
 * through to the node's instance role (#3127). These checks fail if a dependency change removes a module, even
 * when another llm4s module would have brought it in for an application.
 */
class BedrockCredentialSourcesSpec extends AnyWordSpec with Matchers {

  private def loads(className: String): Boolean =
    Try(Class.forName(className, false, getClass.getClassLoader)).isSuccess

  "llm4s-bedrock's classpath" should {
    "let the default chain load the web-identity (IRSA) provider" in {
      Try(WebIdentityCredentialsUtils.factory()).isSuccess shouldBe true
      loads("software.amazon.awssdk.services.sts.internal.StsWebIdentityCredentialsProviderFactory") shouldBe true
    }

    "let a profile load role_arn (assume-role) credentials" in {
      loads("software.amazon.awssdk.services.sts.internal.StsProfileCredentialsProviderFactory") shouldBe true
    }

    "let a profile load SSO credentials and refresh their token" in {
      loads("software.amazon.awssdk.services.sso.auth.SsoProfileCredentialsProviderFactory") shouldBe true
      loads("software.amazon.awssdk.services.ssooidc.SsoOidcProfileTokenProviderFactory") shouldBe true
    }
  }
}
